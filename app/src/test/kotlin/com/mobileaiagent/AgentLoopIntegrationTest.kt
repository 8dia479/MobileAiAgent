package com.mobileaiagent

import com.mobileaiagent.agent.AgentLoop
import com.mobileaiagent.agent.AgentSpec
import com.mobileaiagent.agent.AgentTurnObserver
import com.mobileaiagent.agent.DefaultTaskRunner
import com.mobileaiagent.agent.TaskManager
import com.mobileaiagent.agent.RunOutcome
import com.mobileaiagent.agent.plan.PlanManager
import com.mobileaiagent.agent.recovery.RecoveryEngine
import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.core.storage.AgentStores
import com.mobileaiagent.core.util.CancellationToken
import com.mobileaiagent.domain.model.*
import com.mobileaiagent.domain.repo.AgentSettings
import com.mobileaiagent.domain.repo.SettingsRepository
import com.mobileaiagent.plugins.BuiltInRegistrar
import com.mobileaiagent.providers.ApiToolCall
import com.mobileaiagent.providers.FinishReason
import com.mobileaiagent.service.LiveRunRegistry
import com.mobileaiagent.tools.spec.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * End-to-end exercise of the real ReAct loop with a scripted provider:
 * model asks for a tool -> tool really runs against the filesystem -> result is
 * fed back -> model answers. No fake tool results anywhere.
 */
class AgentLoopIntegrationTest {

    private lateinit var root: File
    private lateinit var registry: ToolRegistry
    private lateinit var planManager: PlanManager

