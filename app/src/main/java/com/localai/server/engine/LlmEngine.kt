package com.localai.server.engine

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Dual-engine LLM inference:
 * - MediaPipe LlmInference for .task models (Gemma 2/3, Qwen, DeepSeek, etc.)
 * - LiteRT GenAI for .litertlm models (Gemma 4)
 *
 * Thread-safe: uses Semaphore to ensure only one inference runs at a time.
 */
class LlmEngine(private val context: Context) {

    /** Extract text content from a LiteRT Message */
    private fun Message.textContent(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    // ── Engines ──
    private var mediaPipeEngine: LlmInference? = null
    private var liteRtEngine: Engine? = null

    private var loadedModelPath: String? = null
    private var loadedFormat: ModelFormat = ModelFormat.NONE

    private enum class ModelFormat { NONE, TASK, LITERTLM }

    // ── State ──
    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status

    private val _loadedModelName = MutableStateFlow<String?>(null)
    val loadedModelName: StateFlow<String?> = _loadedModelName

    // ── Settings ──
    var maxTokens: Int = 8192
    var defaultTemperature: Float = 0.7f
    var defaultTopK: Int = 64
    var defaultTopP: Float = 0.95f

    // ── Performance metrics ──
    private val _lastProcessingTimeMs = MutableStateFlow(0L)
    val lastProcessingTimeMs: StateFlow<Long> = _lastProcessingTimeMs

    private val _lastResponseLength = MutableStateFlow(0)
    val lastResponseLength: StateFlow<Int> = _lastResponseLength

    private val _tokensPerSecond = MutableStateFlow(0f)
    val tokensPerSecond: StateFlow<Float> = _tokensPerSecond

    private val totalInferenceCount = AtomicLong(0)
    fun getInferenceCount(): Long = totalInferenceCount.get()

    // ── Concurrency: only one inference at a time ──
    private val inferenceLock = Semaphore(1, true) // fair ordering
    private val _queueDepth = MutableStateFlow(0)
    val queueDepth: StateFlow<Int> = _queueDepth

    val isLoaded: Boolean get() = mediaPipeEngine != null || liteRtEngine != null

    /** True if loaded model potentially supports image/audio input (.litertlm format). */
    val isMultimodal: Boolean get() = loadedFormat == ModelFormat.LITERTLM

    enum class Status { IDLE, LOADING, READY, ERROR }

    // ── Chat message for multi-turn ──
    data class ChatMessage(val role: String, val content: String)

    /** Cancellation flag - check from generation callbacks to abort early */
    class CancellationToken {
        @Volatile var isCancelled: Boolean = false
            private set
        fun cancel() { isCancelled = true }
    }

    /**
     * Detect chat template based on loaded model name
     */
    private enum class ChatTemplate { GEMMA, CHATML }

    private fun detectTemplate(): ChatTemplate {
        val name = (_loadedModelName.value ?: loadedModelPath ?: "").lowercase()
        return when {
            name.contains("gemma") -> ChatTemplate.GEMMA
            else -> ChatTemplate.CHATML // Qwen, DeepSeek, TinyLlama, SmolLM
        }
    }

    /**
     * Format a list of ChatMessages into a prompt string using the appropriate chat template.
     */
    fun formatPrompt(messages: List<ChatMessage>): String {
        if (messages.isEmpty()) return ""
        val template = detectTemplate()
        val sb = StringBuilder()

        when (template) {
            ChatTemplate.GEMMA -> {
                for (msg in messages) {
                    when (msg.role) {
                        "system" -> sb.append("<start_of_turn>user\nSystem: ${msg.content}<end_of_turn>\n")
                        "user" -> sb.append("<start_of_turn>user\n${msg.content}<end_of_turn>\n")
                        "assistant" -> sb.append("<start_of_turn>model\n${msg.content}<end_of_turn>\n")
                    }
                }
                sb.append("<start_of_turn>model\n")
            }
            ChatTemplate.CHATML -> {
                for (msg in messages) {
                    sb.append("<|im_start|>${msg.role}\n${msg.content}<|im_end|>\n")
                }
                sb.append("<|im_start|>assistant\n")
            }
        }
        return sb.toString()
    }

    // ── Model loading ──

    suspend fun loadModel(path: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (isLoaded && loadedModelPath == path) {
                return@withContext Result.success(true)
            }
            _status.value = Status.LOADING
            unload()

            val file = File(path)
            if (!file.exists()) {
                _status.value = Status.ERROR
                return@withContext Result.failure(Exception("Không tìm thấy tệp: $path"))
            }

            val format = when (file.extension) {
                "litertlm" -> ModelFormat.LITERTLM
                else -> ModelFormat.TASK
            }

            val sizeMb = file.length() / (1024 * 1024)
            Log.d(TAG, "Loading model: $path (${sizeMb}MB, format=$format)")

            when (format) {
                ModelFormat.LITERTLM -> {
                    val config = EngineConfig(
                        modelPath = path,
                        backend = Backend.CPU(),         // CPU for text (avoid GPU contention with UI)
                        visionBackend = Backend.GPU(),   // GPU only for vision/image tasks
                        audioBackend = Backend.CPU(),
                        maxNumTokens = maxTokens,
                        cacheDir = context.cacheDir.absolutePath
                    )
                    val engine = Engine(config)
                    engine.initialize()
                    liteRtEngine = engine
                }
                ModelFormat.TASK -> {
                    val options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(path)
                        .setMaxTokens(maxTokens)
                        .build()
                    mediaPipeEngine = LlmInference.createFromOptions(context, options)
                }
                ModelFormat.NONE -> {}
            }

            loadedModelPath = path
            loadedFormat = format
            _loadedModelName.value = MODEL_CATALOG.find { path.endsWith(it.fileName) }?.displayName
                ?: file.nameWithoutExtension
            _status.value = Status.READY
            Log.d(TAG, "Model loaded: ${_loadedModelName.value} ($format)")
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            unload()
            _status.value = Status.ERROR
            Result.failure(e)
        }
    }

