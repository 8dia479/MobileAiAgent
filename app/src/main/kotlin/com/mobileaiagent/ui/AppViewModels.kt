package com.mobileaiagent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mobileaiagent.MobileAIAgentApp
import com.mobileaiagent.agent.NewTaskParams
import com.mobileaiagent.agent.plan.PlanManager
import com.mobileaiagent.core.json.JsonParser
import com.mobileaiagent.core.log.AgentLog
import com.mobileaiagent.domain.model.*
import com.mobileaiagent.domain.repo.AgentSettings
import com.mobileaiagent.domain.repo.ProjectRepository
import com.mobileaiagent.domain.repo.ProviderRepository
import com.mobileaiagent.domain.repo.RefreshOutcome
import com.mobileaiagent.providers.AiProvider
import com.mobileaiagent.providers.ModelDescriptor
import com.mobileaiagent.providers.ModelCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewModel factory wired to the application graph. ViewModels are pure UI
 * adapters: they observe TaskManager and issue commands, but never own task
 * execution (that would tie work to the UI lifecycle).
 */
object AppViewModelFactory {
    fun factory(app: MobileAIAgentApp): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
            modelClass.isAssignableFrom(ChatViewModel::class.java) -> ChatViewModel(app) as T
            modelClass.isAssignableFrom(TaskListViewModel::class.java) -> TaskListViewModel(app) as T
            modelClass.isAssignableFrom(WorkspaceViewModel::class.java) -> WorkspaceViewModel(app) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(app) as T
            modelClass.isAssignableFrom(ModelManagerViewModel::class.java) -> ModelManagerViewModel(app) as T
            modelClass.isAssignableFrom(ProjectViewModel::class.java) -> ProjectViewModel(app) as T
            modelClass.isAssignableFrom(ToolManagerViewModel::class.java) -> ToolManagerViewModel(app) as T
            modelClass.isAssignableFrom(PluginViewModel::class.java) -> PluginViewModel(app) as T
            else -> throw IllegalArgumentException("Unknown ViewModel $modelClass")
        }
    }
}

// --------------------------------------------------------------------- Chat

data class ChatUiState(
    val task: TaskRecord? = null,
    val messages: List<ChatMessage> = emptyList(),
    val events: List<ExecutionEvent> = emptyList(),
    val plan: Plan? = null,
    val isLive: Boolean = false,
    val streamingText: String = "",
    val streamingReasoning: String = "",
    val settings: AgentSettings = AgentSettings(),
    val projects: List<ProjectInfo> = emptyList(),
    val selectedProjectId: String? = null,
    val selectedMode: ExecutionMode = ExecutionMode.BUILD,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    val mainModel: String = "",
    val availableModels: List<ModelDescriptor> = emptyList(),
    val providers: Map<String, AiProvider> = emptyMap(),
    val busySending: Boolean = false,
    val error: String? = null,
)

/**
 * Chat is a *client* of TaskManager. Sending a message creates a persisted task
 * and returns immediately; streaming text is shown by observing task events, so
 * the same transcript appears whether the app was foregrounded the whole time or
 * reopened after a kill.
 */
