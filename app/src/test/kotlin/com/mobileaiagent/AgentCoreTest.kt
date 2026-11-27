package com.mobileaiagent

import com.mobileaiagent.agent.AgentLoop
import com.mobileaiagent.agent.AgentPrompts
import com.mobileaiagent.agent.AgentSpec
import com.mobileaiagent.agent.AgentTurnObserver
import com.mobileaiagent.agent.DefaultTaskRunner
import com.mobileaiagent.agent.TaskManager
import com.mobileaiagent.agent.TaskRunner
import com.mobileaiagent.agent.RunOutcome
import com.mobileaiagent.agent.plan.PlanManager
import com.mobileaiagent.agent.recovery.RecoveryEngine
import com.mobileaiagent.core.json.JsonParser
import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.core.log.AgentLog
import com.mobileaiagent.core.storage.*
import com.mobileaiagent.core.util.CancellationToken
import com.mobileaiagent.core.util.Clock
import com.mobileaiagent.core.util.IdGenerator
import com.mobileaiagent.domain.model.*
import com.mobileaiagent.domain.repo.*
import com.mobileaiagent.providers.*
import com.mobileaiagent.providers.transport.HttpResult
import com.mobileaiagent.providers.transport.HttpTransport
import com.mobileaiagent.providers.transport.StreamingHttpResponse
import com.mobileaiagent.providers.transport.ServerSentEvent
import com.mobileaiagent.providers.impl.OpenAiCompatibleProvider
import com.mobileaiagent.service.LiveRunRegistry
import com.mobileaiagent.tools.builtin.*
import com.mobileaiagent.tools.spec.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

// ---------------------------------------------------------------- test doubles

class FakeClock(var now: Long = 1_700_000_000_000) : Clock {
    override fun nowMillis(): Long = now
}

class FakeHttpTransport : HttpTransport {
    var lastBody: JsonValue? = null
    var responses = mutableListOf<HttpResult>()
    var getResponses = mutableMapOf<String, HttpResult>()
    var streamPayloads = mutableListOf<String>() // concatenated SSE text
    var streamStatus: Int = 200

    override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long, cancellation: CancellationToken?): HttpResult =
        getResponses[url] ?: HttpResult(404, """{"error":{"message":"no stub for $url"}}""")

    override suspend fun postJson(url: String, headers: Map<String, String>, body: JsonValue, timeoutMs: Long, cancellation: CancellationToken?): HttpResult {
        lastBody = body
        return responses.removeFirstOrNull() ?: HttpResult(200, "{}")
    }

    override suspend fun postJsonStream(
        url: String, headers: Map<String, String>, body: JsonValue,
        connectTimeoutMs: Long, readTimeoutMs: Long, cancellation: CancellationToken?,
    ): StreamingHttpResponse {
        lastBody = body
        val text = streamPayloads.removeFirstOrNull() ?: ""
        return object : StreamingHttpResponse {
            override val statusCode = streamStatus
            override val errorBodyIfNotSuccess: String? = if (streamStatus !in 200..299) text else null
            private val reader = text.reader().buffered()
            override fun sseEvents(): Sequence<ServerSentEvent> = com.mobileaiagent.providers.transport.SseReader(reader)
            override fun close() {}
        }
    }
}

class FakeProvider(
    private val name: String = "fake",
    private var responses: List<ProviderTurn> = emptyList(),
) : AiProvider {
    data class ProviderTurn(
        val text: String = "",
        val toolCalls: List<ApiToolCall> = emptyList(),
        val error: String? = null,
        val finish: FinishReason = if (toolCalls.isEmpty()) FinishReason.STOP else FinishReason.TOOL_CALLS,
    )

    val requests = mutableListOf<ChatRequest>()
    private var index = 0

    override val id = name
    override val displayName = "Fake Provider"
    override val kind = ProviderKind.OPENAI_COMPATIBLE
    override val supportsModelDiscovery = false
    override val capabilities = ProviderCapabilities(toolCalling = true)
    override fun isConfigured() = true
    override suspend fun discoverModels(): ModelDiscoveryResult = ModelDiscoveryResult.Success(emptyList())

    override suspend fun streamChat(request: ChatRequest, sink: StreamSink, cancellation: CancellationToken): ChatResult {
        requests.add(request)
        val turn = responses.getOrNull(index++) ?: ProviderTurn(text = "done")
        sink.onStart()
        sink.onTextDelta(turn.text)
        if (turn.error != null) {
            sink.onError(turn.error, retryable = true)
            return ChatResult("", null, emptyList(), FinishReason.ERROR, Usage(), turn.error)
        }
        sink.onUsage(Usage(100, 50, 150))
        sink.onDone()
        return ChatResult(turn.text, null, turn.toolCalls, turn.finish, Usage(100, 50, 150))
    }
}