    fun unload() {
        try { mediaPipeEngine?.close() } catch (_: Exception) {}
        try { liteRtEngine?.close() } catch (_: Exception) {}
        mediaPipeEngine = null
        liteRtEngine = null
        loadedModelPath = null
        loadedFormat = ModelFormat.NONE
        _loadedModelName.value = null
        _status.value = Status.IDLE
    }

    // ── Inference (thread-safe, blocking) ──

    /**
     * Generate response from a raw prompt string.
     * Thread-safe: queues behind any running inference with 60s timeout.
     */
    fun generateResponse(
        prompt: String,
        temperature: Float = defaultTemperature,
        topK: Int = defaultTopK,
        topP: Float = defaultTopP
    ): String {
        _queueDepth.update { it + 1 }
        if (!inferenceLock.tryAcquire(60, TimeUnit.SECONDS)) {
            _queueDepth.update { it - 1 }
            throw Exception("Máy chủ đang bận - hết thời gian chờ trong hàng đợi (60s)")
        }
        try {
            _queueDepth.update { it - 1 }
            val startTime = System.currentTimeMillis()
            val result = doGenerate(prompt, temperature, topK, topP)
            updateMetrics(startTime, result.length)
            return result
        } finally {
            inferenceLock.release()
        }
    }

    /**
     * Generate response from a list of ChatMessages (OpenAI-style).
     */
    fun generateFromMessages(
        messages: List<ChatMessage>,
        temperature: Float = defaultTemperature,
        topK: Int = defaultTopK,
        topP: Float = defaultTopP
    ): String {
        val prompt = formatPrompt(messages)
        return generateResponse(prompt, temperature, topK, topP)
    }