class ChatViewModel(private val app: MobileAIAgentApp) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var currentTaskId: String? = null
    private var observeJob: kotlinx.coroutines.Job? = null
    val planManager: PlanManager get() = app.planManager

    init {
        viewModelScope.launch {
            app.providersReady()
            val settings = app.settingsRepository.snapshot()
            val models = app.modelCatalog.cachedModels(settings.defaultProviderId)
            val projects = app.projectRepository.listProjects()
            _state.update {
                it.copy(
                    settings = settings,
                    providers = app.providerRepository.providers(),
                    projects = projects,
                    selectedProjectId = projects.firstOrNull()?.id,
                    mainModel = settings.mainModel,
                    reasoningEffort = settings.mainReasoningEffort,
                    selectedMode = settings.defaultMode,
                    availableModels = models,
                )
            }
        }
        viewModelScope.launch {
            app.settings.collect { settings ->
                _state.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            app.taskManager.changes.collect { changed ->
                val taskId = currentTaskId ?: return@collect
                if (changed == taskId || changed == "*") reloadPlan()
            }
        }
    }

    fun openTask(taskId: String) {
        if (taskId == currentTaskId) return
        currentTaskId = taskId
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            var taskFlow: Flow<TaskRecord?> = app.taskManager.observeTask(taskId)
            launch {
                app.taskManager.observeMessages(taskId).collect { msgs ->
                    _state.update { it.copy(messages = msgs) }
                }
            }
            launch {
                app.taskManager.observeEvents(taskId).collect { events ->
                    _state.update { it.copy(events = events) }
                }
            }
            taskFlow.collect { task ->
                _state.update { it.copy(task = task, isLive = task?.status == TaskStatus.RUNNING) }
                if (task != null) {
                    app.taskManager.snapshot(taskId)
                    reloadPlan()
                    if (task.status.isTerminal) _state.update { it.copy(busySending = false) }
                }
            }
        }
    }

    fun newChat() {
        currentTaskId = null
        observeJob?.cancel()
        _state.update {
            it.copy(task = null, messages = emptyList(), events = emptyList(), plan = null, busySending = false, error = null)
        }
    }

    fun selectProject(projectId: String) {
        _state.update { it.copy(selectedProjectId = projectId) }
        reloadPlan()
    }

    fun setMode(mode: ExecutionMode) { _state.update { it.copy(selectedMode = mode) } }
    fun setReasoning(effort: ReasoningEffort) { _state.update { it.copy(reasoningEffort = effort) } }
    fun setModel(modelId: String) { _state.update { it.copy(mainModel = modelId) } }
    fun clearError() { _state.update { it.copy(error = null) } }

    fun send(request: String) {
        val trimmed = request.trim()
        if (trimmed.isEmpty() || _state.value.busySending) return
        viewModelScope.launch {
            _state.update { it.copy(busySending = true, error = null) }
            try {
                val state = _state.value
                val projectId = state.selectedProjectId
                val project = state.projects.firstOrNull { it.id == projectId }
                val task = app.taskManager.createTask(
                    NewTaskParams(
                        request = trimmed,
                        projectId = projectId,
                        projectName = project?.name,
                        mode = state.selectedMode,
                        mainModel = state.mainModel.ifBlank { state.settings.mainModel },
                        mainProvider = state.settings.defaultProviderId,
                        mainReasoningEffort = state.reasoningEffort,
                        secondaryModel = state.settings.secondaryModel,
                        secondaryProvider = state.settings.secondaryProviderId,
                        secondaryReasoningEffort = state.settings.secondaryReasoningEffort,
                        autoResumeOnBoot = state.settings.autoResumeOnBoot,
                    )
                )
                openTask(task.id)
                app.startForegroundServiceIfNeeded()
            } catch (e: Exception) {
                AgentLog.e("ChatViewModel", "Failed to create task", e)
                _state.update { it.copy(busySending = false, error = e.message ?: "Could not start task") }
            }
        }
    }

    fun cancel() {
        val taskId = currentTaskId ?: return
        viewModelScope.launch { app.taskManager.cancel(taskId) }
    }

    fun pause() {
        val taskId = currentTaskId ?: return
        viewModelScope.launch { app.taskManager.pause(taskId) }
    }

    fun resume() {
        val taskId = currentTaskId ?: return
        viewModelScope.launch {
            app.taskManager.resume(taskId)
            app.startForegroundServiceIfNeeded()
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            val providerId = _state.value.settings.defaultProviderId
            when (val outcome = app.modelCatalog.refreshModels(providerId)) {
                is RefreshOutcome.Refreshed -> {
                    val models = app.modelCatalog.cachedModels(providerId)
                    _state.update { it.copy(availableModels = models) }
                }
                is RefreshOutcome.Failed -> _state.update { it.copy(error = "Model refresh failed: ${outcome.message}") }
                RefreshOutcome.NoCredentials -> _state.update { it.copy(error = "Add an API key for $providerId first") }
            }
        }
    }

    private fun reloadPlan() {
        val projectId = _state.value.selectedProjectId ?: _state.value.task?.projectId
        if (projectId == null) return
        viewModelScope.launch {
            val info = app.projectRepository.projectInfo(projectId)
            val root = info?.rootPath?.let { File(it) } ?: return@launch
            val plan = withContext(Dispatchers.IO) { app.planManager.readActive(root) }
            _state.update { it.copy(plan = plan) }
        }
    }
}

// ---------------------------------------------------------------- Task list