    @Before fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "mobileaiagent-loop-${System.nanoTime()}")
        root.mkdirs()
        registry = ToolRegistry()
        planManager = PlanManager()
        BuiltInRegistrar(registry, planManager, FakeHttpTransport()).registerDefaults()
    }

    private fun spec(mode: ExecutionMode = ExecutionMode.BUILD) = AgentSpec(
        taskId = "task_1", runId = "run_1", projectId = "p1", projectName = "Test",
        mode = mode, reasoningEffort = ReasoningEffort.MEDIUM,
        secondaryModel = null, secondaryReasoningEffort = null,
        toolManifest = registry.describeEnabled(), plan = null,
    )

    private fun ctx(cancellation: CancellationToken = CancellationToken()) = com.mobileaiagent.tools.spec.ToolContext(
        taskId = "task_1", runId = "run_1", projectId = "p1", projectRoot = root,
        workspace = com.mobileaiagent.tools.builtin.FileWorkspace(root),
        cancellation = cancellation, log = {},
    )

    private fun observer(record: MutableList<String>) = object : AgentTurnObserver {
        override fun onTextDelta(text: String) { record.add("text:$text") }
        override suspend fun onToolStarted(call: ApiToolCall, tool: com.mobileaiagent.tools.spec.AgentTool?) {
            record.add("start:${call.name}")
        }
        override suspend fun onToolFinished(
            call: ApiToolCall,
            tool: com.mobileaiagent.tools.spec.AgentTool?,
            result: com.mobileaiagent.tools.spec.ToolResult,
            durationMs: Long,
        ) {
            record.add("done:${call.name}:${result::class.simpleName}")
        }
    }

    @Test fun modelToolCallReallyExecutesAndResultIsReturned() = runBlocking {
        val provider = FakeProvider(
            responses = listOf(
                FakeProvider.ProviderTurn(
                    text = "Writing the file now.",
                    toolCalls = listOf(
                        ApiToolCall(
                            "call_1", "file_write",
                            JsonValue.obj(
                                "path" to JsonValue.of("notes/plan.md"),
                                "content" to JsonValue.of("# Plan\n- step 1\n"),
                            ).toJson(),
                        )
                    ),
                ),
                FakeProvider.ProviderTurn(text = "Created notes/plan.md"),
            )
        )
        val loop = AgentLoop(provider, registry, spec(), "fake-model")
        val events = mutableListOf<String>()

        val first = loop.runTurn(
            listOf(ChatMessage("m1", ChatMessage.Role.USER, "write a plan file", 0)),
            ctx(), observer(events),
        )
        Assert.assertEquals("Writing the file now.", first.assistantText)
        Assert.assertEquals(1, first.toolCalls.size)
        Assert.assertTrue("tool must succeed", first.toolCalls[0].result is com.mobileaiagent.tools.spec.ToolResult.Success)
        Assert.assertTrue("file must exist on disk", File(root, "notes/plan.md").isFile)
        Assert.assertEquals("# Plan\n- step 1\n", File(root, "notes/plan.md").readText())
        Assert.assertTrue(events.contains("start:file_write"))

        val second = loop.runTurn(
            listOf(
                ChatMessage("m1", ChatMessage.Role.USER, "write a plan file", 0),
                ChatMessage("m2", ChatMessage.Role.ASSISTANT, first.assistantText, 1,
                    toolCallsJson = ApiConversions.encodeToolCalls(first.toolCalls.map { it.call })),
                ChatMessage("m3", ChatMessage.Role.TOOL, first.toolCalls[0].result.render(), 2, toolCallId = "call_1", toolName = "file_write"),
            ),
            ctx(), observer(events),
        )
        Assert.assertEquals("Created notes/plan.md", second.assistantText)
        Assert.assertTrue(second.toolCalls.isEmpty())
        // The second request must have carried the tool result back to the model.
        val lastRequest = provider.requests.last()
        val toolMessage = lastRequest.messages.last { it.role == "tool" }
        Assert.assertEquals("call_1", toolMessage.toolCallId)
        Assert.assertTrue(toolMessage.content.contains("notes/plan.md"))
    }

    @Test fun planGenerationWritesStructuredJsonOnDisk() = runBlocking {
        val planJson = JsonValue.obj(
            "title" to JsonValue.of("Create a bracket"),
            "goal" to JsonValue.of("A printable L bracket"),
            "tasks" to JsonValue.Arr(
                listOf(
                    JsonValue.obj(
                        "id" to JsonValue.of("T1"),
                        "title" to JsonValue.of("Design outline"),
                        "description" to JsonValue.of("2D profile"),
                        "status" to JsonValue.of("pending"),
                    ),
                    JsonValue.obj(
                        "id" to JsonValue.of("T2"),
                        "title" to JsonValue.of("Extrude and export"),
                        "description" to JsonValue.of("STL export"),
                        "status" to JsonValue.of("pending"),
                    ),
                )
            ),
        )
        val provider = FakeProvider(
            responses = listOf(
                FakeProvider.ProviderTurn(
                    text = "Planning…",
                    toolCalls = listOf(ApiToolCall("p1", "plan_generate", planJson.toJson())),
                ),
                FakeProvider.ProviderTurn(text = "Plan ready."),
            )
        )
        val loop = AgentLoop(provider, registry, spec(ExecutionMode.PLAN_BUILD), "fake-model")
        val history = mutableListOf(ChatMessage("m1", ChatMessage.Role.USER, "make a bracket", 0))
        var guard = 0
        while (guard++ < 5) {
            val outcome = loop.runTurn(history, ctx(), observer(mutableListOf()))
            history.add(
                ChatMessage("a$guard", ChatMessage.Role.ASSISTANT, outcome.assistantText, guard.toLong(),
                    toolCallsJson = outcome.toolCalls.takeIf { it.isNotEmpty() }?.let {
                        ApiConversions.encodeToolCalls(it.map { r -> r.call })
                    })
            )
            outcome.toolCalls.forEach { r ->
                history.add(
                    ChatMessage("t$guard", ChatMessage.Role.TOOL, r.result.render(), guard.toLong(),
                        toolCallId = r.call.id, toolName = r.call.name)
                )
            }
            if (!outcome.hadToolCalls) break
        }
        val planFile = File(root, "Plans/active.plan.json")
        Assert.assertTrue("plan JSON must be written", planFile.isFile)
        val plan = planManager.readActive(root)!!
        Assert.assertEquals("Create a bracket", plan.title)
        Assert.assertEquals(2, plan.tasks.size)
        Assert.assertEquals(PlanTaskStatus.PENDING, plan.tasks[0].status)
        // A model-reachable status change without evidence must be rejected.
        val reject = com.mobileaiagent.tools.builtin.PlanUpdateTaskTool(planManager).execute(
            JsonValue.obj("task_id" to JsonValue.of("T1"), "status" to JsonValue.of("completed")),
            ctx(),
        )
        Assert.assertTrue(reject is com.mobileaiagent.tools.spec.ToolResult.Failure)
        Assert.assertEquals(
            "verification_required",
            (reject as com.mobileaiagent.tools.spec.ToolResult.Failure).errorKind,
        )
    }

    @Test fun unknownToolProducesStructuredErrorsThatTheModelCanRead() = runBlocking {
        val provider = FakeProvider(
            responses = listOf(
                FakeProvider.ProviderTurn(
                    text = "",
                    toolCalls = listOf(ApiToolCall("x1", "does_not_exist", "{}")),
                )
            )
        )
        val outcome = AgentLoop(provider, registry, spec(), "fake-model")
            .runTurn(listOf(ChatMessage("m1", ChatMessage.Role.USER, "hi", 0)), ctx(), observer(mutableListOf()))
        val result = outcome.toolCalls.single().result
        Assert.assertTrue(result is com.mobileaiagent.tools.spec.ToolResult.Failure)
        Assert.assertTrue((result as com.mobileaiagent.tools.spec.ToolResult.Failure).message.contains("Unknown tool"))
    }

    @Test fun malformedToolArgumentsAreRejectedWithoutExecuting() = runBlocking {
        val provider = FakeProvider(
            responses = listOf(
                FakeProvider.ProviderTurn(
                    text = "",
                    toolCalls = listOf(ApiToolCall("bad", "file_write", "{not json")),
                )
            )
        )
        val outcome = AgentLoop(provider, registry, spec(), "fake-model")
            .runTurn(listOf(ChatMessage("m1", ChatMessage.Role.USER, "hi", 0)), ctx(), observer(mutableListOf()))
        val failure = outcome.toolCalls.single().result as com.mobileaiagent.tools.spec.ToolResult.Failure
        Assert.assertEquals("invalid_json", failure.errorKind)
        Assert.assertFalse(File(root, "notes").exists())
    }

    @Test fun planModeRegistryHidesMutatingTools() {
        val allowed = com.mobileaiagent.agent.ToolFilters.forMode(ExecutionMode.PLAN)!!
        Assert.assertTrue("file_write" !in allowed)
        Assert.assertTrue("terminal" !in allowed)
        Assert.assertTrue("file_read" in allowed)
        Assert.assertTrue("plan_generate" in allowed)
        Assert.assertNull(com.mobileaiagent.agent.ToolFilters.forMode(ExecutionMode.BUILD))
    }

    @Test fun promptsIncludePlanChecklistAndToolManifest() {
        val plan = planManager.createFromModelJson(
            root, "p1", "t1",
            JsonValue.obj(
                "title" to JsonValue.of("Ship feature"),
                "goal" to JsonValue.of("working code"),
                "tasks" to JsonValue.Arr(
                    listOf(
                        JsonValue.obj(
                            "id" to JsonValue.of("T1"), "title" to JsonValue.of("Write code"),
                            "description" to JsonValue.of(""), "status" to JsonValue.of("pending"),
                        )
                    )
                ),
            ),
        )
        val prompt = com.mobileaiagent.agent.AgentPrompts.systemPrompt(spec().copy(plan = plan))
        Assert.assertTrue(prompt.contains("## Active plan"))
        Assert.assertTrue(prompt.contains("T1: Write code"))
        Assert.assertTrue(prompt.contains("## Tools"))
        Assert.assertTrue(prompt.contains("file_write"))
        Assert.assertTrue(prompt.contains("Execution mode: BUILD"))
    }
}