    /**
     * Core inference logic (NOT thread-safe, must be called under inferenceLock).
     */
    private fun doGenerate(
        prompt: String,
        temperature: Float,
        topK: Int,
        topP: Float
    ): String = when (loadedFormat) {
        ModelFormat.TASK -> {
            val engine = mediaPipeEngine ?: throw Exception("Chưa tải mô hình")
            val opts = LlmInferenceSessionOptions.builder()
                .setTemperature(temperature).setTopK(topK).setTopP(topP).build()
            val session = LlmInferenceSession.createFromOptions(engine, opts)
            try {
                session.addQueryChunk(prompt)
                session.generateResponse()
            } finally { session.close() }
        }
        ModelFormat.LITERTLM -> {
            val engine = liteRtEngine ?: throw Exception("Chưa tải mô hình")
            val config = ConversationConfig(
                samplerConfig = SamplerConfig(topK = topK, topP = topP.toDouble(), temperature = temperature.toDouble())
            )
            val conversation = engine.createConversation(config)
            try {
                val result = StringBuilder()
                try {
                    runBlocking {
                        conversation.sendMessageAsync(prompt)
                            .catch { e ->
                                val sync = conversation.sendMessage(prompt)
                                result.append(sync.textContent())
                            }
                            .collect { msg -> result.append(msg.textContent()) }
                    }
                } catch (_: Exception) {
                    if (result.isEmpty()) {
                        result.append(conversation.sendMessage(prompt).textContent())
                    }
                }
                result.toString()
            } finally { conversation.close() }
        }
        ModelFormat.NONE -> throw Exception("Chưa tải mô hình")
    }

    // ── Streaming inference (thread-safe) ──

    /**
     * Generate response with streaming chunks via callback.
     * onChunk receives incremental text. onComplete receives the full text.
     * Thread-safe: queues behind any running inference.
     */
    fun generateStreaming(
        prompt: String,
        temperature: Float = defaultTemperature,
        topK: Int = defaultTopK,
        topP: Float = defaultTopP,
        cancelToken: CancellationToken? = null,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        _queueDepth.update { it + 1 }
        if (!inferenceLock.tryAcquire(60, TimeUnit.SECONDS)) {
            _queueDepth.update { it - 1 }
            onError(Exception("Máy chủ đang bận - hết thời gian chờ trong hàng đợi (60s)"))
            return
        }
        _queueDepth.update { it - 1 }
        if (cancelToken?.isCancelled == true) {
            inferenceLock.release()
            onError(Exception("Đã hủy"))
            return
        }
        val startTime = System.currentTimeMillis()
        val wrappedComplete: (String) -> Unit = { text ->
            updateMetrics(startTime, text.length)
            onComplete(text)
        }
        try {
            when (loadedFormat) {
                ModelFormat.TASK -> streamMediaPipe(prompt, temperature, topK, topP, cancelToken, onChunk, wrappedComplete)
                ModelFormat.LITERTLM -> streamLiteRT(prompt, temperature, topK, topP, cancelToken, onChunk, wrappedComplete)
                ModelFormat.NONE -> onError(Exception("Chưa tải mô hình"))
            }
        } catch (e: Exception) {
            onError(e)
        } finally {
            inferenceLock.release()
        }
    }

    private fun updateMetrics(startTime: Long, responseLength: Int) {
        val elapsed = System.currentTimeMillis() - startTime
        _lastProcessingTimeMs.value = elapsed
        _lastResponseLength.value = responseLength
        val tokens = responseLength / 4f
        _tokensPerSecond.value = if (elapsed > 0) tokens / (elapsed / 1000f) else 0f
        totalInferenceCount.incrementAndGet()
    }

    /**
     * Streaming from ChatMessages (OpenAI-style).
     */
    fun generateStreamingFromMessages(
        messages: List<ChatMessage>,
        temperature: Float = defaultTemperature,
        topK: Int = defaultTopK,
        topP: Float = defaultTopP,
        cancelToken: CancellationToken? = null,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val prompt = formatPrompt(messages)
        generateStreaming(prompt, temperature, topK, topP, cancelToken, onChunk, onComplete, onError)
    }

    // ── Multimodal inference (image + audio input) ──