data class TaskListUiState(
    val tasks: List<TaskRecord> = emptyList(),
    val activeCount: Int = 0,
    val queued: List<String> = emptyList(),
    val agentActive: Boolean = false,
)

class TaskListViewModel(private val app: MobileAIAgentApp) : ViewModel() {
    val state: StateFlow<TaskListUiState> = combine(
        app.taskManager.observeAll(),
        app.agentActive,
    ) { tasks, active ->
        TaskListUiState(
            tasks = tasks,
            activeCount = tasks.count { it.status == TaskStatus.RUNNING },
            queued = tasks.filter { it.status == TaskStatus.QUEUED }.map { it.id },
            agentActive = active,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TaskListUiState())

    fun pause(taskId: String) = viewModelScope.launch { app.taskManager.pause(taskId) }
    fun resume(taskId: String) {
        viewModelScope.launch {
            app.taskManager.resume(taskId)
            app.startForegroundServiceIfNeeded()
        }
    }
    fun cancel(taskId: String) = viewModelScope.launch { app.taskManager.cancel(taskId) }
    fun delete(taskId: String) = viewModelScope.launch {
        app.taskManager.cancel(taskId)
        app.stores.tasks.delete(taskId)
        app.stores.messages.deleteForTask(taskId)
        app.stores.events.deleteForTask(taskId)
        app.stores.journal.deleteForTask(taskId)
        app.stores.checkpoints.delete(taskId)
    }
    suspend fun events(taskId: String) = app.stores.events.list(taskId, 500)
    suspend fun journal(taskId: String) = app.stores.journal.list(taskId, 300)
}

// ---------------------------------------------------------------- Workspace

data class FileNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val depth: Int,
)

data class WorkspaceUiState(
    val projects: List<ProjectInfo> = emptyList(),
    val currentProjectId: String? = null,
    val nodes: List<FileNode> = emptyList(),
    val currentPath: String = "",
    val breadcrumbs: List<String> = emptyList(),
    val selectedFile: FileNode? = null,
    val previewText: String? = null,
    val previewKind: PreviewKind = PreviewKind.NONE,
    val loading: Boolean = false,
    val error: String? = null,
    val importMessage: String? = null,
)

/**
 * What the preview pane should render. MESH/PDF/DOCX/SHEET/IMAGE are *real*
 * viewers; CAD_UNSUPPORTED and UNSUPPORTED are stated honestly rather than
 * faked with a placeholder image.
 */
enum class PreviewKind {
    NONE, TEXT, IMAGE, MESH, PDF, DOCX, SHEET, BINARY_INFO, PLAN, CAD_UNSUPPORTED, UNSUPPORTED
}