/**
 * Full TaskManager + DefaultTaskRunner path: proves a task created through the
 * manager actually executes tools, journals them, persists messages and reaches
 * COMPLETED — all without any UI.
 */
class TaskExecutionTest {
    private lateinit var root: File
    private lateinit var stores: AgentStores

    @Before fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "mobileaiagent-task-${System.nanoTime()}")
        root.mkdirs()
        stores = AgentStores.inMemory()
    }

    @Test fun taskRunsToCompletionAndJournalsTools() = runBlocking {
        val registry = ToolRegistry()
        val planManager = PlanManager()
        BuiltInRegistrar(registry, planManager, FakeHttpTransport()).registerDefaults()
        val provider = FakeProvider(
            responses = listOf(
                FakeProvider.ProviderTurn(
                    text = "creating",
                    toolCalls = listOf(
                        ApiToolCall(
                            "c1", "file_write",
                            JsonValue.obj(
                                "path" to JsonValue.of("out/hello.txt"),
                                "content" to JsonValue.of("hello world"),
                            ).toJson(),
                        )
                    ),
                ),
                FakeProvider.ProviderTurn(text = "All done: out/hello.txt created."),
            )
        )
        val settings = AgentSettings(allowShell = false, mainModel = "fake-model", maxToolIterations = 6)
        val runner = DefaultTaskRunner(
            stores = stores,
            providers = FakeProviders(provider),
            settingsRepo = StaticSettings2(settings),
            projects = FakeProjects(root),
            registry = registry,
            planManager = planManager,
            taskManagerProvider = { managerRef },
            resources = FakeResources(),
            recoveryEngine = RecoveryEngine(stores),
        )
        managerRef = TaskManager(
            stores = stores,
            runner = runner,
            onRunningChanged = {},
        )
        stores.tasks.upsert(makeTask(id = "tx", status = TaskStatus.QUEUED))
        val outcome = runner.run(stores.tasks.get("tx")!!, CancellationToken(), LiveRunRegistry())
        Assert.assertEquals(RunOutcome.Kind.COMPLETED, outcome.kind)

        // The runner deliberately does NOT own terminal status: TaskManager does,
        // so a crashed process never leaves a fake COMPLETED record behind.
        val task = stores.tasks.get("tx")!!
        Assert.assertEquals(TaskStatus.QUEUED, task.status)
        Assert.assertTrue("file must exist", File(root, "out/hello.txt").isFile)
        Assert.assertEquals("hello world", File(root, "out/hello.txt").readText())

        val journal = stores.journal.list("tx", 50)
        Assert.assertEquals(1, journal.size)
        Assert.assertEquals("file_write", journal[0].toolId)
        Assert.assertEquals("succeeded", journal[0].status)
        Assert.assertTrue("mutagenic flags drive recovery", journal[0].mutagenic)

        val messages = stores.messages.list("tx", 50)
        Assert.assertTrue(messages.any { it.role == ChatMessage.Role.ASSISTANT && it.content.contains("All done") })
        Assert.assertTrue(messages.any { it.role == ChatMessage.Role.TOOL && it.toolName == "file_write" })
        Assert.assertTrue(provider.requests.size >= 2)
        // Token accounting is persisted on the task.
        Assert.assertTrue(stores.tasks.get("tx")!!.tokensIn > 0)
    }

    @Test fun taskFailsCleanlyWithoutProvider() = runBlocking {
        val registry = ToolRegistry()
        val runner = DefaultTaskRunner(
            stores = stores,
            providers = FakeProviders(null),
            settingsRepo = StaticSettings2(AgentSettings()),
            projects = FakeProjects(root),
            registry = registry,
            planManager = PlanManager(),
            taskManagerProvider = { null },
            resources = FakeResources(),
        )
        val outcome = runner.run(makeTask(id = "nf"), CancellationToken(), LiveRunRegistry())
        Assert.assertEquals(RunOutcome.Kind.FAILED, outcome.kind)
        Assert.assertTrue(outcome.message!!.contains("provider", ignoreCase = true))
        Assert.assertEquals(ErrorKind.AUTH, outcome.errorKind)
    }

    @Test fun networkLossDefersTaskInsteadOfFailing() = runBlocking {
        val registry = ToolRegistry()
        val runner = DefaultTaskRunner(
            stores = stores,
            providers = FakeProviders(FakeProvider()),
            settingsRepo = StaticSettings2(AgentSettings()),
            projects = FakeProjects(root),
            registry = registry,
            planManager = PlanManager(),
            taskManagerProvider = { null },
            resources = FakeResources(network = false),
        )
        val outcome = runner.run(makeTask(id = "nn"), CancellationToken(), LiveRunRegistry())
        Assert.assertEquals(RunOutcome.Kind.WAITING_NETWORK, outcome.kind)
        Assert.assertEquals(ErrorKind.NETWORK, outcome.errorKind)
    }

    @Test fun cancellationStopsTheLoopAndIsReported() = runBlocking {
        val registry = ToolRegistry()
        val cancellation = CancellationToken()
        val slowProvider = object : com.mobileaiagent.providers.AiProvider {
            override val id = "slow"
            override val displayName = "Slow"
            override val kind = com.mobileaiagent.providers.ProviderKind.OPENAI_COMPATIBLE
            override val supportsModelDiscovery = false
            override val capabilities = com.mobileaiagent.providers.ProviderCapabilities()
            override fun isConfigured() = true
            override suspend fun discoverModels() = com.mobileaiagent.providers.ModelDiscoveryResult.Success(emptyList())
            override suspend fun streamChat(
                request: com.mobileaiagent.providers.ChatRequest,
                sink: com.mobileaiagent.providers.StreamSink,
                cancellation: CancellationToken,
            ): com.mobileaiagent.providers.ChatResult {
                cancellation.cancel()
                return com.mobileaiagent.providers.ChatResult(
                    "", null, emptyList(), FinishReason.CANCELLED, com.mobileaiagent.providers.Usage(),
                )
            }
        }
        val runner = DefaultTaskRunner(
            stores = stores,
            providers = FakeProviders(slowProvider),
            settingsRepo = StaticSettings2(AgentSettings()),
            projects = FakeProjects(root),
            registry = registry,
            planManager = PlanManager(),
            taskManagerProvider = { null },
            resources = FakeResources(),
        )
        val outcome = runner.run(makeTask(id = "c1"), cancellation, LiveRunRegistry())
        Assert.assertEquals(RunOutcome.Kind.CANCELLED, outcome.kind)
        Assert.assertEquals(ErrorKind.CANCELLED, outcome.errorKind)
    }

    private companion object {
        var managerRef: TaskManager? = null
    }
}