class FakeProviders(private var provider: AiProvider?) : ProviderRepository {
    override suspend fun providers(): Map<String, AiProvider> = provider?.let { mapOf(it.id to it) } ?: emptyMap()
    override suspend fun provider(id: String): AiProvider? = provider
    override suspend fun activeProviderId(): String = provider?.id ?: "fake"
    override fun defaultBaseUrl(providerKindId: String): String = ""
    fun set(p: AiProvider?) { provider = p }
}

class FakeProjects(private val root: File) : ProjectRepository {
    override fun projectsRoot(): File = root
    override fun projectDir(projectId: String): File = File(root, projectId)
    override suspend fun createProject(name: String, description: String?): ProjectInfo =
        ProjectInfo("p1", name, description, root.absolutePath, 0, 0)
    override suspend fun listProjects(): List<ProjectInfo> =
        listOf(ProjectInfo("p1", "Test", null, root.absolutePath, 0, 0))
    override suspend fun renameProject(projectId: String, newName: String): Boolean = true
    override suspend fun deleteProject(projectId: String): Boolean = true
    override suspend fun projectInfo(projectId: String): ProjectInfo =
        ProjectInfo("p1", "Test", null, root.absolutePath, 0, 0)
}

class FakeResources(private val network: Boolean = true) : ResourceMonitor {
    override fun isNetworkAvailable() = network
    override fun isUnmeteredNetwork() = true
    override fun isBatteryLow() = false
    override fun isPowerSaving() = false
    override fun availableDiskBytes() = 1_000_000_000L
}

class ScriptedRunner(private val outcomeFor: (TaskRecord) -> RunOutcome) : TaskRunner {
    val paused = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    override suspend fun run(task: TaskRecord, cancellation: CancellationToken, live: LiveRunRegistry): RunOutcome {
        live.register(task.id, "run_test", cancellation)
        try {
            // Simulate work that obeys cancellation.
            repeat(20) {
                if (cancellation.isCancelled) return RunOutcome(RunOutcome.Kind.CANCELLED)
                Thread.sleep(5)
            }
            return outcomeFor(task)
        } finally {
            live.unregister("run_test")
        }
    }
    override fun requestPause(taskId: String) { paused.add(taskId) }
    override fun requestCancel(taskId: String) { cancelled.add(taskId) }
}

private fun testSettings() = AgentSettings(
    allowShell = true,
    internetAccess = true,
    mainModel = "fake-model",
    maxToolIterations = 5,
)

private class StaticSettings(private val value: AgentSettings) : SettingsRepository {
    override suspend fun snapshot() = value
    override suspend fun update(transform: (AgentSettings) -> AgentSettings) {}
}

// -------------------------------------------------------------------- helpers

fun makeTask(
    id: String = "task_1",
    status: TaskStatus = TaskStatus.QUEUED,
    mode: ExecutionMode = ExecutionMode.BUILD,
    autoResume: Boolean = true,
    request: String = "do the thing",
): TaskRecord = TaskRecord(
    id = id, projectId = "p1", projectName = "Test", title = "Test task", request = request,
    mode = mode, mainModel = "fake-model", mainProvider = "fake",
    mainReasoningEffort = ReasoningEffort.MEDIUM, secondaryModel = null, secondaryProvider = null,
    secondaryReasoningEffort = null, status = status, planId = null, activeRunId = null,
    lastError = null, errorKind = null, attemptCount = 0, autoResumeOnBoot = autoResume,
    createdAt = 0, updatedAt = 0, startedAt = null, finishedAt = null,
    tokensIn = 0, tokensOut = 0, costEstimate = 0.0,
)

// ------------------------------------------------------------- recovery tests

class RecoveryEngineTest {
    private lateinit var stores: AgentStores

    @Before fun setUp() {
        stores = AgentStores.inMemory()
    }