class WorkspaceViewModel(private val app: MobileAIAgentApp) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceUiState())
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            app.providersReady()
            val projects = app.projectRepository.listProjects()
            val first = projects.firstOrNull()
            _state.update { it.copy(projects = projects, currentProjectId = first?.id) }
            if (first != null) refresh()
        }
    }

    fun selectProject(projectId: String) {
        _state.update { it.copy(currentProjectId = projectId, currentPath = "", selectedFile = null, previewText = null, previewKind = PreviewKind.NONE) }
        refresh()
    }

    fun openFolder(path: String) {
        _state.update { it.copy(currentPath = path, selectedFile = null, previewText = null, previewKind = PreviewKind.NONE) }
        refresh()
    }

    fun navigateUp() {
        val current = _state.value.currentPath
        if (current.isEmpty()) return
        openFolder(current.substringBeforeLast('/', ""))
    }

    fun refresh() {
        val projectId = _state.value.currentProjectId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val project = app.projectRepository.projectInfo(projectId) ?: return@launch
            _state.update { it.copy(loading = true, error = null) }
            try {
                val root = File(project.rootPath)
                val currentPath = _state.value.currentPath
                val dir = if (currentPath.isEmpty()) root else File(root, currentPath)
                if (!dir.exists()) { _state.update { it.copy(loading = false) }; return@launch }
                val nodes = (dir.listFiles() ?: emptyArray())
                    .filter { !it.name.startsWith(".") }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    .map { FileNode(it.name, relative(root, it), it.isDirectory, if (it.isFile) it.length() else 0, 0) }
                val crumbs = if (currentPath.isEmpty()) listOf(project.name) else listOf(project.name) + currentPath.split('/')
                _state.update { it.copy(nodes = nodes, loading = false, breadcrumbs = crumbs) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    private fun relative(root: File, file: File): String =
        runCatching { root.toPath().relativize(file.toPath()).toString() }.getOrDefault(file.name)

    fun openFile(node: FileNode) {
        val projectId = _state.value.currentProjectId ?: return
        val ext = node.name.substringAfterLast('.', "").lowercase()
        viewModelScope.launch(Dispatchers.IO) {
            val project = app.projectRepository.projectInfo(projectId) ?: return@launch
            val file = File(project.rootPath, node.path)
            _state.update { it.copy(loading = true) }
            val kind = when {
                node.name.endsWith(".plan.json") -> PreviewKind.PLAN
                ext == "csv" || ext == "tsv" -> PreviewKind.SHEET
                ext == "xlsx" -> PreviewKind.SHEET
                ext == "pdf" -> PreviewKind.PDF
                ext == "docx" -> PreviewKind.DOCX
                ext in setOf("stl", "obj", "glb", "gltf") -> PreviewKind.MESH
                ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp") -> PreviewKind.IMAGE
                ext == "svg" -> PreviewKind.TEXT
                ext in setOf(
                    "txt", "md", "json", "kt", "kts", "java", "py", "js", "ts", "html", "css", "xml",
                    "yml", "yaml", "toml", "sh", "gradle", "gitignore", "log", "dxf",
                ) -> PreviewKind.TEXT
                ext in setOf("step", "stp", "iges", "igs") -> PreviewKind.CAD_UNSUPPORTED
                ext in setOf("pptx", "zip", "apk", "mp4", "mp3", "wav", "mov") -> PreviewKind.BINARY_INFO
                else -> if (ext.isEmpty() && file.isFile) PreviewKind.TEXT else PreviewKind.UNSUPPORTED
            }
            val text = when (kind) {
                PreviewKind.TEXT -> runCatching {
                    if (file.length() > 2_000_000) "File is ${file.length()} bytes; open it with the agent to inspect."
                    else file.readText()
                }.getOrElse { "Could not read file: ${it.message}" }
                PreviewKind.PLAN -> runCatching { file.readText() }.getOrElse { null }
                // Rendered by MeshViewerPanel, which reloads the mesh itself.
                PreviewKind.MESH -> null
                PreviewKind.CAD_UNSUPPORTED ->
                    "CAD B-rep formats (STEP/IGES) are not previewed: reading them needs a " +
                        "geometry kernel, which this app does not ship.\n\n" +
                        "Ask the agent in Chat to convert ${node.name} to STL/OBJ for a 3D preview, " +
                        "or to extract dimensions and features from it."
                PreviewKind.PDF, PreviewKind.DOCX, PreviewKind.SHEET -> null
                PreviewKind.IMAGE -> null
                PreviewKind.BINARY_INFO -> "Binary file: ${node.name} (${com.mobileaiagent.core.util.humanBytes(node.sizeBytes)})\n\n" +
                    "Ask the agent in Chat to extract or convert this file."
                else -> "No preview available for '${node.name}'."
            }
            _state.update {
                it.copy(
                    loading = false, selectedFile = node, previewKind = kind, previewText = text,
                )
            }
        }
    }

    fun importUri(uri: android.net.Uri, displayName: String) {
        val projectId = _state.value.currentProjectId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val project = app.projectRepository.projectInfo(projectId) ?: return@launch
            try {
                val context = app
                val targetDir = File(project.rootPath, "Downloads").apply { mkdirs() }
                val safeName = com.mobileaiagent.core.util.safePathSegment(displayName)
                val target = File(targetDir, safeName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Could not open selected document")
                _state.update { it.copy(importMessage = "Imported $safeName", currentPath = "Downloads") }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(error = "Import failed: ${e.message}") }
            }
        }
    }

    fun deleteFile(node: FileNode) {
        val projectId = _state.value.currentProjectId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val project = app.projectRepository.projectInfo(projectId) ?: return@launch
            val file = File(project.rootPath, node.path)
            if (file.canonicalPath.startsWith(File(project.rootPath).canonicalPath)) {
                file.deleteRecursively()
                _state.update { it.copy(selectedFile = null, previewText = null, previewKind = PreviewKind.NONE) }
                refresh()
            }
        }
    }

    /** Resolves a project-relative path; must not run on the main thread. */
    suspend fun absoluteFile(node: FileNode): File? {
        val projectId = _state.value.currentProjectId ?: return null
        val project = app.projectRepository.projectInfo(projectId) ?: return null
        return File(project.rootPath, node.path)
    }

    fun clearMessages() { _state.update { it.copy(importMessage = null, error = null) } }
}