private class StaticSettings2(private val value: AgentSettings) : SettingsRepository {
    override suspend fun snapshot() = value
    override suspend fun update(transform: (AgentSettings) -> AgentSettings) {}
}

/**
 * testConnection used to be a stub that always returned ok = true, which made
 * the "Test connection" button meaningless. The default now derives its verdict
 * from discoverModels, so an expired key is reported as a failure.
 */
class ProviderConnectionTest {
    private fun provider(discovery: suspend () -> com.mobileaiagent.providers.ModelDiscoveryResult) =
        object : com.mobileaiagent.providers.AiProvider {
            override val id = "p"
            override val displayName = "P"
            override val kind = com.mobileaiagent.providers.ProviderKind.OPENAI_COMPATIBLE
            override val supportsModelDiscovery = true
            override val capabilities = com.mobileaiagent.providers.ProviderCapabilities()
            override fun isConfigured() = true
            override suspend fun discoverModels() = discovery()
            override suspend fun streamChat(
                request: com.mobileaiagent.providers.ChatRequest,
                sink: com.mobileaiagent.providers.StreamSink,
                cancellation: CancellationToken,
            ) = com.mobileaiagent.providers.ChatResult(
                "", null, emptyList(), FinishReason.STOP, com.mobileaiagent.providers.Usage(),
            )
        }