    @Test fun interruptedTasksAreNotSilentlyMarkedCompleted() = runBlocking {
        stores.tasks.upsert(makeTask(id = "t1", status = TaskStatus.RUNNING))
        val report = RecoveryEngine(stores).recover(aliveRunIds = emptySet())
        val task = stores.tasks.get("t1")!!
        Assert.assertEquals(TaskStatus.INTERRUPTED, task.status)
        Assert.assertEquals(1, report.interrupted.size)
        Assert.assertTrue(task.id in report.resumable)
    }

    @Test fun manualResumeTasksNeedAttentionInsteadOfAutoResume() = runBlocking {
        stores.tasks.upsert(makeTask(id = "t2", status = TaskStatus.RUNNING, autoResume = false))
        val report = RecoveryEngine(stores).recover(emptySet())
        Assert.assertTrue(report.resumable.isEmpty())
        Assert.assertEquals(1, report.tasksNeedingAttention.size)
    }

    @Test fun planModeTasksAreNotAutoResumed() = runBlocking {
        stores.tasks.upsert(makeTask(id = "t3", status = TaskStatus.RUNNING, mode = ExecutionMode.PLAN))
        val report = RecoveryEngine(stores).recover(emptySet())
        Assert.assertTrue(report.resumable.isEmpty())
    }

    @Test fun orphanedRunsAreClosed() = runBlocking {
        stores.runs.start(
            RunRecord("r1", "t4", RunPhase.AGENT_LOOP, 0, null, null, 3, "step", null, null)
        )
        val report = RecoveryEngine(stores).recover(emptySet())
        Assert.assertEquals(listOf("r1"), report.orphanedRuns)
        val run = stores.runs.get("r1")!!
        Assert.assertNotNull(run.endedAt)
        Assert.assertEquals("process_terminated", run.exitReason)
    }

    @Test fun liveRunsAreNotTreatedAsOrphaned() = runBlocking {
        stores.runs.start(RunRecord("r2", "t5", RunPhase.AGENT_LOOP, 0, null, null, 1, null, null, null))
        val report = RecoveryEngine(stores).recover(aliveRunIds = setOf("r2"))
        Assert.assertTrue(report.orphanedRuns.isEmpty())
    }

    @Test fun pendingMutagenicOperationsAreReportedNotReplayed() = runBlocking {
        stores.tasks.upsert(makeTask(id = "t6", status = TaskStatus.RUNNING))
        stores.journal.started("t6", "r6", "call-1", "file_write", """{"path":"a.txt"}""", mutagenic = true)
        stores.journal.started("t6", "r6", "call-2", "file_list", """{}""", mutagenic = false)
        val report = RecoveryEngine(stores).recover(emptySet())
        Assert.assertTrue(report.notes.any { it.contains("file_write") })
        val pending = stores.journal.pendingMutagenic("t6")
        Assert.assertEquals(1, pending.size)
        Assert.assertEquals("file_write", pending[0].toolId)
    }

    @Test fun resumeContextListsCompletedWork() = runBlocking {
        stores.journal.started("t7", "r7", "c1", "file_write", "{}", mutagenic = true)
        stores.journal.finished("c1", "succeeded", "created a.txt")
        stores.checkpoints.save(TaskCheckpoint("t7", 0, "T1", "wrote a.txt", null, 1))
        val ctx = RecoveryEngine(stores).resumeContext("t7")
        Assert.assertTrue(ctx.completedSummary.contains("file_write"))
        Assert.assertNotNull(ctx.checkpoint)
        Assert.assertTrue(ctx.pendingOperations.isEmpty())
    }
}

// -------------------------------------------------------------- plan manager

class PlanManagerTest {
    private lateinit var root: File