// ---------------------------------------------------------------- Settings

class SettingsViewModel(private val app: MobileAIAgentApp) : ViewModel() {
    val settings: StateFlow<AgentSettings> = app.settings

    fun update(transform: (AgentSettings) -> AgentSettings) {
        viewModelScope.launch {
            app.settingsRepository.update(transform)
            app.refreshSettings()
        }
    }

    suspend fun setApiKey(providerId: String, key: String) {
        app.credentialStore.setKey(providerId, key)
        (app.providerRepository as? com.mobileaiagent.providers.ProviderRepositoryImpl)?.invalidate()
        AgentLog.i("Settings", "API key updated for $providerId")
    }

    suspend fun hasApiKey(providerId: String): Boolean = app.credentialStore.hasKey(providerId)

    suspend fun testProvider(providerId: String): String {
        val provider = app.providerRepository.provider(providerId) ?: return "Provider not found"
        if (!provider.isConfigured()) return "No API key saved"
        val result = provider.testConnection()
        return if (result.ok) "Connected: ${result.message}" else "Failed: ${result.message}"
    }
}

// ------------------------------------------------------------ Model manager

data class ModelManagerUiState(
    val providers: List<AiProvider> = emptyList(),
    val selectedProviderId: String = "nvidia_nim",
    val models: List<ModelDescriptor> = emptyList(),
    val refreshing: Boolean = false,
    val lastRefresh: Long? = null,
    val message: String? = null,
    val configuredProviders: Set<String> = emptySet(),
)

class ModelManagerViewModel(private val app: MobileAIAgentApp) : ViewModel() {
    private val _state = MutableStateFlow(ModelManagerUiState())
    val state: StateFlow<ModelManagerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            app.providersReady()
            val providers = app.providerRepository.providers().values.toList()
            val settings = app.settingsRepository.snapshot()
            _state.update {
                it.copy(
                    providers = providers,
                    selectedProviderId = settings.defaultProviderId,
                    configuredProviders = providers.filter { p -> p.isConfigured() }.map { p -> p.id }.toSet(),
                )
            }
            loadCached(settings.defaultProviderId)
        }
    }

    fun selectProvider(providerId: String) {
        _state.update { it.copy(selectedProviderId = providerId) }
        viewModelScope.launch { loadCached(providerId) }
    }

    private suspend fun loadCached(providerId: String) {
        val models = app.modelCatalog.cachedModels(providerId)
        val last = app.modelCatalog.lastRefresh(providerId)
        _state.update { it.copy(models = models, lastRefresh = last) }
    }

    fun refresh() {
        val providerId = _state.value.selectedProviderId
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, message = null) }
            val outcome = app.modelCatalog.refreshModels(providerId)
            val message = when (outcome) {
                is RefreshOutcome.Refreshed -> "Discovered ${outcome.count} models"
                is RefreshOutcome.Failed -> "Refresh failed: ${outcome.message}" +
                    if (outcome.usedCache) " (showing cached list)" else ""
                RefreshOutcome.NoCredentials -> "No API key saved for this provider"
            }
            loadCached(providerId)
            _state.update { it.copy(refreshing = false, message = message) }
        }
    }

    fun addManualModel(modelId: String) {
        val providerId = _state.value.selectedProviderId
        if (modelId.isBlank()) return
        viewModelScope.launch {
            app.modelCatalog.upsertManualModel(
                ModelDescriptor(
                    id = modelId, displayName = modelId, providerId = providerId,
                    capabilitiesSource = com.mobileaiagent.providers.CapabilitySource.VERIFIED_MANUAL,
                )
            )
            loadCached(providerId)
            _state.update { it.copy(message = "Added manual model $modelId") }
        }
    }

    fun setCapability(modelId: String, capability: String, value: Boolean?) {
        val providerId = _state.value.selectedProviderId
        viewModelScope.launch {
            val current = app.modelCatalog.capabilitiesOverride(providerId, modelId) ?: ModelCapabilities()
            val updated = when (capability) {
                "vision" -> current.copy(vision = value)
                "tools" -> current.copy(toolCalling = value)
                "reasoning" -> current.copy(reasoning = value)
                "structured" -> current.copy(structuredOutput = value)
                else -> current
            }
            app.modelCatalog.setCapabilitiesOverride(providerId, modelId, updated)
            loadCached(providerId)
        }
    }

    fun selectAsMain(modelId: String) {
        val providerId = _state.value.selectedProviderId
        viewModelScope.launch {
            app.settingsRepository.update { it.copy(mainModel = modelId, defaultProviderId = providerId) }
            app.refreshSettings()
            _state.update { it.copy(message = "Main model set to $modelId") }
        }
    }

    fun selectAsSecondary(modelId: String) {
        val providerId = _state.value.selectedProviderId
        viewModelScope.launch {
            app.settingsRepository.update {
                it.copy(secondaryModel = modelId, secondaryProviderId = providerId)
            }
            app.refreshSettings()
            _state.update { it.copy(message = "Secondary (delegation) model set to $modelId") }
        }
    }
}

