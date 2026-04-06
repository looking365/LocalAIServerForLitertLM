package com.localai.server.ui

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localai.server.LocalAIApp
import com.localai.server.engine.LlmEngine
import com.localai.server.service.AiServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ── Responsive layout ──

enum class LayoutSize { COMPACT, MEDIUM, EXPANDED }

@Composable
fun rememberLayoutSize(): LayoutSize {
    val w = LocalConfiguration.current.screenWidthDp
    // Z Fold 7 inner screen ~700-800dp, tablet portrait ~800dp+, tablet landscape 1000dp+
    return when {
        w >= 700 -> LayoutSize.EXPANDED   // tablets, Z Fold unfolded, phones landscape
        w >= 600 -> LayoutSize.MEDIUM     // large phones portrait
        else -> LayoutSize.COMPACT        // phones portrait
    }
}

// ── Data Models ──

data class UiChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ServerUiState(
    val localModels: List<LlmEngine.ModelInfo> = emptyList(),
    val downloadedIds: Set<String> = emptySet(),
    val selectedModelPath: String = "",
    val isModelLoading: Boolean = false,
    val downloadingId: String? = null,
    val downloadProgress: Float = 0f,
    val downloadedMb: Long = 0,
    val downloadTotalMb: Long = 0,
    val message: String? = null,
    // Chat
    val chatMessages: List<UiChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val streamingContent: String = "",
    // Settings - inference
    val maxTokens: Int = 1024,
    val temperature: Float = 0.7f,
    val topK: Int = 64,
    val topP: Float = 0.95f,
    val systemPrompt: String = "",
    // Settings - server behavior
    val autoStartOnOpen: Boolean = false,
    val autoStartOnBoot: Boolean = false,
    val keepAwake: Boolean = true,
    // Performance
    val lastProcessingMs: Long = 0,
    val tokensPerSecond: Float = 0f,
    // UI
    val showSettings: Boolean = false,
    val activeTab: Int = 0 // 0=Server, 1=Chat, 2=Download
)

// ── ViewModel ──

class ServerViewModel(app: Application) : AndroidViewModel(app) {
    private val localApp = app as LocalAIApp
    private val engine = localApp.engine
    private val settings = localApp.settings
    private val _uiState = MutableStateFlow(ServerUiState())
    val uiState: StateFlow<ServerUiState> = _uiState

    val engineStatus = engine.status
    val loadedModelName = engine.loadedModelName

    init {
        // Restore persisted settings
        _uiState.update { it.copy(
            maxTokens = settings.maxTokens,
            temperature = settings.temperature,
            topK = settings.topK,
            topP = settings.topP,
            systemPrompt = settings.systemPrompt,
            autoStartOnOpen = settings.autoStartOnOpen,
            autoStartOnBoot = settings.autoStartOnBoot,
            keepAwake = settings.keepAwake,
            selectedModelPath = settings.lastModelPath
        ) }
        engine.maxTokens = settings.maxTokens
        engine.defaultTemperature = settings.temperature
        engine.defaultTopK = settings.topK
        engine.defaultTopP = settings.topP

        viewModelScope.launch(Dispatchers.IO) {
            scanModels()
            // Auto-start: load last model + start server
            if (settings.autoStartOnOpen && settings.lastModelPath.isNotBlank()) {
                autoStart()
            }
        }
    }

    private suspend fun autoStart() {
        val path = settings.lastModelPath
        if (path.isBlank() || engine.isLoaded) return
        _uiState.update { it.copy(isModelLoading = true, message = "Đang tự tải mô hình...") }
        engine.loadModel(path)
            .onSuccess {
                _uiState.update { it.copy(isModelLoading = false, message = "Mô hình sẵn sàng! Đang khởi động máy chủ...") }
                AiServerService.start(getApplication())
            }
            .onFailure { e ->
                _uiState.update { it.copy(isModelLoading = false, message = "Tự tải thất bại: ${e.message}") }
            }
    }

    private fun scanModels() {
        val models = engine.findLocalModels()
        val downloaded = engine.getDownloadedCatalogIds()
        _uiState.update { it.copy(localModels = models, downloadedIds = downloaded) }
    }

    fun setActiveTab(tab: Int) { _uiState.update { it.copy(activeTab = tab) } }
    fun toggleSettings() { _uiState.update { it.copy(showSettings = !it.showSettings) } }

    fun selectModel(path: String) {
        _uiState.update { it.copy(selectedModelPath = path) }
        settings.lastModelPath = path
    }

    fun loadModel() {
        val path = _uiState.value.selectedModelPath
        if (path.isBlank()) return
        _uiState.update { it.copy(isModelLoading = true, message = "Đang tải mô hình...") }
        viewModelScope.launch {
            engine.maxTokens = _uiState.value.maxTokens
            engine.defaultTemperature = _uiState.value.temperature
            engine.defaultTopK = _uiState.value.topK
            engine.defaultTopP = _uiState.value.topP

            engine.loadModel(path)
                .onSuccess {
                    settings.lastModelPath = path
                    _uiState.update { it.copy(isModelLoading = false, message = "Mô hình sẵn sàng!") }
                }
                .onFailure { e -> _uiState.update { it.copy(isModelLoading = false, message = "Lỗi: ${e.message}") } }
        }
    }

    fun unloadModel() {
        engine.unload()
        _uiState.update { it.copy(message = "Đã gỡ mô hình") }
    }

    fun downloadModel(model: LlmEngine.DownloadableModel) {
        if (_uiState.value.downloadingId != null) return
        _uiState.update { it.copy(downloadingId = model.id, downloadProgress = 0f, downloadTotalMb = model.sizeMb) }
        viewModelScope.launch {
            engine.downloadModel(model) { dl, total, progress ->
                _uiState.update { it.copy(downloadProgress = progress, downloadedMb = dl / (1024*1024), downloadTotalMb = total / (1024*1024)) }
            }.onSuccess { path ->
                _uiState.update { it.copy(downloadingId = null, selectedModelPath = path, message = "Đã tải ${model.displayName}") }
                scanModels()
            }.onFailure { e ->
                _uiState.update { it.copy(downloadingId = null, message = "Tải thất bại: ${e.message}") }
            }
        }
    }

    fun cancelDownload() {
        engine.cancelDownload()
        _uiState.update { it.copy(downloadingId = null, message = "Đã hủy") }
    }