    @Test fun reportsSuccessAndTheModelCount() {
        val p = provider {
            com.mobileaiagent.providers.ModelDiscoveryResult.Success(
                listOf(
                    com.mobileaiagent.providers.ModelDescriptor(
                        id = "m1", displayName = "M1", providerId = "p",
                        contextWindow = null, maxOutputTokens = null,
                        capabilities = com.mobileaiagent.providers.ModelCapabilities(),
                        capabilitiesSource = com.mobileaiagent.providers.CapabilitySource.INFERRED,
                        raw = null,
                    ),
                ),
            )
        }
        val r = kotlinx.coroutines.runBlocking { p.testConnection() }
        Assert.assertTrue(r.ok)
        Assert.assertTrue("model count missing: ${r.message}", r.message.contains("1 model"))
    }

    @Test fun reportsAuthFailuresAsCredentialProblems() {
        val p = provider {
            com.mobileaiagent.providers.ModelDiscoveryResult.Failure("HTTP 401", authFailure = true)
        }
        val r = kotlinx.coroutines.runBlocking { p.testConnection() }
        Assert.assertFalse(r.ok)
        Assert.assertTrue("should say credentials: ${r.message}", r.message.contains("credentials"))
    }

    @Test fun reportsNetworkFailuresAsReachabilityProblems() {
        val p = provider {
            com.mobileaiagent.providers.ModelDiscoveryResult.Failure("timeout")
        }
        val r = kotlinx.coroutines.runBlocking { p.testConnection() }
        Assert.assertFalse(r.ok)
        Assert.assertTrue("should say reach: ${r.message}", r.message.contains("reach"))
    }

    @Test fun anExceptionIsAFailureNotACrash() {
        val p = provider { throw IllegalStateException("boom") }
        val r = kotlinx.coroutines.runBlocking { p.testConnection() }
        Assert.assertFalse(r.ok)
        Assert.assertTrue("boom", r.message.contains("boom"))
    }
}