    @Before fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "mobileaiagent-plan-test-${System.nanoTime()}")
        root.mkdirs()
        PlanManager().apply { }
    }

    private fun modelPlan(vararg taskTitles: String): JsonValue = JsonValue.obj(
        "title" to JsonValue.of("Build a stand"),
        "goal" to JsonValue.of("A printable phone stand"),
        "tasks" to JsonValue.Arr(
            taskTitles.mapIndexed { index, title ->
                JsonValue.obj(
                    "id" to JsonValue.of("T${index + 1}"),
                    "title" to JsonValue.of(title),
                    "description" to JsonValue.of("Do $title"),
                    "status" to JsonValue.of("pending"),
                )
            }
        ),
    )

    @Test fun createsPlanFileOnDisk() {
        val manager = PlanManager()
        val plan = manager.createFromModelJson(root, "p1", "task1", modelPlan("Sketch", "Model", "Export"))
        val file = File(root, "Plans/active.plan.json")
        Assert.assertTrue("plan file must exist", file.isFile)
        Assert.assertEquals("Build a stand", plan.title)
        Assert.assertEquals(3, plan.tasks.size)
        val reread = manager.readActive(root)!!
        Assert.assertEquals(3, reread.tasks.size)
        Assert.assertEquals(PlanStatus.ACTIVE, reread.status)
    }

    @Test fun modelCannotSelfMarkCompletedAtCreation() {
        val manager = PlanManager()
        val payload = JsonValue.obj(
            "title" to JsonValue.of("x"),
            "goal" to JsonValue.of("y"),
            "tasks" to JsonValue.Arr(
                listOf(
                    JsonValue.obj(
                        "id" to JsonValue.of("T1"),
                        "title" to JsonValue.of("task"),
                        "description" to JsonValue.of(""),
                        "status" to JsonValue.of("completed"),
                    )
                )
            ),
        )
        val plan = manager.createFromModelJson(root, "p1", null, payload)
        Assert.assertEquals(PlanTaskStatus.PENDING, plan.tasks[0].status)
    }

    @Test fun archivedRevisionIsPreserved() {
        val manager = PlanManager()
        val first = manager.createFromModelJson(root, "p1", null, modelPlan("A", "B"))
        val second = manager.createFromModelJson(root, "p1", null, modelPlan("A", "B", "C"), previous = first)
        Assert.assertTrue(second.revision > first.revision)
        val history = File(root, "Plans/history").listFiles() ?: emptyArray()
        Assert.assertEquals(1, history.size)
        Assert.assertNotNull(manager.readActive(root))
    }

    @Test fun updateTaskStatusOnlyCompletesWithEvidenceAndUpdatesPlanState() {
        val manager = PlanManager()
        manager.createFromModelJson(root, "p1", null, modelPlan("A", "B"))
        val updated = manager.updateTaskStatus(root, "T1", PlanTaskStatus.COMPLETED, result = "verified output")!!
        Assert.assertEquals(PlanTaskStatus.COMPLETED, updated.task("T1")!!.status)
        Assert.assertEquals(1, updated.completedCount)
        val allDone = manager.updateTaskStatus(root, "T2", PlanTaskStatus.COMPLETED, result = "done")!!
        Assert.assertEquals(PlanStatus.COMPLETED, allDone.status)
    }

    @Test fun replanPreservesCompletedTasks() {
        val manager = PlanManager()
        manager.createFromModelJson(root, "p1", null, modelPlan("Sketch", "Model", "Export"))
        manager.updateTaskStatus(root, "T1", PlanTaskStatus.COMPLETED, result = "sketch verified")
        val replanned = manager.replan(root, "p1", null, modelPlan("Sketch", "New Model", "Export"), manager.readActive(root))
        Assert.assertEquals(PlanTaskStatus.COMPLETED, replanned.task("T1")!!.status,)
        Assert.assertEquals(PlanTaskStatus.PENDING, replanned.task("T2")!!.status)
        Assert.assertTrue(replanned.revision > 1)
    }

    @Test fun unreadablePlanFileIsReportedNotCrashing() {
        val manager = PlanManager()
        File(root, "Plans").mkdirs()
        File(root, "Plans/active.plan.json").writeText("{not json")
        Assert.assertNull(manager.readActive(root))
    }
}

// ---------------------------------------------------------------- file tools

class FileToolsTest {
    private lateinit var root: File
    private lateinit var ctx: ToolContext

    private fun context(f: File, settings: ToolRuntimeSettings = ToolRuntimeSettings(allowShell = true)): ToolContext {
        val ws = FileWorkspace(f)
        return ToolContext(
            taskId = "t", runId = "r", projectId = "p", projectRoot = f, workspace = ws,
            cancellation = CancellationToken(), log = {}, platform = ToolPlatform.JVM_TEST,
            settingsSnapshot = settings,
        )
    }