    /**
     * Generate streaming response with image/audio attachments.
     * ONLY works with .litertlm models (Gemma 4, etc).
     * Throws if current model is not multimodal.
     */
    fun generateStreamingMultimodal(
        prompt: String,
        images: List<ByteArray> = emptyList(),
        audios: List<ByteArray> = emptyList(),
        temperature: Float = defaultTemperature,
        topK: Int = defaultTopK,
        topP: Float = defaultTopP,
        cancelToken: CancellationToken? = null,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (!isMultimodal) {
            onError(Exception("Mô hình hiện tại không hỗ trợ đa phương tiện. Hãy tải Gemma 4 (.litertlm)."))
            return
        }
        _queueDepth.update { it + 1 }
        if (!inferenceLock.tryAcquire(60, TimeUnit.SECONDS)) {
            _queueDepth.update { it - 1 }
            onError(Exception("Máy chủ đang bận - hết thời gian chờ trong hàng đợi (60s)"))
            return
        }
        _queueDepth.update { it - 1 }
        if (cancelToken?.isCancelled == true) {
            inferenceLock.release()
            onError(Exception("Đã hủy"))
            return
        }
        val startTime = System.currentTimeMillis()
        try {
            val engine = liteRtEngine ?: throw Exception("Chưa tải mô hình")
            val config = ConversationConfig(
                samplerConfig = SamplerConfig(topK = topK, topP = topP.toDouble(), temperature = temperature.toDouble())
            )
            val conversation = engine.createConversation(config)

            // Build Contents: images/audios first, then prompt text (Edge Gallery order)
            val contentList = mutableListOf<Content>()
            images.forEach { contentList.add(Content.ImageBytes(it)) }
            audios.forEach { contentList.add(Content.AudioBytes(it)) }
            contentList.add(Content.Text(prompt))

            val result = StringBuilder()
            var lastPartial = ""
            val latch = java.util.concurrent.CountDownLatch(1)
            var callbackError: Throwable? = null

            conversation.sendMessageAsync(
                Contents.of(contentList),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (cancelToken?.isCancelled == true) return
                        val full = message.toString()
                        val chunk = if (full.startsWith(lastPartial)) full.substring(lastPartial.length) else full
                        if (chunk.isNotEmpty()) {
                            result.append(chunk)
                            onChunk(chunk)
                        }
                        lastPartial = full
                    }
                    override fun onDone() { latch.countDown() }
                    override fun onError(throwable: Throwable) {
                        callbackError = throwable
                        latch.countDown()
                    }
                }
            )

            latch.await(5, TimeUnit.MINUTES)
            try { conversation.close() } catch (_: Exception) {}