    fun deleteModel(id: String) {
        engine.deleteModel(id)
        scanModels()
        _uiState.update { it.copy(message = "Đã xóa") }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) { scanModels() }
    }

    // ── Settings ──

    fun updateMaxTokens(v: Int) { _uiState.update { it.copy(maxTokens = v) }; engine.maxTokens = v; settings.maxTokens = v }
    fun updateTemperature(v: Float) { _uiState.update { it.copy(temperature = v) }; engine.defaultTemperature = v; settings.temperature = v }
    fun updateTopK(v: Int) { _uiState.update { it.copy(topK = v) }; engine.defaultTopK = v; settings.topK = v }
    fun updateTopP(v: Float) { _uiState.update { it.copy(topP = v) }; engine.defaultTopP = v; settings.topP = v }
    fun updateSystemPrompt(v: String) { _uiState.update { it.copy(systemPrompt = v) }; settings.systemPrompt = v }

    fun updateAutoStartOnOpen(v: Boolean) { _uiState.update { it.copy(autoStartOnOpen = v) }; settings.autoStartOnOpen = v }
    fun updateAutoStartOnBoot(v: Boolean) { _uiState.update { it.copy(autoStartOnBoot = v) }; settings.autoStartOnBoot = v }
    fun updateKeepAwake(v: Boolean) { _uiState.update { it.copy(keepAwake = v) }; settings.keepAwake = v }

    // ── Chat (text-only, dùng cho test trên app) ──

    fun sendMessage(userText: String) {
        if (userText.isBlank() || _uiState.value.isGenerating) return

        val userMsg = UiChatMessage("user", userText)
        _uiState.update { it.copy(
            chatMessages = it.chatMessages + userMsg,
            isGenerating = true,
            streamingContent = ""
        ) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _uiState.value
                val startTime = System.currentTimeMillis()

                val messages = mutableListOf<LlmEngine.ChatMessage>()
                if (state.systemPrompt.isNotBlank()) {
                    messages.add(LlmEngine.ChatMessage("system", state.systemPrompt))
                }
                state.chatMessages.forEach { msg ->
                    messages.add(LlmEngine.ChatMessage(msg.role, msg.content))
                }

                engine.generateStreamingFromMessages(
                    messages,
                    temperature = state.temperature,
                    topK = state.topK,
                    topP = state.topP,
                    onChunk = { chunk ->
                        _uiState.update { it.copy(streamingContent = it.streamingContent + chunk) }
                    },
                    onComplete = { fullText ->
                        val elapsed = System.currentTimeMillis() - startTime
                        val tps = if (elapsed > 0) (fullText.length / 4f) / (elapsed / 1000f) else 0f
                        _uiState.update { it.copy(
                            chatMessages = it.chatMessages + UiChatMessage("assistant", fullText),
                            isGenerating = false,
                            streamingContent = "",
                            lastProcessingMs = elapsed,
                            tokensPerSecond = tps
                        ) }
                    },
                    onError = { e ->
                        _uiState.update { it.copy(
                            chatMessages = it.chatMessages + UiChatMessage("assistant", "Lỗi: ${e.message}"),
                            isGenerating = false,
                            streamingContent = ""
                        ) }
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    chatMessages = it.chatMessages + UiChatMessage("assistant", "Lỗi: ${e.message}"),
                    isGenerating = false,
                    streamingContent = ""
                ) }
            }
        }
    }

    fun clearChat() {
        _uiState.update { it.copy(chatMessages = emptyList(), streamingContent = "") }
    }

    fun clearMessage() { _uiState.update { it.copy(message = null) } }
}