// ---------------------------------------------------------------- Projects

class ProjectViewModel(private val app: MobileAIAgentApp) : ViewModel() {
    private val _projects = MutableStateFlow<List<ProjectInfo>>(emptyList())
    val projects: StateFlow<List<ProjectInfo>> = _projects.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _projects.value = app.projectRepository.listProjects()
        }
    }

    fun create(name: String, description: String?) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            app.projectRepository.createProject(name.trim(), description)
            refresh()
        }
    }

    fun rename(id: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            app.projectRepository.renameProject(id, newName)
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            app.projectRepository.deleteProject(id)
            refresh()
        }
    }
}

// ------------------------------------------------------------ Tool manager

class ToolManagerViewModel(private val app: MobileAIAgentApp) : ViewModel() {
    private val _state = MutableStateFlow<List<com.mobileaiagent.tools.spec.AgentTool>>(emptyList())
    val tools: StateFlow<List<com.mobileaiagent.tools.spec.AgentTool>> = _state.asStateFlow()

    /**
     * Settings are observed from the app graph rather than read in the composable:
     * a Screen must never reach for `applicationContext` itself, and this keeps the
     * enable/disable switch in sync with the persisted value.
     */
    val settings: StateFlow<AgentSettings> = app.settings

    init {
        viewModelScope.launch { app.providersReady(); _state.value = app.toolRegistry.all() }
    }

    fun toggle(toolId: String, enabled: Boolean) {
        viewModelScope.launch {
            app.settingsRepository.update { settings ->
                val disabled = settings.toolOverridesDisabled.toMutableSet()
                if (enabled) disabled.remove(toolId) else disabled.add(toolId)
                settings.copy(toolOverridesDisabled = disabled)
            }
            app.refreshSettings()
        }
    }
}

// ------------------------------------------------------------------ Plugins

class PluginViewModel(private val app: MobileAIAgentApp) : ViewModel() {
    val plugins: StateFlow<List<com.mobileaiagent.plugins.PluginManager.InstalledPlugin>> =
        app.pluginManager.observeInstalled()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun install(manifestJson: String) {
        viewModelScope.launch {
            app.pluginManager.installFromManifest(manifestJson)
                .onSuccess { _message.value = "Installed ${it.name} v${it.version}" }
                .onFailure { _message.value = "Install failed: ${it.message}" }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            app.pluginManager.setEnabled(id, enabled)
            _message.value = if (enabled) "Enabled $id" else "Disabled $id"
        }
    }

    fun uninstall(id: String) {
        viewModelScope.launch {
            app.pluginManager.uninstall(id)
            _message.value = "Removed $id"
        }
    }

    fun clearMessage() { _message.value = null }
}

/** Waits (bounded) for the app graph to finish async initialisation. */
private suspend fun MobileAIAgentApp.providersReady() {
    kotlinx.coroutines.withTimeoutOrNull(6_000) { initializedFlow.first { it } }
}

/** Reads a plan file without touching Compose state (used by previews). */
fun readPlanFile(root: File): Plan? = runCatching {
    val file = File(root, "Plans/active.plan.json")
    if (!file.isFile) null else Plan.fromJson(JsonParser.parse(file.readText()))
}.getOrNull()
