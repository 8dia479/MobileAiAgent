package com.mobileaiagent

import android.app.Application
import android.util.Log
import com.mobileaiagent.agent.DefaultTaskRunner
import com.mobileaiagent.agent.TaskManager
import com.mobileaiagent.agent.plan.PlanManager
import com.mobileaiagent.agent.recovery.RecoveryEngine
import com.mobileaiagent.core.log.AgentLog
import com.mobileaiagent.core.log.LogRecord
import com.mobileaiagent.core.storage.AgentStores
import com.mobileaiagent.data.AndroidResourceMonitor
import com.mobileaiagent.data.crypto.AndroidKeystoreCredentialStore
import com.mobileaiagent.data.db.AgentDatabase
import com.mobileaiagent.data.db.RoomCheckpointStore
import com.mobileaiagent.data.db.RoomEventStore
import com.mobileaiagent.data.db.RoomMessageStore
import com.mobileaiagent.data.db.RoomModelCatalog
import com.mobileaiagent.data.db.RoomRunStore
import com.mobileaiagent.data.db.RoomTaskStore
import com.mobileaiagent.data.db.RoomToolJournal
import com.mobileaiagent.data.db.FilesystemProjectRepository
import com.mobileaiagent.data.settings.SettingsRepositoryImpl
import com.mobileaiagent.domain.repo.AgentSettings
import com.mobileaiagent.domain.repo.CredentialStore
import com.mobileaiagent.domain.repo.ModelCatalogRepository
import com.mobileaiagent.domain.repo.ProjectRepository
import com.mobileaiagent.domain.repo.ProviderRepository
import com.mobileaiagent.domain.repo.ResourceMonitor
import com.mobileaiagent.domain.repo.SettingsRepository
import com.mobileaiagent.plugins.BuiltInRegistrar
import com.mobileaiagent.plugins.PluginManager
import com.mobileaiagent.providers.ProviderRepositoryImpl
import com.mobileaiagent.providers.transport.KtorHttpTransport
import com.mobileaiagent.service.AgentForegroundService
import com.mobileaiagent.service.RecoveryWorker
import com.mobileaiagent.service.WatchdogWorker
import com.mobileaiagent.tools.spec.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-scoped dependency graph.
 *
 * This object deliberately owns every long-lived component (TaskManager, the
 * runner, the tool registry, repositories). Activities and ViewModels read from
 * it; they never create or own execution state. That is what makes task execution
 * independent of the UI lifecycle (spec §1, §13).
 */
class MobileAIAgentApp : Application() {

    private val initStarted = AtomicBoolean(false)

    /** Compose-safe readiness flag: flips once the graph is fully wired. */
    private val _initialized = MutableStateFlow(false)
    val initializedFlow: StateFlow<Boolean> = _initialized
    val initialized: Boolean get() = _initialized.value

    lateinit var database: AgentDatabase
        private set
    lateinit var stores: AgentStores
        private set
    lateinit var taskManager: TaskManager
        private set
    lateinit var toolRegistry: ToolRegistry
        private set
    lateinit var planManager: PlanManager
        private set
    lateinit var providerRepository: ProviderRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var credentialStore: CredentialStore
        private set
    lateinit var projectRepository: ProjectRepository
        private set
    lateinit var modelCatalog: ModelCatalogRepository
        private set
    lateinit var resourceMonitor: ResourceMonitor
        private set
    lateinit var pluginManager: PluginManager
        private set
    lateinit var httpTransport: com.mobileaiagent.providers.transport.HttpTransport
        private set

    /** Observable settings snapshot for Compose. */
    private val _settings = MutableStateFlow(AgentSettings())
    val settings: StateFlow<AgentSettings> = _settings