// ── Main Screen ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(viewModel: ServerViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val loadedModel by viewModel.loadedModelName.collectAsStateWithLifecycle()
    val isRunning by AiServerService.isRunning.collectAsStateWithLifecycle()
    val activePort by AiServerService.activePort.collectAsStateWithLifecycle()
    val requestCount by AiServerService.requestCount.collectAsStateWithLifecycle()
    val logs by AiServerService.logs.collectAsStateWithLifecycle()
    val processPid by AiServerService.processPid.collectAsStateWithLifecycle()
    val serviceRestarts by AiServerService.serviceRestartCount.collectAsStateWithLifecycle()
    val selfPingOk by AiServerService.lastSelfPingOk.collectAsStateWithLifecycle()
    val tasks by AiServerService.tasks.collectAsStateWithLifecycle()
    val successCount by AiServerService.successCount.collectAsStateWithLifecycle()
    val failCount by AiServerService.failCount.collectAsStateWithLifecycle()
    val totalLatency by AiServerService.totalLatencyMs.collectAsStateWithLifecycle()
    val activeTaskCount by AiServerService.activeTaskCount.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTaskId by remember { mutableStateOf<String?>(null) }

    // Show messages via snackbar
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearMessage()
        }
    }

    val layoutSize = rememberLayoutSize()
    val useRail = layoutSize != LayoutSize.COMPACT

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local AI Server") },
                actions = {
                    IconButton(onClick = { viewModel.toggleSettings() }) {
                        Icon(Icons.Default.Settings, "Cài đặt")
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, "Làm mới")
                    }
                }
            )
        },
        bottomBar = {
            if (!useRail) {
                NavigationBar {
                    NavigationBarItem(
                        selected = uiState.activeTab == 0,
                        onClick = { viewModel.setActiveTab(0) },
                        icon = { Icon(Icons.Default.Home, null) },
                        label = { Text("Máy chủ") }
                    )
                    NavigationBarItem(
                        selected = uiState.activeTab == 1,
                        onClick = { viewModel.setActiveTab(1) },
                        icon = { Icon(Icons.Default.Forum, null) },
                        label = { Text("Trò chuyện") },
                        enabled = engineStatus == LlmEngine.Status.READY
                    )
                    NavigationBarItem(
                        selected = uiState.activeTab == 2,
                        onClick = { viewModel.setActiveTab(2) },
                        icon = { Icon(Icons.Default.CloudDownload, null) },
                        label = { Text("Mô hình") }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            if (useRail) {
                NavigationRail {
                    Spacer(Modifier.height(8.dp))
                    NavigationRailItem(
                        selected = uiState.activeTab == 0,
                        onClick = { viewModel.setActiveTab(0) },
                        icon = { Icon(Icons.Default.Home, null) },
                        label = { Text("Máy chủ") }
                    )
                    NavigationRailItem(
                        selected = uiState.activeTab == 1,
                        onClick = { viewModel.setActiveTab(1) },
                        icon = { Icon(Icons.Default.Forum, null) },
                        label = { Text("Trò chuyện") },
                        enabled = engineStatus == LlmEngine.Status.READY
                    )
                    NavigationRailItem(
                        selected = uiState.activeTab == 2,
                        onClick = { viewModel.setActiveTab(2) },
                        icon = { Icon(Icons.Default.CloudDownload, null) },
                        label = { Text("Mô hình") }
                    )
                }
            }

            Column(Modifier.fillMaxSize()) {
                // Settings panel (collapsible)
                AnimatedVisibility(visible = uiState.showSettings) {
                    SettingsPanel(uiState, viewModel)
                }

                when (uiState.activeTab) {
                    0 -> ServerTab(uiState, viewModel, engineStatus, loadedModel, isRunning, activePort, requestCount, logs, context,
                        processPid, serviceRestarts, selfPingOk,
                        tasks, successCount, failCount, totalLatency, activeTaskCount, layoutSize,
                        onTaskClick = { selectedTaskId = it })
                    1 -> ChatTab(uiState, viewModel, loadedModel, layoutSize)
                    2 -> DownloadTab(uiState, viewModel, layoutSize)
                }
            }
        }

        // Task detail bottom sheet
        selectedTaskId?.let { tid ->
            TaskDetailSheet(taskId = tid, onDismiss = { selectedTaskId = null })
        }
    }
}

// ── Settings Panel ──

@Composable
fun SettingsPanel(uiState: ServerUiState, viewModel: ServerViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Cài đặt", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // ── Server Behavior ──
            Text("Máy chủ", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tự khởi động khi mở app", style = MaterialTheme.typography.bodySmall)
                    Text("Tự tải mô hình và chạy máy chủ", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = uiState.autoStartOnOpen, onCheckedChange = { viewModel.updateAutoStartOnOpen(it) })
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tự khởi động khi bật máy", style = MaterialTheme.typography.bodySmall)
                    Text("Máy chủ chạy ngay khi bật điện thoại", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = uiState.autoStartOnBoot, onCheckedChange = { viewModel.updateAutoStartOnBoot(it) })
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Giữ CPU hoạt động (WakeLock)", style = MaterialTheme.typography.bodySmall)
                    Text("Ngăn CPU ngủ khi máy chủ chạy nền", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = uiState.keepAwake, onCheckedChange = { viewModel.updateKeepAwake(it) })
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // ── Inference ──
            Text("Tham số suy luận", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)

            // System Prompt
            OutlinedTextField(
                value = uiState.systemPrompt,
                onValueChange = { viewModel.updateSystemPrompt(it) },
                label = { Text("System Prompt (chỉ dẫn hệ thống)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1, maxLines = 3,
                textStyle = MaterialTheme.typography.bodySmall
            )

            // Max Tokens
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Token tối đa: ${uiState.maxTokens}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
                Slider(
                    value = uiState.maxTokens.toFloat(),
                    onValueChange = { viewModel.updateMaxTokens(it.toInt()) },
                    valueRange = 128f..4096f,
                    steps = 15,
                    modifier = Modifier.weight(1f)
                )
            }

            // Temperature
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Temperature: ${"%.2f".format(uiState.temperature)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
                Slider(
                    value = uiState.temperature,
                    onValueChange = { viewModel.updateTemperature(it) },
                    valueRange = 0f..2f,
                    modifier = Modifier.weight(1f)
                )
            }

            // Top K
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Top K: ${uiState.topK}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
                Slider(
                    value = uiState.topK.toFloat(),
                    onValueChange = { viewModel.updateTopK(it.toInt()) },
                    valueRange = 1f..128f,
                    modifier = Modifier.weight(1f)
                )
            }

            // Top P
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Top P: ${"%.2f".format(uiState.topP)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
                Slider(
                    value = uiState.topP,
                    onValueChange = { viewModel.updateTopP(it) },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ── Server Tab ──

@Composable
fun ServerTab(
    uiState: ServerUiState,
    viewModel: ServerViewModel,
    engineStatus: LlmEngine.Status,
    loadedModel: String?,
    isRunning: Boolean,
    activePort: Int,
    requestCount: Int,
    logs: List<String>,
    context: android.content.Context,
    processPid: Int,
    serviceRestarts: Int,
    selfPingOk: Boolean,
    tasks: List<AiServerService.Companion.RequestTask>,
    successCount: Int,
    failCount: Int,
    totalLatency: Long,
    activeTaskCount: Int,
    layoutSize: LayoutSize,
    onTaskClick: (String) -> Unit = {}
) {
    // Two-pane layout on Expanded (Z Fold unfolded, tablet landscape)
    if (layoutSize == LayoutSize.EXPANDED && isRunning) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            // Top row: compact status + KPIs inline
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CompactStatusCard(isRunning, loadedModel, activePort, context, Modifier.weight(1f))
                KpiCardsRow(
                    totalRequests = successCount + failCount,
                    successCount = successCount,
                    failCount = failCount,
                    avgLatencyMs = if ((successCount + failCount) > 0)
                        totalLatency / (successCount + failCount) else 0L,
                    activeCount = activeTaskCount,
                    modifier = Modifier.weight(1.2f)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Main area: Requests timeline (left, wider) + Sidebar (right)
            Row(Modifier.fillMaxWidth().weight(1f)) {
                // Requests timeline
                Column(Modifier.weight(1.4f).fillMaxHeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Yêu cầu gần đây", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        val hasActive = tasks.any { it.status == AiServerService.Companion.TaskStatus.PROCESSING }
                        if (hasActive) {
                            IconButton(onClick = {
                                tasks.filter { it.status == AiServerService.Companion.TaskStatus.PROCESSING }
                                    .forEach { AiServerService.cancelTask(it.id) }
                            }) {
                                Icon(Icons.Default.StopCircle, "Dừng tất cả", Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (tasks.isNotEmpty()) {
                            IconButton(onClick = {
                                shareText(context, "Server tasks report", tasksToReportText(tasks))
                            }) { Icon(Icons.Default.IosShare, "Export tất cả", Modifier.size(18.dp)) }
                            IconButton(onClick = {
                                copyToClipboard(context, "Server tasks", tasksToReportText(tasks))
                                android.widget.Toast.makeText(context, "Đã copy ${tasks.size} task", android.widget.Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Default.ContentCopy, "Copy tất cả", Modifier.size(18.dp)) }
                            IconButton(onClick = { AiServerService.clearTaskHistory() }) {
                                Icon(Icons.Default.DeleteSweep, "Xóa", Modifier.size(18.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        val currentTask = tasks.firstOrNull { it.status == AiServerService.Companion.TaskStatus.PROCESSING }
                        if (currentTask != null) item { CurrentTaskCard(currentTask) }

                        if (tasks.isEmpty()) {
                            item { EmptyTasksCard() }
                        } else {
                            items(tasks.take(100)) { task -> TaskRow(task, onClick = { onTaskClick(task.id) }) }
                        }
                    }
                }

                Spacer(Modifier.width(12.dp))
                VerticalDivider()
                Spacer(Modifier.width(12.dp))

                // Sidebar: model + diagnostics + logs
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text("Mô hình", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    item { ModelSelectorRow(uiState, viewModel, engineStatus) }
                    item { DiagnosticsCard(processPid, serviceRestarts, selfPingOk) }
                    item { ApiEndpointsCard() }
                    if (logs.isNotEmpty()) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Nhật ký", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = {
                                    shareText(context, "Server logs", logs.joinToString("\n"))
                                }) { Icon(Icons.Default.IosShare, "Export", Modifier.size(16.dp)) }
                                IconButton(onClick = {
                                    copyToClipboard(context, "Server logs", logs.joinToString("\n"))
                                    android.widget.Toast.makeText(context, "Đã copy ${logs.size} dòng", android.widget.Toast.LENGTH_SHORT).show()
                                }) { Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(16.dp)) }
                            }
                        }
                        items(logs.reversed().take(30)) { log ->
                            Text(log, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
        return
    }

    // Compact/Medium single-column layout
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Server Status Card ──
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isRunning && engineStatus == LlmEngine.Status.READY)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isRunning) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            null,
                            tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                if (isRunning) "Máy chủ đang chạy" else "Máy chủ đã dừng",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (isRunning) {
                                val lanIp = remember(isRunning) { getLanIpAddress() }
                                Text("Local (máy này):", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("http://127.0.0.1:$activePort", style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                                if (lanIp != null) {
                                    Spacer(Modifier.height(2.dp))
                                    Text("LAN (máy khác cùng WiFi):", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("http://$lanIp:$activePort", style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary)
                                } else {
                                    Text("LAN: không có WiFi", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text("Mô hình: ${loadedModel ?: "chưa có"}", style = MaterialTheme.typography.bodySmall)
                                Text("Số yêu cầu: $requestCount", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    // Performance metrics
                    if (isRunning && uiState.tokensPerSecond > 0) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${"%.1f".format(uiState.tokensPerSecond)}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text("tok/s", style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${uiState.lastProcessingMs}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text("ms", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // API info
                    if (isRunning) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text("Các API có sẵn", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Text("Chat:  POST /v1/chat/completions", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                Text("Model: GET  /v1/models", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                Text("Audio: POST /v1/audio/transcriptions", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                Text("Image: POST /v1/images/describe", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                Text("Video: POST /v1/video/analyze", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                Text("Sức khỏe: GET /api/health", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isRunning) {
                            Button(
                                onClick = { AiServerService.stop(context) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) { Icon(Icons.Default.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Dừng") }
                        } else {
                            Button(
                                onClick = { AiServerService.start(context) },
                                enabled = engineStatus == LlmEngine.Status.READY
                            ) { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Chạy") }
                        }
                    }
                }
            }
        }

        // ── KPI Dashboard ──
        if (isRunning) {
            item {
                KpiCardsRow(
                    totalRequests = successCount + failCount,
                    successCount = successCount,
                    failCount = failCount,
                    avgLatencyMs = if ((successCount + failCount) > 0)
                        totalLatency / (successCount + failCount) else 0L,
                    activeCount = activeTaskCount
                )
            }

            // Current processing task indicator
            val currentTask = tasks.firstOrNull { it.status == AiServerService.Companion.TaskStatus.PROCESSING }
            if (currentTask != null) {
                item { CurrentTaskCard(currentTask) }
            }

            // ── Request Timeline ──
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Yêu cầu gần đây", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (tasks.isNotEmpty()) {
                        IconButton(onClick = {
                            shareText(context, "Server tasks report", tasksToReportText(tasks))
                        }) { Icon(Icons.Default.IosShare, "Export", Modifier.size(18.dp)) }
                        IconButton(onClick = {
                            copyToClipboard(context, "Server tasks", tasksToReportText(tasks))
                            android.widget.Toast.makeText(context, "Đã copy ${tasks.size} task", android.widget.Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(18.dp)) }
                        IconButton(onClick = { AiServerService.clearTaskHistory() }) {
                            Icon(Icons.Default.DeleteSweep, "Xóa", Modifier.size(18.dp))
                        }
                    }
                }
            }

            if (tasks.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Inbox, null, Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            Spacer(Modifier.height(8.dp))
                            Text("Chưa có yêu cầu nào",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Gọi API từ app khác để xem ở đây",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            } else {
                items(tasks.take(30)) { task ->
                    TaskRow(task, onClick = { onTaskClick(task.id) })
                }
            }

            // ── Diagnostics ──
            item {
                DiagnosticsCard(processPid, serviceRestarts, selfPingOk)
            }
        }

        // ── Model Selection ──
        item {
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            Text("Mô hình", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        item {
            var expanded by remember { mutableStateOf(false) }
            val selectedName = uiState.localModels.find { it.path == uiState.selectedModelPath }?.name
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedName ?: if (uiState.localModels.isEmpty()) "Chưa có mô hình" else "Chọn mô hình...",
                            maxLines = 1)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        if (uiState.localModels.isEmpty()) {
                            DropdownMenuItem(text = { Text("Chưa có mô hình. Vào tab Mô hình để tải.") }, onClick = { expanded = false })
                        }
                        uiState.localModels.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model.name, fontWeight = FontWeight.Medium)
                                        Text("${model.sizeMb}MB", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = { viewModel.selectModel(model.path); expanded = false },
                                leadingIcon = {
                                    if (model.path == uiState.selectedModelPath) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                    else Icon(Icons.Default.Memory, null)
                                }
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (engineStatus == LlmEngine.Status.READY) {
                    OutlinedButton(onClick = { viewModel.unloadModel() }) {
                        Icon(Icons.Default.Close, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Gỡ mô hình")
                    }
                } else {
                    Button(
                        onClick = { viewModel.loadModel() },
                        enabled = uiState.selectedModelPath.isNotBlank() && !uiState.isModelLoading
                    ) {
                        if (uiState.isModelLoading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp)); Text("Đang tải...")
                        } else {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Tải mô hình")
                        }
                    }
                }
            }
        }

        // ── Server Logs ──
        if (logs.isNotEmpty()) {
            item {
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Nhật ký", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        shareText(context, "Server logs", logs.joinToString("\n"))
                    }) { Icon(Icons.Default.IosShare, "Export logs", Modifier.size(18.dp)) }
                    IconButton(onClick = {
                        copyToClipboard(context, "Server logs", logs.joinToString("\n"))
                        android.widget.Toast.makeText(context, "Đã copy ${logs.size} dòng log", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.ContentCopy, "Copy logs", Modifier.size(18.dp)) }
                }
            }
            items(logs.reversed()) { log ->
                Text(log, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** Get WiFi/LAN IP address of this device (for external connection URL) */
fun getLanIpAddress(): String? {
    return try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        for (intf in interfaces) {
            if (intf.isLoopback || !intf.isUp) continue
            for (addr in intf.inetAddresses) {
                if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                    return addr.hostAddress
                }
            }
        }
        null
    } catch (_: Exception) { null }
}

@Composable
fun DiagnosticsCard(pid: Int, restarts: Int, pingOk: Boolean) {
    // Poll system + heap memory every 3s, only update state when values change
    val context = LocalContext.current
    var availMb by remember { mutableStateOf(0L) }
    var totalMb by remember { mutableStateOf(0L) }
    var isLowMem by remember { mutableStateOf(false) }
    var heapUsedMb by remember { mutableStateOf(0L) }
    var heapMaxMb by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        while (true) {
            am.getMemoryInfo(info)
            val newAvail = info.availMem / (1024 * 1024)
            val newTotal = info.totalMem / (1024 * 1024)
            val newLow = info.lowMemory
            if (newAvail != availMb) availMb = newAvail
            if (newTotal != totalMb) totalMb = newTotal
            if (newLow != isLowMem) isLowMem = newLow

            val rt = Runtime.getRuntime()
            val newHeapUsed = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val newHeapMax = rt.maxMemory() / (1024 * 1024)
            if (newHeapUsed != heapUsedMb) heapUsedMb = newHeapUsed
            if (newHeapMax != heapMaxMb) heapMaxMb = newHeapMax

            kotlinx.coroutines.delay(3000)
        }
    }
    val heapPercent = if (heapMaxMb > 0) (heapUsedMb * 100 / heapMaxMb).toInt() else 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isLowMem || !pingOk || restarts > 1)
                MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (pingOk && !isLowMem) Icons.Default.MonitorHeart else Icons.Default.Warning,
                    null, Modifier.size(20.dp),
                    tint = if (pingOk && !isLowMem) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(8.dp))
                Text("Chẩn đoán", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PID", style = MaterialTheme.typography.labelSmall)
                    Text("$pid", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Restart", style = MaterialTheme.typography.labelSmall)
                    Text("$restarts", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        color = if (restarts > 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("HTTP", style = MaterialTheme.typography.labelSmall)
                    Text(if (pingOk) "OK" else "FAIL", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        color = if (pingOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Low RAM", style = MaterialTheme.typography.labelSmall)
                    Text(if (isLowMem) "YES" else "NO", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        color = if (isLowMem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // RAM bars
            Text("RAM thiết bị: ${availMb}MB trống / ${totalMb}MB tổng",
                style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = { if (totalMb > 0) 1f - (availMb.toFloat() / totalMb) else 0f },
                modifier = Modifier.fillMaxWidth().height(6.dp).padding(vertical = 2.dp)
            )

            Spacer(Modifier.height(4.dp))
            Text("Heap app: ${heapUsedMb}MB / ${heapMaxMb}MB ($heapPercent%)",
                style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = { heapPercent / 100f },
                modifier = Modifier.fillMaxWidth().height(6.dp).padding(vertical = 2.dp)
            )

            if (restarts > 1) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠ Service đã bị restart $restarts lần. Có thể thiết bị không đủ RAM cho model này.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (isLowMem) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "⚠ Hệ thống đang thiếu RAM - nguy cơ process bị kill. Hãy đóng các app khác.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ── Reusable LazyColumn items ──

fun serverStatusItem(
    scope: androidx.compose.foundation.lazy.LazyListScope,
    isRunning: Boolean,
    engineStatus: LlmEngine.Status,
    loadedModel: String?,
    activePort: Int,
    requestCount: Int,
    uiState: ServerUiState,
    context: android.content.Context
) {
    scope.item {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isRunning && engineStatus == LlmEngine.Status.READY)
                    MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isRunning) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        null,
                        tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (isRunning) "Máy chủ đang chạy" else "Máy chủ đã dừng",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (isRunning) {
                            val lanIp = remember(isRunning) { getLanIpAddress() }
                            Text("Local:", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("http://127.0.0.1:$activePort", style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                            if (lanIp != null) {
                                Text("LAN:", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("http://$lanIp:$activePort", style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            Text("Mô hình: ${loadedModel ?: "chưa có"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    // Start/Stop button aligned right
                    if (isRunning) {
                        FilledTonalButton(
                            onClick = { AiServerService.stop(context) },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) { Icon(Icons.Default.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Dừng") }
                    } else {
                        Button(
                            onClick = { AiServerService.start(context) },
                            enabled = engineStatus == LlmEngine.Status.READY
                        ) { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Chạy") }
                    }
                }
            }
        }
    }
}

fun modelSelectorItems(
    scope: androidx.compose.foundation.lazy.LazyListScope,
    uiState: ServerUiState,
    viewModel: ServerViewModel,
    engineStatus: LlmEngine.Status
) {
    scope.item {
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))
        Text("Mô hình", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
    scope.item { ModelSelectorRow(uiState, viewModel, engineStatus) }
}

@Composable
private fun ModelSelectorRow(uiState: ServerUiState, viewModel: ServerViewModel, engineStatus: LlmEngine.Status) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = uiState.localModels.find { it.path == uiState.selectedModelPath }?.name

    Column {
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedName ?: if (uiState.localModels.isEmpty()) "Chưa có mô hình" else "Chọn mô hình...", maxLines = 1)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (uiState.localModels.isEmpty()) {
                    DropdownMenuItem(text = { Text("Chưa có mô hình. Vào tab Mô hình để tải.") }, onClick = { expanded = false })
                }
                uiState.localModels.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(model.name, fontWeight = FontWeight.Medium)
                                Text("${model.sizeMb}MB", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = { viewModel.selectModel(model.path); expanded = false },
                        leadingIcon = {
                            if (model.path == uiState.selectedModelPath) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            else Icon(Icons.Default.Memory, null)
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (engineStatus == LlmEngine.Status.READY) {
                OutlinedButton(onClick = { viewModel.unloadModel() }) {
                    Icon(Icons.Default.Close, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Gỡ mô hình")
                }
            } else {
                Button(
                    onClick = { viewModel.loadModel() },
                    enabled = uiState.selectedModelPath.isNotBlank() && !uiState.isModelLoading
                ) {
                    if (uiState.isModelLoading) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp)); Text("Đang tải...")
                    } else {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Tải mô hình")
                    }
                }
            }
        }
    }
}

// ── Dashboard Components ──

@Composable
fun KpiCardsRow(
    totalRequests: Int,
    successCount: Int,
    failCount: Int,
    avgLatencyMs: Long,
    activeCount: Int,
    modifier: Modifier = Modifier
) {
    val successRate = if (totalRequests > 0) (successCount * 100 / totalRequests) else 100
    val successColor = when {
        totalRequests == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        successRate >= 95 -> MaterialTheme.colorScheme.primary
        successRate >= 80 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        KpiCard(
            label = "Tổng",
            value = "$totalRequests",
            subtitle = "yêu cầu",
            modifier = Modifier.weight(1f)
        )
        KpiCard(
            label = "Thành công",
            value = "$successRate%",
            subtitle = "$successCount/$totalRequests",
            valueColor = successColor,
            modifier = Modifier.weight(1f)
        )
        KpiCard(
            label = "Độ trễ TB",
            value = formatLatency(avgLatencyMs),
            subtitle = "trung bình",
            modifier = Modifier.weight(1f)
        )
        KpiCard(
            label = "Hàng đợi",
            value = "$activeCount",
            subtitle = if (activeCount > 0) "đang xử lý" else "trống",
            valueColor = if (activeCount > 0) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun KpiCard(
    label: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            Modifier.padding(10.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = valueColor,
                maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp)
        }
    }
}

@Composable
fun CurrentTaskCard(task: AiServerService.Companion.RequestTask) {
    // Live duration counter (1Hz is enough for human-readable display)
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(task.id) {
        while (task.status == AiServerService.Companion.TaskStatus.PROCESSING) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val elapsed = now - task.startTime

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.width(8.dp))
                Text("ĐANG XỬ LÝ", style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.weight(1f))
                Text(formatLatency(elapsed), fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { AiServerService.cancelTask(task.id) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.Stop, "Dừng task", Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("${task.method} ${task.endpoint}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Computer, null, Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f))
                Spacer(Modifier.width(4.dp))
                Text(task.clientIp, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace)
            }
            if (!task.preview.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(task.preview, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                    maxLines = 2)
            }
        }
    }
}

@Composable
fun TaskRow(task: AiServerService.Companion.RequestTask, onClick: () -> Unit = {}) {
    val statusColor = when (task.status) {
        AiServerService.Companion.TaskStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        AiServerService.Companion.TaskStatus.FAILED -> MaterialTheme.colorScheme.error
        AiServerService.Companion.TaskStatus.PROCESSING -> MaterialTheme.colorScheme.tertiary
    }
    val statusIcon = when (task.status) {
        AiServerService.Companion.TaskStatus.COMPLETED -> Icons.Default.CheckCircle
        AiServerService.Companion.TaskStatus.FAILED -> Icons.Default.Error
        AiServerService.Companion.TaskStatus.PROCESSING -> Icons.Default.Sync
    }
    val timeFmt = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = onClick
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(statusIcon, null, Modifier.size(16.dp), tint = statusColor)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("${task.method} ${task.endpoint}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(timeFmt.format(java.util.Date(task.startTime)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(" • ", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(task.clientIp,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace)
                        if (task.status == AiServerService.Companion.TaskStatus.PROCESSING && task.streamingChunks > 0) {
                            Text(" • ${task.streamingChunks} chunks", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatLatency(task.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        fontFamily = FontFamily.Monospace)
                    if (task.httpStatus > 0) {
                        Text("HTTP ${task.httpStatus}", style = MaterialTheme.typography.labelSmall,
                            color = statusColor.copy(alpha = 0.7f))
                    }
                }
                if (task.status == AiServerService.Companion.TaskStatus.PROCESSING) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { AiServerService.cancelTask(task.id) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Stop, "Dừng", Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailSheet(taskId: String, onDismiss: () -> Unit) {
    // Observe live task updates
    val tasksFlow by AiServerService.tasks.collectAsStateWithLifecycle()
    val task = tasksFlow.firstOrNull { it.id == taskId }
    if (task == null) { onDismiss(); return }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val timeFmt = remember { java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()) }
    var selectedTab by remember { mutableStateOf(0) }
    val context = LocalContext.current

    val statusColor = when (task.status) {
        AiServerService.Companion.TaskStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        AiServerService.Companion.TaskStatus.FAILED -> MaterialTheme.colorScheme.error
        AiServerService.Companion.TaskStatus.PROCESSING -> MaterialTheme.colorScheme.tertiary
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(0.9f)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusIcon = when (task.status) {
                    AiServerService.Companion.TaskStatus.COMPLETED -> Icons.Default.CheckCircle
                    AiServerService.Companion.TaskStatus.FAILED -> Icons.Default.Error
                    AiServerService.Companion.TaskStatus.PROCESSING -> Icons.Default.Sync
                }
                Icon(statusIcon, null, Modifier.size(24.dp), tint = statusColor)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("${task.method} ${task.endpoint}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("${task.status.name} • ${formatLatency(task.durationMs)} • HTTP ${if (task.httpStatus > 0) task.httpStatus else "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor)
                }
                if (task.status == AiServerService.Companion.TaskStatus.PROCESSING) {
                    IconButton(onClick = { AiServerService.cancelTask(task.id) }) {
                        Icon(Icons.Default.Stop, "Dừng task", Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
                IconButton(onClick = {
                    copyToClipboard(context, "Task ${task.id}", taskToDetailText(task))
                    android.widget.Toast.makeText(context, "Đã copy vào clipboard", android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, "Copy task", Modifier.size(20.dp))
                }
                IconButton(onClick = {
                    shareText(context, "Task ${task.method} ${task.endpoint}", taskToDetailText(task))
                }) {
                    Icon(Icons.Default.Share, "Chia sẻ task", Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(8.dp))

            // Tabs
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    text = { Text("Tổng quan") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    text = { Text("Request") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 },
                    text = { Text("Response") })
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 },
                    text = { Text("Events (${task.events.size})") })
            }
            Spacer(Modifier.height(8.dp))

            // Content
            when (selectedTab) {
                0 -> OverviewTabContent(task, timeFmt)
                1 -> RequestTabContent(task)
                2 -> ResponseTabContent(task)
                3 -> EventsTabContent(task, timeFmt)
            }
        }
    }
}

@Composable
private fun OverviewTabContent(task: AiServerService.Companion.RequestTask, timeFmt: java.text.SimpleDateFormat) {
    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            DetailSection("Client") {
                DetailRow("IP", task.clientIp)
                if (task.userAgent.isNotBlank()) DetailRow("User-Agent", task.userAgent)
            }
        }
        item {
            DetailSection("Timing") {
                DetailRow("Bắt đầu", timeFmt.format(java.util.Date(task.startTime)))
                task.endTime?.let { DetailRow("Kết thúc", timeFmt.format(java.util.Date(it))) }
                DetailRow("Thời gian", formatLatency(task.durationMs))
            }
        }
        item {
            DetailSection("Dữ liệu") {
                DetailRow("Input", formatBytes(task.inputBytes))
                DetailRow("Output", formatBytes(task.outputBytes))
                if (task.streamingChunks > 0) DetailRow("Stream chunks", "${task.streamingChunks}")
                DetailRow("HTTP Status", if (task.httpStatus > 0) "${task.httpStatus}" else "—")
            }
        }
        if (task.parameters.isNotEmpty()) {
            item {
                DetailSection("Tham số") {
                    task.parameters.forEach { (k, v) ->
                        DetailRow(k, if (v.length > 80) v.take(80) + "..." else v)
                    }
                }
            }
        }
        if (task.error != null) {
            item {
                DetailSection("Lỗi") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(task.error, modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
        item {
            DetailSection("ID") {
                Text(task.id, style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun RequestTabContent(task: AiServerService.Companion.RequestTask) {
    LazyColumn(Modifier.fillMaxWidth()) {
        if (task.requestBody.isNullOrBlank()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Không có nội dung request",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(formatJsonPretty(task.requestBody),
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun ResponseTabContent(task: AiServerService.Companion.RequestTask) {
    LazyColumn(Modifier.fillMaxWidth()) {
        if (task.responseBody.isNullOrBlank() && task.preview.isNullOrBlank()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    if (task.status == AiServerService.Companion.TaskStatus.PROCESSING) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.height(8.dp))
                            Text("Đang xử lý... ${task.streamingChunks} chunks",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Text("Không có phản hồi",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            item {
                val content = task.responseBody ?: task.preview ?: ""
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    SelectionContainer {
                        Text(content,
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun EventsTabContent(task: AiServerService.Companion.RequestTask, timeFmt: java.text.SimpleDateFormat) {
    LazyColumn(Modifier.fillMaxWidth()) {
        itemsIndexed(task.events) { idx, event ->
            val prev = if (idx > 0) task.events[idx - 1].timestamp else task.startTime
            val delta = event.timestamp - prev
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(92.dp)) {
                    Text(timeFmt.format(java.util.Date(event.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (idx > 0) {
                        Text("+${formatLatency(delta)}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.padding(top = 4.dp)) {
                    Box(
                        Modifier.size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(event.label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f))
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun DetailRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(key, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp))
        SelectionContainer(Modifier.weight(1f)) {
            Text(value, style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace)
        }
    }
}

// ── Clipboard + Share helpers ──

fun copyToClipboard(context: android.content.Context, label: String, text: String) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
}

fun shareText(context: android.content.Context, subject: String, text: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Chia sẻ / Lưu file").apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

/** Format task as readable text for copy/share */
fun taskToDetailText(task: AiServerService.Companion.RequestTask): String {
    val timeFmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
    val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    val sb = StringBuilder()
    sb.appendLine("=== TASK ${task.id} ===")
    sb.appendLine("${task.method} ${task.endpoint}")
    sb.appendLine("Status: ${task.status} (HTTP ${if (task.httpStatus > 0) task.httpStatus else "—"})")
    sb.appendLine("Duration: ${formatLatency(task.durationMs)}")
    sb.appendLine("Started: ${dateFmt.format(java.util.Date(task.startTime))}")
    task.endTime?.let { sb.appendLine("Ended: ${dateFmt.format(java.util.Date(it))}") }
    sb.appendLine()
    sb.appendLine("=== CLIENT ===")
    sb.appendLine("IP: ${task.clientIp}")
    if (task.userAgent.isNotBlank()) sb.appendLine("User-Agent: ${task.userAgent}")
    sb.appendLine()
    sb.appendLine("=== DATA ===")
    sb.appendLine("Input: ${formatBytes(task.inputBytes)}")
    sb.appendLine("Output: ${formatBytes(task.outputBytes)}")
    if (task.streamingChunks > 0) sb.appendLine("Stream chunks: ${task.streamingChunks}")
    sb.appendLine()
    if (task.parameters.isNotEmpty()) {
        sb.appendLine("=== PARAMETERS ===")
        task.parameters.forEach { (k, v) -> sb.appendLine("$k: $v") }
        sb.appendLine()
    }
    if (task.events.isNotEmpty()) {
        sb.appendLine("=== EVENTS (${task.events.size}) ===")
        task.events.forEachIndexed { idx, e ->
            val prev = if (idx > 0) task.events[idx - 1].timestamp else task.startTime
            val delta = e.timestamp - prev
            val deltaStr = if (idx > 0) " (+${formatLatency(delta)})" else ""
            sb.appendLine("[${timeFmt.format(java.util.Date(e.timestamp))}]$deltaStr ${e.label}")
        }
        sb.appendLine()
    }
    if (!task.requestBody.isNullOrBlank()) {
        sb.appendLine("=== REQUEST ===")
        sb.appendLine(formatJsonPretty(task.requestBody))
        sb.appendLine()
    }
    if (!task.responseBody.isNullOrBlank() || !task.preview.isNullOrBlank()) {
        sb.appendLine("=== RESPONSE ===")
        sb.appendLine(task.responseBody ?: task.preview)
        sb.appendLine()
    }
    if (task.error != null) {
        sb.appendLine("=== ERROR ===")
        sb.appendLine(task.error)
    }
    return sb.toString()
}

/** Format ALL tasks as a combined report */
fun tasksToReportText(tasks: List<AiServerService.Companion.RequestTask>): String {
    val sb = StringBuilder()
    sb.appendLine("LOCAL AI SERVER - TASK REPORT")
    sb.appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
    sb.appendLine("Total tasks: ${tasks.size}")
    sb.appendLine("==========================================")
    sb.appendLine()
    tasks.forEach { task ->
        sb.append(taskToDetailText(task))
        sb.appendLine("──────────────────────────────────────────")
        sb.appendLine()
    }
    return sb.toString()
}

/** Simple JSON pretty-printer */
fun formatJsonPretty(input: String): String {
    return try {
        val trimmed = input.trim()
        if (trimmed.startsWith("{")) {
            org.json.JSONObject(trimmed).toString(2)
        } else if (trimmed.startsWith("[")) {
            org.json.JSONArray(trimmed).toString(2)
        } else input
    } catch (_: Exception) { input }
}

fun formatLatency(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "%.1fs".format(ms / 1000.0)
    else -> "%dm %ds".format(ms / 60_000, (ms % 60_000) / 1000)
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
}

@Composable
fun CompactStatusCard(
    isRunning: Boolean,
    loadedModel: String?,
    activePort: Int,
    context: android.content.Context,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Đang chạy", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = { AiServerService.stop(context) },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Stop, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Dừng", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(6.dp))
            val lanIp = remember(isRunning) { getLanIpAddress() }
            Text("127.0.0.1:$activePort", style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace)
            if (lanIp != null) {
                Text("$lanIp:$activePort", style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text(loadedModel ?: "Chưa tải mô hình",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
        }
    }
}

@Composable
fun EmptyTasksCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Inbox, null, Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(8.dp))
            Text("Chưa có yêu cầu nào",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Gọi API từ app khác để xem ở đây",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}

@Composable
fun ApiEndpointsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("API có sẵn", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            listOf(
                "POST /v1/chat/completions",
                "POST /v1/audio/transcriptions",
                "POST /v1/images/describe",
                "POST /v1/video/analyze",
                "GET  /v1/models",
                "GET  /api/health"
            ).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Chat Tab ──

@Composable
fun ChatTab(uiState: ServerUiState, viewModel: ServerViewModel, loadedModel: String?, layoutSize: LayoutSize = LayoutSize.COMPACT) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(uiState.chatMessages.size, uiState.streamingContent) {
        if (uiState.chatMessages.isNotEmpty() || uiState.streamingContent.isNotEmpty()) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Chat header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Trò chuyện", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(loadedModel ?: "Chưa tải mô hình", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Performance badge
            if (uiState.tokensPerSecond > 0 && !uiState.isGenerating) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        "${"%.1f".format(uiState.tokensPerSecond)} tok/s | ${uiState.lastProcessingMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            IconButton(onClick = { viewModel.clearChat() }) {
                Icon(Icons.Default.DeleteSweep, "Xóa trò chuyện")
            }
        }

        HorizontalDivider()

        // Messages
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            if (uiState.chatMessages.isEmpty() && !uiState.isGenerating) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Forum, null, Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                            Spacer(Modifier.height(8.dp))
                            Text("Bắt đầu cuộc trò chuyện",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }

            items(uiState.chatMessages) { msg ->
                ChatBubble(msg, layoutSize = layoutSize)
            }

            // Streaming content
            if (uiState.isGenerating && uiState.streamingContent.isNotEmpty()) {
                item {
                    ChatBubble(UiChatMessage("assistant", uiState.streamingContent), isStreaming = true, layoutSize = layoutSize)
                }
            }

            // Loading indicator
            if (uiState.isGenerating && uiState.streamingContent.isEmpty()) {
                item {
                    Row(Modifier.padding(vertical = 8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Đang suy nghĩ...", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // Input area (text-only in UI - dùng endpoint API cho multimodal)
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Nhập tin nhắn...") },
                minLines = 1,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (inputText.isNotBlank() && !uiState.isGenerating) {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    }
                }),
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    if (inputText.isNotBlank() && !uiState.isGenerating) {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    }
                },
                enabled = inputText.isNotBlank() && !uiState.isGenerating,
                modifier = Modifier.size(48.dp)
            ) {
                if (uiState.isGenerating) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.ArrowUpward, "Gửi")
                }
            }
        }
    }
}

@Composable
fun ChatBubble(msg: UiChatMessage, isStreaming: Boolean = false, layoutSize: LayoutSize = LayoutSize.COMPACT) {
    val isUser = msg.role == "user"
    val maxWidth = when (layoutSize) {
        LayoutSize.COMPACT -> 300.dp
        LayoutSize.MEDIUM -> 500.dp
        LayoutSize.EXPANDED -> 700.dp
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = maxWidth),
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(Modifier.padding(12.dp)) {
                if (!isUser) {
                    Text("AI", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    msg.content + if (isStreaming) " _" else "",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

// ── Download Tab ──

@Composable
fun DownloadTab(uiState: ServerUiState, viewModel: ServerViewModel, layoutSize: LayoutSize = LayoutSize.COMPACT) {
    val columns = when (layoutSize) {
        LayoutSize.EXPANDED -> 3
        LayoutSize.MEDIUM -> 2
        LayoutSize.COMPACT -> 1
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Kho mô hình", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Tải mô hình để chạy AI ngay trên máy",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Download progress
        if (uiState.downloadingId != null) {
            Spacer(Modifier.height(8.dp))
            val model = LlmEngine.MODEL_CATALOG.find { it.id == uiState.downloadingId }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Đang tải: ${model?.displayName ?: "..."}", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { uiState.downloadProgress },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${uiState.downloadedMb}/${uiState.downloadTotalMb} MB", style = MaterialTheme.typography.labelSmall)
                        Text("${(uiState.downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { viewModel.cancelDownload() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Hủy") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Model catalog - adaptive grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            gridItems(LlmEngine.MODEL_CATALOG) { model ->
            val downloaded = model.id in uiState.downloadedIds
            val isDownloading = uiState.downloadingId == model.id

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (downloaded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(model.displayName, fontWeight = FontWeight.SemiBold)
                            if (downloaded) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    Text("Đã tải", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(model.description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("~${model.sizeMb}MB | RAM: ${model.ram}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Spacer(Modifier.width(8.dp))

                    if (downloaded) {
                        IconButton(onClick = { viewModel.deleteModel(model.id) }) {
                            Icon(Icons.Default.Delete, "Xóa", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        IconButton(
                            onClick = { viewModel.downloadModel(model) },
                            enabled = uiState.downloadingId == null
                        ) {
                            if (isDownloading) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.CloudDownload, "Tải xuống", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
    }
}
