package com.localai.server.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.localai.server.BootReceiver
import com.localai.server.LocalAIApp
import com.localai.server.MainActivity
import com.localai.server.engine.LlmEngine
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID

class AiServerService : Service() {

    private var httpServer: AiHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val DEFAULT_PORT = 8765
        private val FALLBACK_PORTS = listOf(8765, 8766, 8767, 8780)
        const val CHANNEL_ID = "ai_server"
        const val NOTIFICATION_ID = 1

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _activePort = MutableStateFlow(DEFAULT_PORT)
        val activePort: StateFlow<Int> = _activePort

        private val _requestCount = MutableStateFlow(0)
        val requestCount: StateFlow<Int> = _requestCount

        private val _logs = MutableStateFlow<List<String>>(emptyList())
        val logs: StateFlow<List<String>> = _logs

        fun addLog(msg: String) {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val entry = "[$ts] $msg"
            _logs.value = (_logs.value + entry).takeLast(50)
            Log.d("AiServer", entry)
        }

        fun start(context: Context) {
            // Mark "should be running" so we auto-recover after process kill
            (context.applicationContext as? LocalAIApp)?.settings?.serverShouldBeRunning = true
            val intent = Intent(context, AiServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            (context.applicationContext as? LocalAIApp)?.settings?.serverShouldBeRunning = false
            context.stopService(Intent(context, AiServerService::class.java))
        }

        // ── Diagnostics ──
        private val _processPid = MutableStateFlow(0)
        val processPid: StateFlow<Int> = _processPid

        private val _serviceRestartCount = MutableStateFlow(0)
        val serviceRestartCount: StateFlow<Int> = _serviceRestartCount

        private val _lastSelfPingOk = MutableStateFlow(true)
        val lastSelfPingOk: StateFlow<Boolean> = _lastSelfPingOk

        // ── Request tracking ──
        data class TaskEvent(val timestamp: Long, val label: String)

        data class RequestTask(
            val id: String,
            val endpoint: String,
            val method: String,
            val clientIp: String,
            val userAgent: String = "",
            val startTime: Long,
            val endTime: Long? = null,
            val status: TaskStatus = TaskStatus.PROCESSING,
            val inputBytes: Long = 0,
            val outputBytes: Long = 0,
            val error: String? = null,
            val preview: String? = null,
            val requestBody: String? = null,   // full body (capped at 8KB)
            val responseBody: String? = null,  // full text (capped at 16KB)
            val parameters: Map<String, String> = emptyMap(),
            val httpStatus: Int = 0,
            val streamingChunks: Int = 0,
            val events: List<TaskEvent> = emptyList()
        ) {
            val durationMs: Long get() = (endTime ?: System.currentTimeMillis()) - startTime
        }

        enum class TaskStatus { PROCESSING, COMPLETED, FAILED }

        private const val MAX_BODY_BYTES = 16 * 1024

        // Per-task cancel tokens for stopping in-flight inference
        private val cancelTokens = java.util.concurrent.ConcurrentHashMap<String, LlmEngine.CancellationToken>()

        fun cancelTask(taskId: String): Boolean {
            val token = cancelTokens[taskId] ?: return false
            token.cancel()
            addLog("⏹ Cancel requested for $taskId")
            return true
        }

        fun registerCancelToken(taskId: String): LlmEngine.CancellationToken {
            val token = LlmEngine.CancellationToken()
            cancelTokens[taskId] = token
            return token
        }

        fun unregisterCancelToken(taskId: String) {
            cancelTokens.remove(taskId)
        }

        private const val MAX_TASKS = 200
        private val _tasks = MutableStateFlow<List<RequestTask>>(emptyList())
        val tasks: StateFlow<List<RequestTask>> = _tasks

        private val _successCount = MutableStateFlow(0)
        val successCount: StateFlow<Int> = _successCount

        private val _failCount = MutableStateFlow(0)
        val failCount: StateFlow<Int> = _failCount

        private val _totalLatencyMs = MutableStateFlow(0L)
        val totalLatencyMs: StateFlow<Long> = _totalLatencyMs

        private val _activeTaskCount = MutableStateFlow(0)
        val activeTaskCount: StateFlow<Int> = _activeTaskCount

        fun createTask(
            endpoint: String, method: String, clientIp: String,
            userAgent: String = "", inputBytes: Long = 0
        ): String {
            val id = "req-${System.currentTimeMillis()}-${(1000..9999).random()}"
            val now = System.currentTimeMillis()
            val task = RequestTask(
                id = id, endpoint = endpoint, method = method,
                clientIp = clientIp, userAgent = userAgent, startTime = now,
                inputBytes = inputBytes,
                events = listOf(TaskEvent(now, "Đã nhận request"))
            )
            _tasks.update { (listOf(task) + it).take(MAX_TASKS) }
            _activeTaskCount.update { it + 1 }
            return id
        }

        fun updateTaskPreview(taskId: String, preview: String) {
            _tasks.update { list -> list.map { if (it.id == taskId) it.copy(preview = preview.take(120)) else it } }
        }

        fun setTaskRequestBody(taskId: String, body: String, params: Map<String, String> = emptyMap()) {
            val capped = if (body.length > MAX_BODY_BYTES) body.take(MAX_BODY_BYTES) + "\n...(truncated)" else body
            _tasks.update { list ->
                list.map { if (it.id == taskId) it.copy(requestBody = capped, parameters = params) else it }
            }
        }

        fun addTaskEvent(taskId: String, label: String) {
            val now = System.currentTimeMillis()
            _tasks.update { list ->
                list.map {
                    if (it.id == taskId) it.copy(events = it.events + TaskEvent(now, label))
                    else it
                }
            }
        }

        fun incrementTaskChunks(taskId: String) {
            _tasks.update { list ->
                list.map { if (it.id == taskId) it.copy(streamingChunks = it.streamingChunks + 1) else it }
            }
        }

        fun completeTask(
            taskId: String, outputBytes: Long = 0,
            preview: String? = null, responseBody: String? = null, httpStatus: Int = 200
        ) {
            val now = System.currentTimeMillis()
            var latency = 0L
            val cappedBody = responseBody?.let {
                if (it.length > MAX_BODY_BYTES) it.take(MAX_BODY_BYTES) + "\n...(truncated)" else it
            }
            _tasks.update { list ->
                list.map {
                    if (it.id == taskId) {
                        latency = now - it.startTime
                        it.copy(endTime = now, status = TaskStatus.COMPLETED,
                            outputBytes = outputBytes,
                            preview = preview?.take(120) ?: it.preview,
                            responseBody = cappedBody ?: it.responseBody,
                            httpStatus = httpStatus,
                            events = it.events + TaskEvent(now, "Hoàn thành"))
                    } else it
                }
            }
            _activeTaskCount.update { (it - 1).coerceAtLeast(0) }
            _successCount.update { it + 1 }
            _totalLatencyMs.update { it + latency }
            unregisterCancelToken(taskId)
        }

        fun failTask(taskId: String, error: String, httpStatus: Int = 500) {
            val now = System.currentTimeMillis()
            var latency = 0L
            _tasks.update { list ->
                list.map {
                    if (it.id == taskId) {
                        latency = now - it.startTime
                        it.copy(endTime = now, status = TaskStatus.FAILED,
                            error = error.take(500),
                            httpStatus = httpStatus,
                            events = it.events + TaskEvent(now, "Thất bại: $error".take(200)))
                    } else it
                }
            }
            _activeTaskCount.update { (it - 1).coerceAtLeast(0) }
            _failCount.update { it + 1 }
            _totalLatencyMs.update { it + latency }
            unregisterCancelToken(taskId)
        }

        fun clearTaskHistory() {
            _tasks.update { list -> list.filter { it.status == TaskStatus.PROCESSING } }
            _successCount.value = 0
            _failCount.value = 0
            _totalLatencyMs.value = 0
            _requestCount.value = 0
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isRestart = intent == null
        _processPid.value = android.os.Process.myPid()
        _serviceRestartCount.update { it + 1 }
        if (isRestart) {
            addLog("⚠ Service重启 (PID=${_processPid.value}, 第${_serviceRestartCount.value}) - 进程可能已被终止")
        } else {
            addLog("Service启动 (PID=${_processPid.value})")
        }

        startForeground(NOTIFICATION_ID, buildNotification("Đang khởi động máy chủ..."))

        // Acquire WakeLock if enabled
        val app = application as LocalAIApp
        if (app.settings.keepAwake) {
            acquireWakeLock()
        }

        startHttpServer()

        // Auto-load: priority 1 = intent extra (BootReceiver), priority 2 = last model if restart
        val autoLoadPath = intent?.getStringExtra(BootReceiver.EXTRA_AUTO_LOAD_MODEL)
            ?: if (isRestart && app.settings.serverShouldBeRunning) app.settings.lastModelPath.takeIf { it.isNotBlank() } else null

        if (autoLoadPath != null && !app.engine.isLoaded) {
            addLog("正在自动加载模型: ${autoLoadPath.substringAfterLast('/')}")
            serviceScope.launch {
                app.engine.loadModel(autoLoadPath)
                    .onSuccess { addLog("模型自动加载成功") }
                    .onFailure { e -> addLog("自动加载失败: ${e.message}") }
            }
        }

        // Start watchdog: self-ping HTTP server every 15s
        startWatchdog()

        return START_STICKY
    }

    private var watchdogJob: kotlinx.coroutines.Job? = null
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                val port = _activePort.value
                val ok = try {
                    val sock = java.net.Socket()
                    sock.connect(java.net.InetSocketAddress("127.0.0.1", port), 2000)
                    sock.close()
                    true
                } catch (_: Exception) { false }
                _lastSelfPingOk.value = ok
                if (!ok && _isRunning.value) {
                    addLog("⚠ Watchdog: HTTP服务器无响应,正在重启...")
                    stopHttpServer()
                    startHttpServer()
                }
            }
        }
    }

    override fun onDestroy() {
        addLog("Service销毁 (PID=${android.os.Process.myPid()})")
        watchdogJob?.cancel()
        stopHttpServer()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalAIServer::ServerWakeLock").apply {
            acquire()
        }
        addLog("已激活WakeLock - CPU将保持活动")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
            addLog("已释放WakeLock")
        }
    }

    private fun startHttpServer() {
        if (httpServer != null) return
        val app = application as LocalAIApp
        for (port in FALLBACK_PORTS) {
            try {
                httpServer = AiHttpServer(port, app.engine).also { it.start() }
                _isRunning.value = true
                _activePort.value = port
                updateNotification("Đang chạy trên cổng $port")
                addLog("服务器已在端口 :$port")
                return
            } catch (e: Exception) {
                addLog("端口 $port 忙,尝试下一个端口...")
            }
        }
        addLog("启动失败: 所有端口都忙")
        _isRunning.value = false
    }

    private fun stopHttpServer() {
        httpServer?.stop()
        httpServer = null
        _isRunning.value = false
        _requestCount.value = 0
        addLog("服务器已停止")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Máy chủ AI", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Máy chủ AI chạy trên thiết bị"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Máy chủ AI Local")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    /**
     * HTTP Server with both legacy API and OpenAI-compatible API.
     * Supports CORS, SSE streaming, and concurrent request queuing.
     */
    class AiHttpServer(port: Int, private val engine: LlmEngine) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method

            // Handle CORS preflight - no tracking
            if (method == Method.OPTIONS) {
                return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
            }

            // Skip tracking for noisy health checks
            val trackable = uri != "/api/health"
            val clientIp = session.headers["http-client-ip"] ?: session.remoteIpAddress ?: "unknown"
            val userAgent = session.headers["user-agent"] ?: ""
            val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
            val taskId = if (trackable) createTask(uri, method.name, clientIp, userAgent, contentLength) else null

            return try {
                val response = when {
                    // ── OpenAI-compatible API ──
                    uri == "/v1/chat/completions" && method == Method.POST -> handleOpenAIChat(session, taskId)
                    uri == "/v1/models" && method == Method.GET -> handleOpenAIModels().also {
                        taskId?.let { id -> completeTask(id, 0, null, null, 200) }
                    }

                    // ── Media endpoints (multimodal, requires Gemma 4) ──
                    uri == "/v1/audio/transcriptions" && method == Method.POST -> handleAudioTranscription(session, taskId = taskId)
                    uri == "/v1/audio/translations" && method == Method.POST -> handleAudioTranscription(session, translate = true, taskId = taskId)
                    uri == "/v1/images/describe" && method == Method.POST -> handleImageDescribe(session, taskId)
                    uri == "/v1/video/analyze" && method == Method.POST -> handleVideoAnalyze(session, taskId)

                    // ── Legacy API ──
                    uri == "/api/health" && method == Method.GET -> handleHealth()
                    uri == "/api/chat" && method == Method.POST -> handleChat(session, taskId)
                    uri == "/api/models" && method == Method.GET -> handleModels().also {
                        taskId?.let { id -> completeTask(id, 0, null, null, 200) }
                    }
                    uri == "/api/process-file" && method == Method.POST -> handleProcessFile(session, taskId)

                    else -> {
                        taskId?.let { failTask(it, "Not found: $uri", 404) }
                        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_JSON,
                            """{"error":{"message":"Not found: $uri","type":"invalid_request_error","code":"not_found"}}""")
                    }
                }
                corsResponse(response)
            } catch (e: Exception) {
                addLog("错误: ${e.message}")
                taskId?.let { failTask(it, e.message ?: "server error") }
                corsResponse(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_JSON,
                    """{"error":{"message":"${e.message?.replace("\"", "'")}","type":"server_error"}}"""))
            }
        }

        // ── CORS ──

        private fun corsResponse(response: Response): Response {
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With")
            response.addHeader("Access-Control-Max-Age", "86400")
            return response
        }

        // ── OpenAI-compatible: /v1/chat/completions ──

        private fun handleOpenAIChat(session: IHTTPSession, taskId: String? = null): Response {
            if (!engine.isLoaded) {
                taskId?.let { failTask(it, "model not loaded", 503) }
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_JSON,
                    """{"error":{"message":"Chưa tải mô hình. Hãy mở app Local AI Server và tải mô hình.","type":"server_error","code":"model_not_loaded"}}""")
            }

            // Ensure UTF-8 encoding by modifying Content-Type before parseBody
            val originalContentType = session.headers["content-type"]
            if (originalContentType != null) {
                val ct = NanoHTTPD.ContentType(originalContentType).tryUTF8()
                session.headers["content-type"] = ct.contentTypeHeader
            }

            val files = HashMap<String, String>()
            session.parseBody(files)
            val body = files["postData"] ?: ""
            addLog("Body length: ${body.length} chars")
            val json = JSONObject(body as String)

            // Parse messages array
            val messagesArr = json.optJSONArray("messages")
            if (messagesArr == null || messagesArr.length() == 0) {
                taskId?.let { failTask(it, "messages array required", 400) }
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"error":{"message":"messages array is required","type":"invalid_request_error"}}""")
            }

            // Parse messages - supports both text content and multimodal content arrays
            val messages = mutableListOf<LlmEngine.ChatMessage>()
            val lastMessageImages = mutableListOf<ByteArray>()
            val lastMessageAudios = mutableListOf<ByteArray>()

            for (i in 0 until messagesArr.length()) {
                val msg = messagesArr.getJSONObject(i)
                val role = msg.optString("role", "user")
                val contentValue = msg.opt("content")

                val textContent: String
                val isLast = (i == messagesArr.length() - 1)

                if (contentValue is JSONArray) {
                    // Multimodal content array (OpenAI vision format)
                    val textParts = StringBuilder()
                    for (j in 0 until contentValue.length()) {
                        val part = contentValue.getJSONObject(j)
                        when (part.optString("type")) {
                            "text" -> textParts.append(part.optString("text"))
                            "image_url" -> {
                                if (isLast) {
                                    val url = part.optJSONObject("image_url")?.optString("url") ?: ""
                                    decodeBase64DataUrl(url)?.let { lastMessageImages.add(it) }
                                }
                            }
                            "input_audio" -> {
                                if (isLast) {
                                    val audio = part.optJSONObject("input_audio")
                                    val data = audio?.optString("data") ?: ""
                                    decodeBase64(data)?.let { lastMessageAudios.add(it) }
                                }
                            }
                        }
                    }
                    textContent = textParts.toString()
                } else {
                    textContent = contentValue?.toString() ?: ""
                }

                messages.add(LlmEngine.ChatMessage(role = role, content = textContent))
            }

            val temperature = if (json.has("temperature")) json.getDouble("temperature").toFloat() else engine.defaultTemperature.toFloat()
            val topK = if (json.has("top_k")) json.getInt("top_k") else engine.defaultTopK
            val topP = if (json.has("top_p")) json.getDouble("top_p").toFloat() else engine.defaultTopP.toFloat()
            val stream = json.optBoolean("stream", false)
            val modelName = engine.loadedModelName.value ?: "local-model"
            val hasMedia = lastMessageImages.isNotEmpty() || lastMessageAudios.isNotEmpty()

            _requestCount.update { it + 1 }
            val preview = messages.lastOrNull()?.content?.take(50) ?: ""
            val mediaInfo = if (hasMedia) " [img:${lastMessageImages.size} audio:${lastMessageAudios.size}]" else ""
            addLog("聊天请求 (${if (stream) "streaming" else "đồng bộ"})$mediaInfo - $preview...")
            taskId?.let { tid ->
                updateTaskPreview(tid, preview + mediaInfo)
                val params = mapOf(
                    "temperature" to "$temperature",
                    "top_k" to "$topK",
                    "top_p" to "$topP",
                    "stream" to "$stream",
                    "messages" to "${messages.size}",
                    "images" to "${lastMessageImages.size}",
                    "audios" to "${lastMessageAudios.size}"
                )
                setTaskRequestBody(tid, body, params)
                addTaskEvent(tid, "Parsed: ${messages.size} messages" + if (hasMedia) ", $mediaInfo" else "")
            }

            // Multimodal requires litertlm model
            if (hasMedia && !engine.isMultimodal) {
                taskId?.let { failTask(it, "model not multimodal", 400) }
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"error":{"message":"Mô hình hiện tại không hỗ trợ đa phương tiện. Hãy tải Gemma 4 (.litertlm).","type":"invalid_request_error","code":"model_not_multimodal"}}""")
            }

            if (stream) {
                return if (hasMedia) {
                    handleOpenAIChatStreamingMultimodal(messages, lastMessageImages, lastMessageAudios, temperature, topK, topP, modelName, taskId)
                } else {
                    handleOpenAIChatStreaming(messages, temperature, topK, topP, modelName, taskId)
                }
            }

            // Non-streaming multimodal
            if (hasMedia) {
                val startTime = System.currentTimeMillis()
                val promptText = messages.lastOrNull()?.content ?: ""
                val response = engine.generateMultimodal(promptText, lastMessageImages, lastMessageAudios, temperature, topK, topP)
                val elapsed = System.currentTimeMillis() - startTime
                addLog("响应耗时 ${elapsed}ms (${response.length} 字符)")
                taskId?.let { completeTask(it, response.length.toLong(), response.take(100), response, 200) }

                val chatId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
                val result = JSONObject().apply {
                    put("id", chatId)
                    put("object", "chat.completion")
                    put("created", System.currentTimeMillis() / 1000)
                    put("model", modelName)
                    put("choices", JSONArray().put(JSONObject().apply {
                        put("index", 0)
                        put("message", JSONObject().apply {
                            put("role", "assistant")
                            put("content", response)
                        })
                        put("finish_reason", "stop")
                    }))
                    put("usage", JSONObject().apply {
                        put("prompt_tokens", promptText.length / 4)
                        put("completion_tokens", response.length / 4)
                        put("total_tokens", (promptText.length + response.length) / 4)
                    })
                }
                return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
            }

            // Non-streaming response
            val startTime = System.currentTimeMillis()
            val response = engine.generateFromMessages(messages, temperature, topK, topP)
            val elapsed = System.currentTimeMillis() - startTime

            addLog("响应耗时 ${elapsed}ms (${response.length} 字符)")
            taskId?.let { completeTask(it, response.length.toLong(), response.take(100), response, 200) }

            val chatId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
            val result = JSONObject().apply {
                put("id", chatId)
                put("object", "chat.completion")
                put("created", System.currentTimeMillis() / 1000)
                put("model", modelName)
                put("choices", JSONArray().put(JSONObject().apply {
                    put("index", 0)
                    put("message", JSONObject().apply {
                        put("role", "assistant")
                        put("content", response)
                    })
                    put("finish_reason", "stop")
                }))
                put("usage", JSONObject().apply {
                    put("prompt_tokens", messages.sumOf { it.content.length } / 4)
                    put("completion_tokens", response.length / 4)
                    put("total_tokens", (messages.sumOf { it.content.length } + response.length) / 4)
                })
            }
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
        }

        private fun handleOpenAIChatStreaming(
            messages: List<LlmEngine.ChatMessage>,
            temperature: Float,
            topK: Int,
            topP: Float,
            modelName: String,
            taskId: String? = null
        ): Response {
            val chatId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
            val created = System.currentTimeMillis() / 1000
            val pipedIn = PipedInputStream(16384)
            val pipedOut = PipedOutputStream(pipedIn)

            Thread({
                try {
                    val writer = pipedOut.bufferedWriter()

                    // First chunk: role
                    val roleChunk = JSONObject().apply {
                        put("id", chatId)
                        put("object", "chat.completion.chunk")
                        put("created", created)
                        put("model", modelName)
                        put("choices", JSONArray().put(JSONObject().apply {
                            put("index", 0)
                            put("delta", JSONObject().put("role", "assistant"))
                            put("finish_reason", JSONObject.NULL)
                        }))
                    }
                    writer.write("data: $roleChunk\n\n")
                    writer.flush()

                    val startTime = System.currentTimeMillis()
                    taskId?.let { addTaskEvent(it, "Bắt đầu stream") }
                    val token = taskId?.let { registerCancelToken(it) }

                    engine.generateStreamingFromMessages(
                        messages, temperature, topK, topP, cancelToken = token,
                        onChunk = { chunk ->
                            try {
                                taskId?.let { incrementTaskChunks(it) }
                                val chunkJson = JSONObject().apply {
                                    put("id", chatId)
                                    put("object", "chat.completion.chunk")
                                    put("created", created)
                                    put("model", modelName)
                                    put("choices", JSONArray().put(JSONObject().apply {
                                        put("index", 0)
                                        put("delta", JSONObject().put("content", chunk))
                                        put("finish_reason", JSONObject.NULL)
                                    }))
                                }
                                writer.write("data: $chunkJson\n\n")
                                writer.flush()
                            } catch (_: Exception) {}
                        },
                        onComplete = { fullText ->
                            try {
                                val elapsed = System.currentTimeMillis() - startTime
                                addLog("Stream完成耗时 ${elapsed}ms (${fullText.length} 字符)")
                                taskId?.let { completeTask(it, fullText.length.toLong(), fullText.take(100), fullText, 200) }

                                val doneChunk = JSONObject().apply {
                                    put("id", chatId)
                                    put("object", "chat.completion.chunk")
                                    put("created", created)
                                    put("model", modelName)
                                    put("choices", JSONArray().put(JSONObject().apply {
                                        put("index", 0)
                                        put("delta", JSONObject())
                                        put("finish_reason", "stop")
                                    }))
                                }
                                writer.write("data: $doneChunk\n\n")
                                writer.write("data: [DONE]\n\n")
                                writer.flush()
                                writer.close()
                            } catch (_: Exception) {}
                        },
                        onError = { e ->
                            try {
                                addLog("Stream错误: ${e.message}")
                                taskId?.let { failTask(it, e.message ?: "stream error") }
                                val errorJson = JSONObject().apply {
                                    put("error", JSONObject().apply {
                                        put("message", e.message)
                                        put("type", "server_error")
                                    })
                                }
                                writer.write("data: $errorJson\n\n")
                                writer.write("data: [DONE]\n\n")
                                writer.flush()
                                writer.close()
                            } catch (_: Exception) {}
                        }
                    )
                } catch (e: Exception) {
                    taskId?.let { failTask(it, e.message ?: "stream error") }
                    try { pipedOut.close() } catch (_: Exception) {}
                }
            }, "sse-stream-$chatId").start()

            val response = newChunkedResponse(Response.Status.OK, "text/event-stream", pipedIn)
            response.addHeader("Cache-Control", "no-cache")
            response.addHeader("Connection", "keep-alive")
            response.addHeader("X-Accel-Buffering", "no")
            return response
        }

        // ── OpenAI-compatible: /v1/models ──

        private fun handleOpenAIModels(): Response {
            val models = engine.findLocalModels()
            val data = JSONArray()

            // Add loaded model first
            val loadedName = engine.loadedModelName.value
            if (loadedName != null) {
                data.put(JSONObject().apply {
                    put("id", loadedName)
                    put("object", "model")
                    put("created", System.currentTimeMillis() / 1000)
                    put("owned_by", "local")
                })
            }

            // Add all local models
            models.forEach { m ->
                if (m.name != loadedName) {
                    data.put(JSONObject().apply {
                        put("id", m.name)
                        put("object", "model")
                        put("created", System.currentTimeMillis() / 1000)
                        put("owned_by", "local")
                    })
                }
            }

            val result = JSONObject().apply {
                put("object", "list")
                put("data", data)
            }
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
        }

        // ── Legacy: /api/health ──

        private fun handleHealth(): Response {
            val json = JSONObject().apply {
                put("status", if (engine.isLoaded) "ok" else "no_model")
                put("model", engine.loadedModelName.value ?: "none")
                put("multimodal", engine.isMultimodal)
                put("port", _activePort.value)
                put("version", "2.4")
                put("pid", _processPid.value)
                put("service_restarts", _serviceRestartCount.value)
                val rt = Runtime.getRuntime()
                put("memory", JSONObject().apply {
                    put("heap_used_mb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024))
                    put("heap_max_mb", rt.maxMemory() / (1024 * 1024))
                })
                put("queue_depth", engine.queueDepth.value)
                put("inference_count", engine.getInferenceCount())
                put("tokens_per_second", engine.tokensPerSecond.value)
                put("endpoints", JSONArray().apply {
                    put("/v1/chat/completions")
                    put("/v1/models")
                    put("/v1/audio/transcriptions")
                    put("/v1/audio/translations")
                    put("/v1/images/describe")
                    put("/v1/video/analyze")
                    put("/api/health")
                    put("/api/chat")
                    put("/api/models")
                })
            }
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.toString())
        }

        // ── Legacy: /api/models ──

        private fun handleModels(): Response {
            val arr = JSONArray()
            engine.findLocalModels().forEach { m ->
                arr.put(JSONObject().apply {
                    put("name", m.name)
                    put("path", m.path)
                    put("size_mb", m.sizeMb)
                })
            }
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, arr.toString())
        }

        // ── Legacy: /api/chat ──

        // ── Legacy: /api/process-file (multimodal OCR/describe/transcribe) ──
        private fun handleProcessFile(session: IHTTPSession, taskId: String? = null): Response {
            if (!engine.isLoaded) {
                taskId?.let { failTask(it, "model not loaded", 503) }
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_JSON,
                    """{"error":"Chưa tải mô hình"}""")
            }

            val uploaded = readUploadedFile(session, "file")
            if (uploaded == null) {
                taskId?.let { failTask(it, "missing file field", 400) }
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"error":"Thiếu trường 'file' trong multipart/form-data"}""")
            }
            val (fileBytes, fileName) = uploaded

            // Guard against OOM with large files
            if (fileBytes.size > 10 * 1024 * 1024) {
                taskId?.let { failTask(it, "file too large", 413) }
                return newFixedLengthResponse(Response.Status.PAYLOAD_TOO_LARGE, MIME_JSON,
                    """{"error":"File quá lớn (${fileBytes.size / 1024 / 1024}MB). Tối đa 10MB."}""")
            }

            val task = getFormField(session, "task") ?: "describe"
            val language = getFormField(session, "language") ?: "vi"
            val customPrompt = getFormField(session, "custom_prompt")

            val prompt = when (task) {
                "ocr" -> "Trích xuất toàn bộ chữ từ ảnh này. CHỈ trả về chữ đã trích xuất, giữ nguyên format."
                "describe" -> "Mô tả chi tiết ảnh này bằng $language. Liệt kê đối tượng, người, bối cảnh, màu sắc, hành động."
                "transcribe" -> "Phiên âm audio này bằng $language. CHỈ trả về văn bản phiên âm."
                "summarize" -> "Tóm tắt nội dung file này bằng $language 3-5 câu."
                "analyze" -> "Phân tích nội dung file này bằng $language."
                "extract" -> "Trích xuất thông tin quan trọng từ file này."
                "translate" -> "Dịch nội dung file này sang $language."
                "custom" -> customPrompt ?: "Mô tả nội dung file này."
                else -> "Mô tả nội dung file này bằng $language."
            }

            _requestCount.update { it + 1 }
            addLog("处理文件: $fileName (${fileBytes.size / 1024}KB, task=$task)")
            taskId?.let { tid ->
                updateTaskPreview(tid, "$task: $fileName (${fileBytes.size / 1024}KB)")
                setTaskRequestBody(tid, "file=$fileName\ntask=$task\nlanguage=$language", mapOf(
                    "task" to task, "file" to fileName,
                    "size_kb" to "${fileBytes.size / 1024}"
                ))
            }

            val isImage = fileName.substringAfterLast('.', "").lowercase() in setOf("jpg","jpeg","png","webp","gif","bmp")
            // Non-multimodal model can't process images
            if (isImage && !engine.isMultimodal) {
                taskId?.let { failTask(it, "model not multimodal", 400) }
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"error":"Mô hình hiện tại không hỗ trợ ảnh. Dùng Gemma 4 (.litertlm)."}""")
            }

            // Convert to PNG bytes for LiteRT-LM (Edge Gallery uses PNG)
            val processedBytes = if (isImage) {
                try {
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.size)
                    if (bmp != null) {
                        val maxDim = 768
                        val scaled = if (bmp.width > maxDim || bmp.height > maxDim) {
                            val scale = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
                            val sw = (bmp.width * scale).toInt().coerceAtLeast(1)
                            val sh = (bmp.height * scale).toInt().coerceAtLeast(1)
                            android.graphics.Bitmap.createScaledBitmap(bmp, sw, sh, true)
                        } else bmp
                        val baos = java.io.ByteArrayOutputStream()
                        scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                        if (scaled !== bmp) scaled.recycle()
                        bmp.recycle()
                        baos.toByteArray()
                    } else fileBytes
                } catch (oom: OutOfMemoryError) {
                    System.gc()
                    addLog("解码图像时内存不足")
                    taskId?.let { failTask(it, "OOM decoding image", 413) }
                    return newFixedLengthResponse(Response.Status.PAYLOAD_TOO_LARGE, MIME_JSON,
                        """{"error":"Ảnh quá lớn"}""")
                } catch (_: Exception) { fileBytes }
            } else fileBytes

            addLog("图像: ${fileBytes.size / 1024}KB → ${processedBytes.size / 1024}KB (PNG 768px)")

            return try {
                val startTime = System.currentTimeMillis()
                val text = if (isImage) {
                    engine.generateMultimodal(prompt, listOf(processedBytes), emptyList())
                } else {
                    engine.generateResponse(prompt)
                }
                val elapsed = System.currentTimeMillis() - startTime
                addLog("文件处理完成 ${elapsed}ms (${text.length} chars)")
                taskId?.let { completeTask(it, text.length.toLong(), text.take(100), text, 200) }

                newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                    JSONObject().apply {
                        put("result", text)
                        put("task", task)
                        put("filename", fileName)
                        put("file_size", fileBytes.size)
                        put("model", engine.loadedModelName.value ?: "unknown")
                        put("processing_time_ms", elapsed)
                    }.toString())
            } catch (e: Throwable) {
                addLog("处理文件错误: ${e.javaClass.simpleName}: ${e.message}")
                taskId?.let { failTask(it, "${e.javaClass.simpleName}: ${e.message}") }
                // Force GC to free memory after error
                System.gc()
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_JSON,
                    """{"error":"${e.message?.replace("\"","'") ?: "inference failed"}"}""")
            }
        }

        private fun handleChat(session: IHTTPSession, taskId: String? = null): Response {
            if (!engine.isLoaded) {
                taskId?.let { failTask(it, "model not loaded", 503) }
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_JSON,
                    """{"error":"Chưa tải mô hình. Hãy mở app Local AI Server và tải mô hình."}""")
            }

            // Ensure UTF-8 encoding by modifying Content-Type before parseBody
            val originalContentType = session.headers["content-type"]
            if (originalContentType != null) {
                val ct = NanoHTTPD.ContentType(originalContentType).tryUTF8()
                session.headers["content-type"] = ct.contentTypeHeader
            }

            val files = HashMap<String, String>()
            session.parseBody(files)
            val body = files["postData"] ?: ""
            val json = JSONObject(body)

            val prompt = if (json.has("prompt")) json.getString("prompt") else ""
            if (prompt.isBlank()) {
                taskId?.let { failTask(it, "prompt required", 400) }
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"error":"prompt is required"}""")
            }

            val temperature = if (json.has("temperature")) json.getDouble("temperature").toFloat() else 0.7f
            val topK = if (json.has("top_k")) json.getInt("top_k") else 64
            val topP = if (json.has("top_p")) json.getDouble("top_p").toFloat() else 0.95f

            _requestCount.update { it + 1 }
            addLog("聊天请求 (${prompt.take(50)}...)")
            taskId?.let { tid ->
                updateTaskPreview(tid, prompt.take(80))
                setTaskRequestBody(tid, body, mapOf(
                    "temperature" to "$temperature",
                    "top_k" to "$topK",
                    "top_p" to "$topP",
                    "prompt_length" to "${prompt.length}"
                ))
            }

            val startTime = System.currentTimeMillis()
            val response = engine.generateResponse(prompt, temperature, topK, topP)
            val elapsed = System.currentTimeMillis() - startTime

            addLog("响应耗时 ${elapsed}ms (${response.length} 字符)")
            taskId?.let { completeTask(it, response.length.toLong(), response.take(100), response, 200) }

            val result = JSONObject().apply {
                put("response", response)
                put("model", engine.loadedModelName.value ?: "unknown")
                put("processing_time_ms", elapsed)
            }
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString())
        }

        // ── Media endpoints ──

        /**
         * Read multipart file field into bytes. Returns null if field missing.
         * NanoHTTPD writes uploaded files to temp paths stored in the files map.
         */
        private fun readUploadedFile(session: IHTTPSession, vararg fieldNames: String): Pair<ByteArray, String>? {
            // Ensure UTF-8 encoding by modifying Content-Type before parseBody
            val originalContentType = session.headers["content-type"]
            if (originalContentType != null) {
                val ct = NanoHTTPD.ContentType(originalContentType).tryUTF8()
                session.headers["content-type"] = ct.contentTypeHeader
            }

            val files = HashMap<String, String>()
            try { session.parseBody(files) } catch (_: Exception) { return null }
            for (name in fieldNames) {
                val tempPath = files[name] ?: continue
                try {
                    val bytes = java.io.File(tempPath).readBytes()
                    val origName = session.parameters[name]?.firstOrNull() ?: name
                    return bytes to origName
                } catch (_: Exception) {}
            }
            return null
        }

        private fun getFormField(session: IHTTPSession, name: String): String? {
            return session.parameters[name]?.firstOrNull()
        }

        private fun requireMultimodal(taskId: String? = null): Response? {
            if (!engine.isLoaded) {
                taskId?.let { failTask(it, "model not loaded", 503) }
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_JSON,
                    """{"error":{"message":"Chưa tải mô hình. Hãy mở app và tải Gemma 4.","type":"server_error","code":"model_not_loaded"}}""")
            }
            if (!engine.isMultimodal) {
                taskId?.let { failTask(it, "model not multimodal", 400) }
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"error":{"message":"Endpoint này yêu cầu Gemma 4 (.litertlm). Mô hình hiện tại không hỗ trợ đa phương tiện.","type":"invalid_request_error","code":"model_not_multimodal"}}""")
            }
            return null
        }

        /** POST /v1/audio/transcriptions - OpenAI Whisper-compatible */
        private fun handleAudioTranscription(session: IHTTPSession, translate: Boolean = false, taskId: String? = null): Response {
            requireMultimodal(taskId)?.let { return it }

            val uploaded = readUploadedFile(session, "file", "audio")
            if (uploaded == null) {
                taskId?.let { failTask(it, "missing file field", 400) }
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"error":{"message":"Thiếu trường 'file' trong multipart/form-data","type":"invalid_request_error"}}""")
            }
            val (audioBytes, fileName) = uploaded

            val userPrompt = getFormField(session, "prompt") ?: ""
            val language = getFormField(session, "language") ?: ""
            val responseFormat = getFormField(session, "response_format") ?: "json"

            // Build instruction prompt for Gemma 4
            val instruction = when {
                translate -> "Transcribe the following audio and translate the result to English. Output only the transcription text, no preamble."
                language.isNotBlank() -> "Transcribe the following audio in $language. Output only the transcription text, no preamble."
                else -> "Transcribe the following audio. Output only the transcription text, no preamble."
            }
            val prompt = if (userPrompt.isNotBlank()) "$instruction\n\nContext: $userPrompt" else instruction

            _requestCount.update { it + 1 }
            addLog("音频转录: $fileName (${audioBytes.size / 1024} KB)${if (translate) " → EN" else ""}")
            taskId?.let { tid ->
                updateTaskPreview(tid, "$fileName (${audioBytes.size / 1024}KB)${if (translate) " → EN" else ""}")
                setTaskRequestBody(tid, "file=$fileName (${audioBytes.size} bytes)", mapOf(
                    "file" to fileName,
                    "size_kb" to "${audioBytes.size / 1024}",
                    "language" to language.ifBlank { "auto" },
                    "translate" to "$translate",
                    "response_format" to responseFormat,
                    "prompt" to userPrompt
                ))
                addTaskEvent(tid, "Audio loaded: ${audioBytes.size / 1024}KB")
            }

            return try {
                val startTime = System.currentTimeMillis()
                val text = engine.generateMultimodal(prompt, emptyList(), listOf(audioBytes))
                val elapsed = System.currentTimeMillis() - startTime
                addLog("转录完成 ${elapsed}ms (${text.length} 字符)")
                taskId?.let { completeTask(it, text.length.toLong(), text.take(100), text, 200) }

                when (responseFormat) {
                    "text" -> newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", text)
                    "verbose_json" -> newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                        JSONObject().apply {
                            put("text", text)
                            put("task", if (translate) "translate" else "transcribe")
                            put("language", language.ifBlank { "auto" })
                            put("duration", 0) // not computed
                            put("processing_time_ms", elapsed)
                        }.toString())
                    else -> newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                        """{"text":${JSONObject.quote(text)}}""")
                }
            } catch (e: Exception) {
                addLog("转录错误: ${e.message}")
                taskId?.let { failTask(it, e.message ?: "transcription failed") }
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_JSON,
                    """{"error":{"message":"${e.message?.replace("\"","'")}","type":"server_error"}}""")
            }
        }

        /** POST /v1/images/describe - image analysis */
        private fun handleImageDescribe(session: IHTTPSession, taskId: String? = null): Response {
            requireMultimodal(taskId)?.let { return it }

            val uploaded = readUploadedFile(session, "file", "image")
            if (uploaded == null) {
                taskId?.let { failTask(it, "missing file field", 400) }
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"error":{"message":"Thiếu trường 'file' hoặc 'image' trong multipart/form-data","type":"invalid_request_error"}}""")
            }
            val (imageBytes, fileName) = uploaded

            val userPrompt = getFormField(session, "prompt")
            val prompt = if (!userPrompt.isNullOrBlank()) userPrompt
                else "Mô tả chi tiết ảnh này. Liệt kê các đối tượng, con người, bối cảnh, màu sắc, và hành động nếu có."

            _requestCount.update { it + 1 }
            addLog("图像描述: $fileName (${imageBytes.size / 1024} KB)")
            taskId?.let { tid ->
                updateTaskPreview(tid, "$fileName (${imageBytes.size / 1024}KB)")
                setTaskRequestBody(tid, "file=$fileName (${imageBytes.size} bytes)\nprompt=$prompt", mapOf(
                    "file" to fileName,
                    "size_kb" to "${imageBytes.size / 1024}",
                    "prompt" to prompt
                ))
                addTaskEvent(tid, "Image loaded: ${imageBytes.size / 1024}KB")
            }

            return try {
                val startTime = System.currentTimeMillis()
                val text = engine.generateMultimodal(prompt, listOf(imageBytes), emptyList())
                val elapsed = System.currentTimeMillis() - startTime
                addLog("描述完成 ${elapsed}ms (${text.length} 字符)")
                taskId?.let { completeTask(it, text.length.toLong(), text.take(100), text, 200) }

                newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                    JSONObject().apply {
                        put("description", text)
                        put("model", engine.loadedModelName.value ?: "unknown")
                        put("processing_time_ms", elapsed)
                    }.toString())
            } catch (e: Exception) {
                addLog("图像描述错误: ${e.message}")
                taskId?.let { failTask(it, e.message ?: "describe failed") }
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_JSON,
                    """{"error":{"message":"${e.message?.replace("\"","'")}","type":"server_error"}}""")
            }
        }

        /** POST /v1/video/analyze - extract frames + analyze */
        private fun handleVideoAnalyze(session: IHTTPSession, taskId: String? = null): Response {
            requireMultimodal(taskId)?.let { return it }

            // Ensure UTF-8 encoding by modifying Content-Type before parseBody
            val originalContentType = session.headers["content-type"]
            if (originalContentType != null) {
                val ct = NanoHTTPD.ContentType(originalContentType).tryUTF8()
                session.headers["content-type"] = ct.contentTypeHeader
            }

            val files = HashMap<String, String>()
            try { session.parseBody(files) } catch (_: Exception) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"error":{"message":"Không phân tích được body","type":"invalid_request_error"}}""")
            }
            val tempPath = files["file"] ?: files["video"]
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"error":{"message":"Thiếu trường 'file' hoặc 'video' trong multipart/form-data","type":"invalid_request_error"}}""")

            val fileName = session.parameters["file"]?.firstOrNull()
                ?: session.parameters["video"]?.firstOrNull() ?: "video"
            val frameCount = getFormField(session, "frame_count")?.toIntOrNull()?.coerceIn(1, 16) ?: 4
            val userPrompt = getFormField(session, "prompt")
            val prompt = if (!userPrompt.isNullOrBlank()) userPrompt
                else "Mô tả nội dung video này dựa trên các khung hình đã trích xuất. Tóm tắt sự việc, đối tượng và diễn biến."

            _requestCount.update { it + 1 }
            addLog("视频分析: $fileName (frames=$frameCount)")
            taskId?.let { tid ->
                updateTaskPreview(tid, "$fileName (frames=$frameCount)")
                setTaskRequestBody(tid, "file=$fileName\nframe_count=$frameCount\nprompt=$prompt", mapOf(
                    "file" to fileName,
                    "frame_count" to "$frameCount",
                    "prompt" to prompt
                ))
                addTaskEvent(tid, "Video nhận được, trích $frameCount khung hình")
            }

            // Extract frames with MediaMetadataRetriever
            val frames = mutableListOf<ByteArray>()
            val retriever = android.media.MediaMetadataRetriever()
            var durationMs = 0L
            try {
                retriever.setDataSource(tempPath)
                durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                if (durationMs <= 0) {
                    // Fallback: sample at fixed intervals
                    for (i in 0 until frameCount) {
                        val bmp = retriever.getFrameAtTime(i * 1_000_000L,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        bmp?.let { frames.add(bitmapToJpeg(it, 85)) }
                    }
                } else {
                    val step = (durationMs * 1000L) / frameCount
                    for (i in 0 until frameCount) {
                        val timeUs = step * i + step / 2
                        val bmp = retriever.getFrameAtTime(timeUs,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        bmp?.let { frames.add(bitmapToJpeg(it, 85)) }
                    }
                }
            } catch (e: Exception) {
                addLog("提取帧错误: ${e.message}")
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_JSON,
                    """{"error":{"message":"Không thể đọc video: ${e.message?.replace("\"","'")}","type":"server_error"}}""")
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }

            if (frames.isEmpty()) {
                taskId?.let { failTask(it, "no frames extracted", 400) }
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"error":{"message":"Không trích xuất được khung hình nào từ video","type":"invalid_request_error"}}""")
            }
            taskId?.let { addTaskEvent(it, "Đã trích ${frames.size} khung hình (${durationMs}ms)") }

            return try {
                val startTime = System.currentTimeMillis()
                val text = engine.generateMultimodal(prompt, frames, emptyList())
                val elapsed = System.currentTimeMillis() - startTime
                addLog("视频分析完成 ${elapsed}ms (${frames.size} frames, ${text.length} 字符)")
                taskId?.let { completeTask(it, text.length.toLong(), text.take(100), text, 200) }

                newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                    JSONObject().apply {
                        put("description", text)
                        put("frames_analyzed", frames.size)
                        put("duration_ms", durationMs)
                        put("model", engine.loadedModelName.value ?: "unknown")
                        put("processing_time_ms", elapsed)
                    }.toString())
            } catch (e: Exception) {
                addLog("视频分析错误: ${e.message}")
                taskId?.let { failTask(it, e.message ?: "video analyze failed") }
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_JSON,
                    """{"error":{"message":"${e.message?.replace("\"","'")}","type":"server_error"}}""")
            }
        }

        private fun bitmapToJpeg(bitmap: android.graphics.Bitmap, quality: Int): ByteArray {
            val baos = java.io.ByteArrayOutputStream()
            // Resize if too large to avoid OOM and slow inference
            val maxDim = 1024
            val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val ratio = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                val w = (bitmap.width * ratio).toInt()
                val h = (bitmap.height * ratio).toInt()
                android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
            } else bitmap
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, baos)
            if (scaled !== bitmap) scaled.recycle()
            return baos.toByteArray()
        }

        // ── Base64 helpers ──

        private fun decodeBase64(data: String): ByteArray? {
            return try {
                android.util.Base64.decode(data, android.util.Base64.DEFAULT)
            } catch (_: Exception) { null }
        }

        /** Decode data URL (data:image/jpeg;base64,XXXXX) or bare base64 */
        private fun decodeBase64DataUrl(url: String): ByteArray? {
            if (url.isBlank()) return null
            val base64 = if (url.startsWith("data:")) {
                val comma = url.indexOf(',')
                if (comma == -1) return null
                url.substring(comma + 1)
            } else url
            return decodeBase64(base64)
        }

        // ── Multimodal SSE streaming ──

        private fun handleOpenAIChatStreamingMultimodal(
            messages: List<LlmEngine.ChatMessage>,
            images: List<ByteArray>,
            audios: List<ByteArray>,
            temperature: Float,
            topK: Int,
            topP: Float,
            modelName: String,
            taskId: String? = null
        ): Response {
            val chatId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
            val created = System.currentTimeMillis() / 1000
            val pipedIn = PipedInputStream(16384)
            val pipedOut = PipedOutputStream(pipedIn)
            val promptText = messages.lastOrNull()?.content ?: ""

            Thread({
                try {
                    val writer = pipedOut.bufferedWriter()

                    // Initial role chunk
                    val roleChunk = JSONObject().apply {
                        put("id", chatId); put("object", "chat.completion.chunk")
                        put("created", created); put("model", modelName)
                        put("choices", JSONArray().put(JSONObject().apply {
                            put("index", 0)
                            put("delta", JSONObject().put("role", "assistant"))
                            put("finish_reason", JSONObject.NULL)
                        }))
                    }
                    writer.write("data: $roleChunk\n\n"); writer.flush()

                    val startTime = System.currentTimeMillis()
                    taskId?.let { addTaskEvent(it, "Bắt đầu stream đa phương tiện") }
                    val token = taskId?.let { registerCancelToken(it) }
                    engine.generateStreamingMultimodal(
                        promptText, images, audios, temperature, topK, topP, cancelToken = token,
                        onChunk = { chunk ->
                            try {
                                taskId?.let { incrementTaskChunks(it) }
                                val chunkJson = JSONObject().apply {
                                    put("id", chatId); put("object", "chat.completion.chunk")
                                    put("created", created); put("model", modelName)
                                    put("choices", JSONArray().put(JSONObject().apply {
                                        put("index", 0)
                                        put("delta", JSONObject().put("content", chunk))
                                        put("finish_reason", JSONObject.NULL)
                                    }))
                                }
                                writer.write("data: $chunkJson\n\n"); writer.flush()
                            } catch (_: Exception) {}
                        },
                        onComplete = { fullText ->
                            try {
                                val elapsed = System.currentTimeMillis() - startTime
                                addLog("多模态Stream完成 ${elapsed}ms (${fullText.length} 字符)")
                                taskId?.let { completeTask(it, fullText.length.toLong(), fullText.take(100), fullText, 200) }
                                val doneChunk = JSONObject().apply {
                                    put("id", chatId); put("object", "chat.completion.chunk")
                                    put("created", created); put("model", modelName)
                                    put("choices", JSONArray().put(JSONObject().apply {
                                        put("index", 0); put("delta", JSONObject())
                                        put("finish_reason", "stop")
                                    }))
                                }
                                writer.write("data: $doneChunk\n\n")
                                writer.write("data: [DONE]\n\n")
                                writer.flush(); writer.close()
                            } catch (_: Exception) {}
                        },
                        onError = { e ->
                            try {
                                addLog("多模态stream错误: ${e.message}")
                                taskId?.let { failTask(it, e.message ?: "stream error") }
                                val errorJson = JSONObject().apply {
                                    put("error", JSONObject().apply {
                                        put("message", e.message); put("type", "server_error")
                                    })
                                }
                                writer.write("data: $errorJson\n\n")
                                writer.write("data: [DONE]\n\n")
                                writer.flush(); writer.close()
                            } catch (_: Exception) {}
                        }
                    )
                } catch (_: Exception) {
                    try { pipedOut.close() } catch (_: Exception) {}
                }
            }, "sse-mm-$chatId").start()

            val response = newChunkedResponse(Response.Status.OK, "text/event-stream", pipedIn)
            response.addHeader("Cache-Control", "no-cache")
            response.addHeader("Connection", "keep-alive")
            response.addHeader("X-Accel-Buffering", "no")
            return response
        }

        companion object {
            private const val MIME_JSON = "application/json"
        }
    }
}