    @Before fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "mobileaiagent-file-test-${System.nanoTime()}")
        root.mkdirs()
        ctx = context(root)
    }

    private fun runBlockingTool(tool: AgentTool, args: JsonValue, context: ToolContext = ctx): ToolResult =
        runBlocking { tool.execute(args, context) }

    @Test fun writeThenReadRoundTrips() {
        val write = runBlockingTool(FileWriteTool(), JsonValue.obj("path" to JsonValue.of("src/hello.kt"), "content" to JsonValue.of("fun main() {}")))
        Assert.assertTrue(write is ToolResult.Success)
        val read = runBlockingTool(FileReadTool(), JsonValue.obj("path" to JsonValue.of("src/hello.kt")))
        Assert.assertTrue((read as ToolResult.Success).content.contains("fun main()"))
    }

    @Test fun pathTraversalIsRejected() {
        val attempt = runBlockingTool(FileWriteTool(), JsonValue.obj("path" to JsonValue.of("../escape.txt"), "content" to JsonValue.of("x")))
        Assert.assertTrue(attempt is ToolResult.Failure)
        Assert.assertFalse(File(root.parentFile, "escape.txt").exists())
    }

    @Test fun absolutePathOutsideWorkspaceRejected() {
        val attempt = runBlockingTool(
            FileReadTool(),
            JsonValue.obj("path" to JsonValue.of("/etc/passwd")),
        )
        Assert.assertTrue(attempt is ToolResult.Failure)
    }

    @Test fun fileListShowsWrittenFiles() {
        runBlockingTool(FileWriteTool(), JsonValue.obj("path" to JsonValue.of("a.txt"), "content" to JsonValue.of("1")))
        runBlockingTool(FileWriteTool(), JsonValue.obj("path" to JsonValue.of("b.txt"), "content" to JsonValue.of("22")))
        val list = runBlockingTool(FileListTool(), JsonValue.obj("path" to JsonValue.of("")))
        val text = (list as ToolResult.Success).content
        Assert.assertTrue(text.contains("a.txt"))
        Assert.assertTrue(text.contains("b.txt"))
    }

    @Test fun searchFindsContentWithLineNumbers() {
        runBlockingTool(FileWriteTool(), JsonValue.obj("path" to JsonValue.of("s.txt"), "content" to JsonValue.of("alpha\nbeta needle\ngamma")))
        val result = runBlockingTool(FileSearchTool(), JsonValue.obj("query" to JsonValue.of("needle")))
        val text = (result as ToolResult.Success).content
        Assert.assertTrue(text.contains("s.txt:2"))
    }

    @Test fun deleteWithoutRecursiveFailsOnFolders() {
        runBlockingTool(FileWriteTool(), JsonValue.obj("path" to JsonValue.of("dir/x.txt"), "content" to JsonValue.of("y")))
        val result = runBlockingTool(FileDeleteTool(), JsonValue.obj("path" to JsonValue.of("dir")))
        Assert.assertTrue("expected structured failure, got $result", result is ToolResult.Failure)
        Assert.assertTrue(File(root, "dir/x.txt").exists())
    }

    @Test fun appendCreatesAndExtends() {
        runBlockingTool(FileAppendTool(), JsonValue.obj("path" to JsonValue.of("log.txt"), "content" to JsonValue.of("one\n")))
        runBlockingTool(FileAppendTool(), JsonValue.obj("path" to JsonValue.of("log.txt"), "content" to JsonValue.of("two\n")))
        Assert.assertEquals("one\ntwo\n", File(root, "log.txt").readText())
    }

    @Test fun terminalIsDisabledUnlessEnabled() {
        val disabled = context(root, ToolRuntimeSettings(allowShell = false))
        val result = runBlockingTool(TerminalTool(), JsonValue.obj("command" to JsonValue.of("echo hi")), disabled)
        Assert.assertTrue(result is ToolResult.Failure)
        Assert.assertTrue((result as ToolResult.Failure).message.contains("disabled"))
    }

    @Test fun terminalRejectsChainedCommands() {
        val result = runBlockingTool(TerminalTool(), JsonValue.obj("command" to JsonValue.of("echo a ; rm -rf data")))
        Assert.assertTrue(result is ToolResult.Failure)
        Assert.assertTrue((result as ToolResult.Failure).message.contains("chaining"))
    }

    @Test fun terminalRejectsNonWhitelistedBinary() {
        val result = runBlockingTool(
            TerminalTool(),
            JsonValue.obj("command" to JsonValue.of("curl http://evil")),
            context(root, ToolRuntimeSettings(allowShell = true, shellWhitelist = setOf("ls", "echo", "cat"))),
        )
        Assert.assertTrue(result is ToolResult.Failure)
    }
}