    /** True while any task is actively executing; UI uses this for the indicator. */
    private val _agentActive = MutableStateFlow(false)
    val agentActive: StateFlow<Boolean> = _agentActive

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        BuildInfo.init(this)
        AgentLog.androidSink = { record -> logToLogcat(record) }
        initialize()
    }

    /**
     * Idempotent initialisation. Safe to call from the Activity, the service and
     * workers: whichever starts first builds the graph.
     */
    fun initialize() {
        if (!initStarted.compareAndSet(false, true)) return
        AgentLog.i("App", "Initialising MobileAIAgent ${BuildConfig.VERSION_NAME}")
        database = AgentDatabase.build(this)
        stores = AgentStores(
            tasks = RoomTaskStore(database.taskDao()),
            events = RoomEventStore(database.eventDao()),
            runs = RoomRunStore(database.runDao()),
            messages = RoomMessageStore(database.messageDao()),
            journal = RoomToolJournal(database.journalDao()),
            checkpoints = RoomCheckpointStore(database.checkpointDao()),
        )
        credentialStore = AndroidKeystoreCredentialStore(this)
        settingsRepository = SettingsRepositoryImpl(this)
        httpTransport = KtorHttpTransport()
        providerRepository = ProviderRepositoryImpl(settingsRepository, credentialStore, httpTransport)
        projectRepository = FilesystemProjectRepository(database.projectDao(), filesDir)
        planManager = PlanManager()
        resourceMonitor = AndroidResourceMonitor(this)
        toolRegistry = ToolRegistry()
        BuiltInRegistrar(toolRegistry, planManager, httpTransport).registerDefaults()
        modelCatalog = RoomModelCatalog(database.modelDao()) { id -> providerRepository.provider(id) }
        pluginManager = PluginManager(this, database.pluginDao(), toolRegistry)

        val runner = DefaultTaskRunner(
            stores = stores,
            providers = providerRepository,
            settingsRepo = settingsRepository,
            projects = projectRepository,
            registry = toolRegistry,
            planManager = planManager,
            taskManagerProvider = { taskManager },
            resources = resourceMonitor,
            deviceName = { "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" },
            androidRelease = { android.os.Build.VERSION.RELEASE },
            recoveryEngine = RecoveryEngine(stores),
            onStateChanged = { },
        )

        taskManager = TaskManager(
            stores = stores,
            runner = runner,
            onRunningChanged = { active ->
                _agentActive.value = active
                if (active) startForegroundServiceIfNeeded()
            },
        )

        applicationScope.launch {
            runCatching {
                ensureDefaultProject()
                // Load settings, then recover tasks and resume eligible work.
                _settings.value = settingsRepository.snapshot()
                toolRegistry.enabledIds = toolRegistry.all().map { it.id }.toSet() - _settings.value.toolOverridesDisabled
                taskManager.onProcessStart(RecoveryEngine(stores))
                pluginManager.loadInstalledPlugins()
                if (_settings.value.backgroundExecutionEnabled) {
                    WatchdogWorker.enqueuePeriodic(this@MobileAIAgentApp)
                }
                _initialized.value = true
                AgentLog.i("App", "Initialisation complete")
            }.onFailure { AgentLog.e("App", "Initialisation failed: ${it.message}") }
        }
    }

    /** Refresh the settings StateFlow (called after the user edits settings). */
    fun refreshSettings() {
        applicationScope.launch {
            val snapshot = settingsRepository.snapshot()
            _settings.value = snapshot
            toolRegistry.enabledIds = toolRegistry.all().map { it.id }.toSet() - snapshot.toolOverridesDisabled
            (providerRepository as? ProviderRepositoryImpl)?.invalidate()
        }
    }

    fun startForegroundServiceIfNeeded() {
        runCatching { AgentForegroundService.start(this) }
            .onFailure { AgentLog.w("App", "Could not start foreground service: ${it.message}") }
    }

    fun scheduleRecoveryWork() {
        RecoveryWorker.enqueue(this)
    }

    /** Runs off the main thread: touches Room and the filesystem. */
    fun ensureDefaultProject() {
        applicationScope.launch {
            try {
                if (projectRepository.listProjects().isEmpty()) {
                    val project = projectRepository.createProject("First project", "Default workspace")
                    AgentLog.i("App", "Created default project ${project.id}")
                }
            } catch (e: Exception) {
                AgentLog.e("App", "Could not create the default project", e)
            }
        }
    }

    fun filesRoot(): File = filesDir

    private fun logToLogcat(record: LogRecord) {
        when (record.level) {
            com.mobileaiagent.core.log.LogLevel.DEBUG -> Log.d(record.tag.take(23), record.message)
            com.mobileaiagent.core.log.LogLevel.INFO -> Log.i(record.tag.take(23), record.message)
            com.mobileaiagent.core.log.LogLevel.WARN -> Log.w(record.tag.take(23), record.message)
            com.mobileaiagent.core.log.LogLevel.ERROR -> Log.e(record.tag.take(23), record.message)
        }
    }
}