            callbackError?.let { throw it }
            updateMetrics(startTime, result.length)
            onComplete(result.toString())
        } catch (e: Exception) {
            onError(e)
        } finally {
            inferenceLock.release()
        }
    }

    /** Non-streaming multimodal inference. */
    fun generateMultimodal(
        prompt: String,
        images: List<ByteArray> = emptyList(),
        audios: List<ByteArray> = emptyList(),
        temperature: Float = defaultTemperature,
        topK: Int = defaultTopK,
        topP: Float = defaultTopP
    ): String {
        var result = ""
        var error: Exception? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        generateStreamingMultimodal(
            prompt, images, audios, temperature, topK, topP,
            onChunk = { },
            onComplete = { r -> result = r; latch.countDown() },
            onError = { e -> error = e; latch.countDown() }
        )
        latch.await(5, TimeUnit.MINUTES)
        error?.let { throw it }
        return result
    }

    private fun streamMediaPipe(
        prompt: String,
        temperature: Float,
        topK: Int,
        topP: Float,
        cancelToken: CancellationToken?,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit
    ) {
        val engine = mediaPipeEngine ?: throw Exception("Chưa tải mô hình")
        val opts = LlmInferenceSessionOptions.builder()
            .setTemperature(temperature).setTopK(topK).setTopP(topP).build()
        val session = LlmInferenceSession.createFromOptions(engine, opts)
        var completed = false
        try {
            session.addQueryChunk(prompt)
            try {
                var lastPartial = ""
                session.generateResponseAsync { partial, isDone ->
                    if (cancelToken?.isCancelled == true) {
                        if (!completed) {
                            completed = true
                            onComplete(lastPartial + "\n[Đã hủy]")
                        }
                        return@generateResponseAsync
                    }
                    val chunk = partial.substring(lastPartial.length)
                    if (chunk.isNotEmpty()) onChunk(chunk)
                    lastPartial = partial
                    if (isDone && !completed) {
                        completed = true
                        onComplete(lastPartial)
                    }
                }
            } catch (_: Exception) {
                if (!completed) {
                    val result = session.generateResponse()
                    onChunk(result)
                    onComplete(result)
                }
            }
        } finally {
            session.close()
        }
    }

    private fun streamLiteRT(
        prompt: String,
        temperature: Float,
        topK: Int,
        topP: Float,
        cancelToken: CancellationToken?,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit
    ) {
        val engine = liteRtEngine ?: throw Exception("Chưa tải mô hình")
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(topK = topK, topP = topP.toDouble(), temperature = temperature.toDouble())
        )
        val conversation = engine.createConversation(config)
        try {
            val result = StringBuilder()
            var cancelled = false
            try {
                runBlocking {
                    conversation.sendMessageAsync(prompt)
                        .catch { e ->
                            val sync = conversation.sendMessage(prompt)
                            val text = sync.textContent()
                            result.append(text)
                            onChunk(text)
                        }
                        .collect { msg ->
                            if (cancelToken?.isCancelled == true) {
                                cancelled = true
                                result.append("\n[Đã hủy]")
                                throw kotlinx.coroutines.CancellationException("User cancelled")
                            }
                            val text = msg.textContent()
                            result.append(text)
                            onChunk(text)
                        }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // expected on user cancel
            } catch (_: Exception) {
                if (result.isEmpty()) {
                    val sync = conversation.sendMessage(prompt)
                    val text = sync.textContent()
                    result.append(text)
                    onChunk(text)
                }
            }
            onComplete(result.toString())
        } finally {
            conversation.close()
        }
    }

    // ── File management ──

    fun getModelsDir(): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private val modelExtensions = setOf("task", "litertlm", "bin")

    fun findLocalModels(): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()
        val seen = mutableSetOf<String>()
        val dirs = listOf(
            getModelsDir(),
            File("/storage/emulated/0/Download"),
            File("/storage/emulated/0/Documents"),
        )
        for (dir in dirs) {
            if (!dir.exists()) continue
            dir.listFiles()?.filter { f ->
                f.isFile && f.extension in modelExtensions && f.length() > 1024 * 1024
                    && !f.name.endsWith(".downloading")
            }?.forEach { file ->
                if (seen.add(file.absolutePath)) {
                    val catalog = MODEL_CATALOG.find { it.fileName == file.name }
                    models.add(ModelInfo(
                        path = file.absolutePath,
                        name = catalog?.displayName ?: file.nameWithoutExtension,
                        sizeMb = file.length() / (1024 * 1024),
                        description = catalog?.description ?: ""
                    ))
                }
            }
        }
        return models
    }

    fun getDownloadedCatalogIds(): Set<String> {
        val dir = getModelsDir()
        if (!dir.exists()) return emptySet()
        val names = dir.listFiles()?.filter { it.length() > 1024 * 1024 }?.map { it.name }?.toSet() ?: emptySet()
        return MODEL_CATALOG.filter { it.fileName in names }.map { it.id }.toSet()
    }

    @Volatile private var cancelDownload = false
    fun cancelDownload() { cancelDownload = true }

    suspend fun downloadModel(
        model: DownloadableModel,
        onProgress: (Long, Long, Float) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val target = File(getModelsDir(), model.fileName)
            val temp = File(getModelsDir(), "${model.fileName}.downloading")

            if (target.exists() && target.length() > 1024 * 1024) {
                return@withContext Result.success(target.absolutePath)
            }

            cancelDownload = false
            var resume = if (temp.exists()) temp.length() else 0L

            val conn = URL(model.downloadUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "LocalAIServer/1.0")
            if (resume > 0) conn.setRequestProperty("Range", "bytes=$resume-")

            val code = conn.responseCode
            if (code != 200 && code != 206) {
                conn.disconnect()
                return@withContext Result.failure(Exception("HTTP $code"))
            }

            val total = if (code == 206) resume + conn.contentLengthLong else conn.contentLengthLong
            val input = conn.inputStream
            val output = FileOutputStream(temp, resume > 0)
            val buf = ByteArray(65536)
            var read: Int
            var downloaded = resume
            var lastUpdate = 0L

            try {
                while (input.read(buf).also { read = it } != -1) {
                    if (cancelDownload) return@withContext Result.failure(Exception("Đã hủy"))
                    output.write(buf, 0, read)
                    downloaded += read
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 200) {
                        lastUpdate = now
                        onProgress(downloaded, total, if (total > 0) downloaded.toFloat() / total else 0f)
                    }
                }
                onProgress(downloaded, total, 1f)
            } finally {
                output.close(); input.close(); conn.disconnect()
            }

            if (temp.exists()) {
                target.delete()
                temp.renameTo(target)
            }
            Result.success(target.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun deleteModel(id: String): Boolean {
        val model = MODEL_CATALOG.find { it.id == id } ?: return false
        File(getModelsDir(), model.fileName).delete()
        File(getModelsDir(), "${model.fileName}.downloading").delete()
        return true
    }

    data class ModelInfo(val path: String, val name: String, val sizeMb: Long, val description: String)

    data class DownloadableModel(
        val id: String, val displayName: String, val fileName: String,
        val downloadUrl: String, val sizeMb: Long, val ram: String, val description: String
    )

    companion object {
        private const val TAG = "LlmEngine"

        val MODEL_CATALOG = listOf(
            // ── Gemma 4 (.litertlm - LiteRT GenAI) ──
            DownloadableModel("gemma4-e2b", "Gemma 4 E2B", "gemma-4-E2B-it.litertlm",
                "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                2464, "~3 GB", "Gemma 4 mới nhất, 2B tham số, đa phương tiện"),
            DownloadableModel("gemma4-e4b", "Gemma 4 E4B", "gemma-4-E4B-it.litertlm",
                "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
                3486, "~4.5 GB", "Gemma 4 mạnh nhất, 4B tham số, đa phương tiện"),
            // ── Gemma 3 (.task - MediaPipe, mirrored) ──
            DownloadableModel("gemma3-270m-q8", "Gemma 3 270M", "gemma3-270m-it-q8.task",
                "https://huggingface.co/omermalix66/gemma3-270m-it-q8.task/resolve/main/gemma3-270m-it-q8.task",
                290, "~0.5 GB", "Siêu nhẹ, tốc độ cao"),
            DownloadableModel("gemma3-1b-int4", "Gemma 3 1B (Int4)", "gemma3-1b-it-int4.task",
                "https://huggingface.co/AfiOne/gemma3-1b-it-int4.task/resolve/main/gemma3-1b-it-int4.task",
                555, "~1 GB", "Nhẹ, cân bằng chất lượng/kích thước tốt"),
            // ── Other models (.task) ──
            DownloadableModel("qwen25-0.5b", "Qwen 2.5 0.5B", "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
                "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
                521, "~0.8 GB", "Đa ngôn ngữ, Apache 2.0"),
            DownloadableModel("qwen25-1.5b", "Qwen 2.5 1.5B", "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
                "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
                1524, "~2 GB", "Đa ngôn ngữ xuất sắc"),
            DownloadableModel("deepseek-r1-1.5b", "DeepSeek R1 1.5B", "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv1280.task",
                "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv1280.task",
                1775, "~2 GB", "Lý luận logic tốt"),
            DownloadableModel("smollm-135m", "SmolLM 135M", "SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task",
                "https://huggingface.co/litert-community/SmolLM-135M-Instruct/resolve/main/SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task",
                159, "~0.3 GB", "Siêu nhỏ 159MB, tải nhanh"),
            DownloadableModel("tinyllama-1.1b", "TinyLlama 1.1B", "TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv1280.task",
                "https://huggingface.co/litert-community/TinyLlama-1.1B-Chat-v1.0/resolve/main/TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv1280.task",
                1095, "~1.5 GB", "Nhẹ, chat tốt"),
            DownloadableModel("gemma2-2b-int8", "Gemma 2 2B (Int8)", "gemma2-2b-it-cpu-int8.task",
                "https://huggingface.co/CarlosJefte/Gemma-2-2b-mediapipe/resolve/main/gemma2-2b-it-cpu-int8.task",
                2588, "~3 GB", "Ổn định, đã kiểm chứng"),
        )
    }
}
