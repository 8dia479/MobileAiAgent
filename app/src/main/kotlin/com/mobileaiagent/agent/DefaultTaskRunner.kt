package com.mobileaiagent.agent

import com.mobileaiagent.agent.plan.PlanManager
import com.mobileaiagent.agent.recovery.RecoveryEngine
import com.mobileaiagent.core.log.AgentLog
import com.mobileaiagent.core.storage.AgentStores
import com.mobileaiagent.core.storage.TaskCheckpoint
import com.mobileaiagent.core.util.CancellationToken
import com.mobileaiagent.core.util.Clock
import com.mobileaiagent.core.util.IdGenerator
import com.mobileaiagent.core.util.SystemClock
import com.mobileaiagent.domain.model.*
import com.mobileaiagent.domain.repo.AgentSettings
import com.mobileaiagent.domain.repo.ProjectRepository
import com.mobileaiagent.domain.repo.ProviderRepository
import com.mobileaiagent.domain.repo.ResourceMonitor
import com.mobileaiagent.domain.repo.SettingsRepository
import com.mobileaiagent.service.LiveRunRegistry
import com.mobileaiagent.tools.spec.ArtifactRef
import com.mobileaiagent.tools.spec.ToolContext
import com.mobileaiagent.tools.spec.ToolPlatform
import com.mobileaiagent.tools.spec.ToolRegistry
import com.mobileaiagent.tools.spec.ToolResult
import com.mobileaiagent.tools.spec.ToolRuntimeSettings
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The real agent executor: a full multi-turn ReAct loop per task.
 *
 * Turn shape: model streams -> (assistant message persisted with tool calls) ->
 * each tool call is journaled BEFORE execution, then executed, then journaled
 * with its outcome and appended as a TOOL message -> next turn.
 *
 * All I/O goes through repositories; nothing here touches Android UI APIs, so the
 * loop runs identically inside a foreground service or a unit test.
 */
class DefaultTaskRunner(
    private val stores: AgentStores,
    private val providers: ProviderRepository,
    private val settingsRepo: SettingsRepository,
    private val projects: ProjectRepository,
    private val registry: ToolRegistry,
    private val planManager: PlanManager,
    private val taskManagerProvider: () -> TaskManager?,
    private val resources: ResourceMonitor,
    private val clock: Clock = SystemClock,
    private val ids: IdGenerator = IdGenerator.random(),
    private val deviceName: () -> String? = { null },
    private val androidRelease: () -> String? = { null },
    private val recoveryEngine: RecoveryEngine? = null,
    /** Called on meaningful state changes so the notification/service can update. */
    private val onStateChanged: suspend (TaskRecord) -> Unit = {},
) : TaskRunner {

    private val pauseRequests = ConcurrentHashMap.newKeySet<String>()
    private val cancelRequests = ConcurrentHashMap.newKeySet<String>()
    private val progressSinks = ConcurrentHashMap<String, (String, Float?) -> Unit>()
    private val lastArtifacts = ConcurrentHashMap<String, MutableList<ArtifactRef>>()

    override fun requestPause(taskId: String) { pauseRequests.add(taskId) }
    override fun requestCancel(taskId: String) { cancelRequests.add(taskId) }

    fun registerProgressSink(taskId: String, sink: (String, Float?) -> Unit) { progressSinks[taskId] = sink }
    fun artifactsFor(taskId: String): List<ArtifactRef> = lastArtifacts[taskId]?.toList() ?: emptyList()

    private fun manager(): TaskManager? = taskManagerProvider()

    override suspend fun run(task: TaskRecord, cancellation: CancellationToken, live: LiveRunRegistry): RunOutcome {
        val settings = settingsRepo.snapshot()
        val runId = ids.newId("run")
        stores.runs.start(
            RunRecord(
                id = runId, taskId = task.id, phase = RunPhase.AGENT_LOOP,
                startedAt = clock.nowMillis(), endedAt = null, exitReason = null, steps = 0,
                lastStepSummary = null, serviceInstanceId = null, hostBootId = null,
            )
        )
        stores.tasks.upsert(task.copy(activeRunId = runId, updatedAt = clock.nowMillis()))
        live.register(task.id, runId, cancellation)
        emit(task.id, runId, EventType.RUN_STARTED, "Run started", "Model ${task.mainModel}")
        lastArtifacts[task.id] = mutableListOf()

        val projectRoot = resolveProjectRoot(task)
            ?: return finish(RunOutcome.Kind.FAILED, "No project available", ErrorKind.INTERNAL, task.id, runId, live, 0, null)

        var iterations = 0
        var lastSummary: String? = null
        var exitKind = RunOutcome.Kind.COMPLETED
        var exitMessage: String? = null
        var exitErrorKind: ErrorKind? = null
        var pendingApprovalJson: String? = null

        try {
            val provider = providers.provider(task.mainProvider)
                ?: providers.provider(providers.activeProviderId())
            if (provider == null || !provider.isConfigured()) {
                return finish(
                    RunOutcome.Kind.FAILED,
                    "No usable AI provider. Add an API key in Settings → Providers.",
                    ErrorKind.AUTH, task.id, runId, live, 0, null,
                )
            }
            if (!resources.isNetworkAvailable()) {
                return finish(
                    RunOutcome.Kind.WAITING_NETWORK, "No network connection; task will resume when online.",
                    ErrorKind.NETWORK, task.id, runId, live, 0, null,
                )
            }
            if (settings.backgroundWifiOnly && !resources.isUnmeteredNetwork()) {
                return finish(
                    RunOutcome.Kind.WAITING_NETWORK, "Waiting for Wi-Fi (background Wi-Fi-only setting is on).",
                    ErrorKind.NETWORK, task.id, runId, live, 0, null,
                )
            }

            val ctx = buildToolContext(task, runId, projectRoot, cancellation, settings)

            // Attach resume context for restored tasks.
            if (task.status == TaskStatus.INTERRUPTED || task.attemptCount > 1) {
                val resume = recoveryEngine?.resumeContext(task.id)
                val notice = AgentPrompts.recoveryContinuation(
                    resume?.completedSummary ?: "",
                    planManager.readActive(projectRoot)?.tasks
                        ?.firstOrNull { it.status == PlanTaskStatus.PENDING || it.status == PlanTaskStatus.IN_PROGRESS }?.title,
                )
                stores.messages.append(
                    ChatMessage(ids.newId("msg"), ChatMessage.Role.SYSTEM, notice, clock.nowMillis()), task.id,
                )
                emit(task.id, runId, EventType.RECOVERY_RESUMED, "Resumed from checkpoint", notice.take(400))
            }

            val maxIterations = settings.maxToolIterations.coerceIn(1, 200)
            val allowedTools = ToolFilters.forMode(task.mode)
            var awaitingApproval = false

            while (iterations < maxIterations) {
                if (cancellation.isCancelled || cancelRequests.remove(task.id)) {
                    return finish(RunOutcome.Kind.CANCELLED, "Cancelled", ErrorKind.CANCELLED, task.id, runId, live, iterations, lastSummary)
                }
                if (pauseRequests.remove(task.id)) {
                    return finish(RunOutcome.Kind.PAUSED, "Paused by user", null, task.id, runId, live, iterations, lastSummary)
                }
                if (!resources.isNetworkAvailable()) {
                    return finish(
                        RunOutcome.Kind.WAITING_NETWORK, "Lost network connection mid-task; safe to resume.",
                        ErrorKind.NETWORK, task.id, runId, live, iterations, lastSummary,
                    )
                }
                iterations++
                val currentPlan = planManager.readActive(projectRoot)
                val spec = AgentSpec(
                    taskId = task.id, runId = runId,
                    projectId = task.projectId ?: projectRoot.name,
                    projectName = task.projectName ?: projectRoot.name,
                    mode = task.mode, reasoningEffort = task.mainReasoningEffort,
                    secondaryModel = task.secondaryModel,
                    secondaryReasoningEffort = task.secondaryReasoningEffort,
                    toolManifest = describeTools(allowedTools),
                    plan = currentPlan,
                    deviceName = deviceName(), androidRelease = androidRelease(), nowIso = clock.nowIso(),
                )
                val loop = AgentLoop(
                    provider = provider,
                    registry = scopedRegistry(allowedTools),
                    spec = spec,
                    modelName = task.mainModel.ifBlank { settings.mainModel },
                    maxOutputTokens = settings.maxTokensPerRequest,
                    temperature = settings.temperature,
                )
                val history = stores.messages.list(task.id, 160)
                val assistantText = StringBuilder()
                val reasoningText = StringBuilder()
                var sawArtifact = false

                emit(task.id, runId, EventType.MODEL_REQUEST, "Model request", "turn $iterations")

                val observer = object : AgentTurnObserver {
                    override fun onTextDelta(text: String) {
                        assistantText.append(text)
                        progressSinks[task.id]?.invoke("Receiving response…", null)
                    }
                    override fun onReasoningDelta(text: String) { reasoningText.append(text) }

                    override suspend fun onUsage(usage: com.mobileaiagent.providers.Usage) {
                        val cur = stores.tasks.get(task.id) ?: return
                        stores.tasks.upsert(
                            cur.copy(
                                tokensIn = cur.tokensIn + usage.inputTokens,
                                tokensOut = cur.tokensOut + usage.outputTokens,
                                updatedAt = clock.nowMillis(),
                            )
                        )
                    }

                    override suspend fun onToolStarted(call: com.mobileaiagent.providers.ApiToolCall, tool: com.mobileaiagent.tools.spec.AgentTool?) {
                        stores.journal.started(task.id, runId, call.id, call.name, call.arguments, tool?.mutagenic == true)
                        emit(task.id, runId, EventType.TOOL_CALL_STARTED, call.name, call.arguments.take(600))
                    }

                    override suspend fun onToolFinished(
                        call: com.mobileaiagent.providers.ApiToolCall,
                        tool: com.mobileaiagent.tools.spec.AgentTool?,
                        result: ToolResult, durationMs: Long,
                    ) {
                        val status = when (result) {
                            is ToolResult.Success -> "succeeded"
                            is ToolResult.Failure -> "failed"
                            else -> "cancelled"
                        }
                        stores.journal.finished(call.id, status, result.render(500))
                        stores.messages.append(
                            ChatMessage(
                                id = ids.newId("msg"), role = ChatMessage.Role.TOOL,
                                content = result.render(6000), timestamp = clock.nowMillis(),
                                toolCallId = call.id, toolName = call.name,
                            ),
                            task.id,
                        )
                        if (result is ToolResult.Success && result.artifacts.isNotEmpty()) {
                            sawArtifact = true
                            lastArtifacts[task.id]?.addAll(result.artifacts)
                        }
                        val type = when (result) {
                            is ToolResult.Success -> EventType.TOOL_CALL_FINISHED
                            is ToolResult.Failure -> EventType.TOOL_CALL_FAILED
                            else -> EventType.TOOL_CALL_FINISHED
                        }
                        emit(task.id, runId, type, "${call.name} → $status (${durationMs} ms)", result.render(900))
                        if (call.name == "plan_generate" && result is ToolResult.Success) {
                            emit(task.id, runId, EventType.PLAN_CREATED, "Plan created", result.render(600))
                        }
                        if (call.name == "plan_update_task" && result is ToolResult.Success) {
                            emit(task.id, runId, EventType.CHECKLIST_UPDATED, "Checklist updated", result.render(400))
                        }
                        if (call.name == "request_approval" && result is ToolResult.Success) {
                            awaitingApproval = true
                            pendingApprovalJson = com.mobileaiagent.core.json.JsonParser
                                .parseOrNull(call.arguments)?.toJson()
                                ?: "{}"
                        }
                    }
                }

                val outcome = loop.runTurn(history, ctx, observer)

                if (outcome.finishReason == com.mobileaiagent.providers.FinishReason.CANCELLED || cancellation.isCancelled) {
                    stores.messages.append(
                        ChatMessage(
                            id = ids.newId("msg"), role = ChatMessage.Role.ASSISTANT,
                            content = assistantText.toString(), timestamp = clock.nowMillis(),
                            reasoning = reasoningText.toString().ifEmpty { null }, isPartial = true,
                        ),
                        task.id,
                    )
                    return finish(RunOutcome.Kind.CANCELLED, "Cancelled", ErrorKind.CANCELLED, task.id, runId, live, iterations, lastSummary)
                }

                if (outcome.fatalError != null) {
                    stores.messages.append(
                        ChatMessage(
                            id = ids.newId("msg"), role = ChatMessage.Role.ASSISTANT,
                            content = assistantText.toString(), timestamp = clock.nowMillis(), isPartial = true,
                        ),
                        task.id,
                    )
                    val kind = classifyError(outcome.fatalError)
                    emit(task.id, runId, EventType.ERROR, "Model request failed", outcome.fatalError.take(1500))
                    val runKind = if (kind == ErrorKind.NETWORK) RunOutcome.Kind.WAITING_NETWORK else RunOutcome.Kind.FAILED
                    return finish(runKind, outcome.fatalError, kind, task.id, runId, live, iterations, lastSummary)
                }

                stores.messages.append(
                    ChatMessage(
                        id = ids.newId("msg"), role = ChatMessage.Role.ASSISTANT,
                        content = outcome.assistantText, timestamp = clock.nowMillis(),
                        reasoning = outcome.reasoning,
                        toolCallsJson = if (outcome.toolCalls.isEmpty()) null
                        else ApiConversions.encodeToolCalls(outcome.toolCalls.map { it.call }),
                    ),
                    task.id,
                )
                if (outcome.assistantText.isNotBlank()) {
                    emit(task.id, runId, EventType.ASSISTANT_MESSAGE, "Assistant", outcome.assistantText)
                }

                if (awaitingApproval) {
                    exitKind = RunOutcome.Kind.NEEDS_USER
                    exitMessage = "Waiting for your approval to continue."
                    break
                }
                if (!outcome.hadToolCalls) {
                    lastSummary = outcome.assistantText.take(400)
                    checkpoint(task.id, planManager.readActive(projectRoot), lastSummary, runId)
                    exitKind = RunOutcome.Kind.COMPLETED
                    break
                }
                checkpoint(task.id, planManager.readActive(projectRoot), lastSummary, runId)
                onStateChanged(stores.tasks.get(task.id) ?: task)
            }

            if (iterations >= maxIterations && exitKind == RunOutcome.Kind.COMPLETED) {
                exitKind = RunOutcome.Kind.FAILED
                exitMessage = "Stopped after $maxIterations tool iterations (settings limit). Ask for a smaller step or raise the limit."
                exitErrorKind = ErrorKind.INTERNAL
            }
            return finish(exitKind, exitMessage, exitErrorKind, task.id, runId, live, iterations, lastSummary, pendingApprovalJson)
        } catch (e: CancellationException) {
            return finish(RunOutcome.Kind.CANCELLED, "Cancelled", ErrorKind.CANCELLED, task.id, runId, live, iterations, lastSummary)
        } catch (e: CancellationToken.CancellationException) {
            return finish(RunOutcome.Kind.CANCELLED, "Cancelled", ErrorKind.CANCELLED, task.id, runId, live, iterations, lastSummary)
        } catch (e: Exception) {
            AgentLog.e("DefaultTaskRunner", "Task ${task.id} failed", e)
            return finish(RunOutcome.Kind.FAILED, e.message ?: e::class.simpleName, ErrorKind.INTERNAL, task.id, runId, live, iterations, lastSummary)
        } finally {
            progressSinks.remove(task.id)
            live.unregister(runId)
        }
    }

    // ----------------------------------------------------------------- helpers

    private suspend fun resolveProjectRoot(task: TaskRecord): File? {
        val projectId = task.projectId
        if (projectId != null) {
            projects.projectInfo(projectId)?.let { return File(it.rootPath) }
        }
        return projects.listProjects().firstOrNull()?.let { File(it.rootPath) }
    }

    private fun buildToolContext(
        task: TaskRecord, runId: String, projectRoot: File,
        cancellation: CancellationToken, settings: AgentSettings,
    ): ToolContext = ToolContext(
        taskId = task.id, runId = runId,
        projectId = task.projectId ?: projectRoot.name,
        projectRoot = projectRoot,
        workspace = com.mobileaiagent.tools.builtin.FileWorkspace(projectRoot),
        cancellation = cancellation,
        log = { msg -> AgentLog.i("Task/${task.id.take(8)}", msg) },
        emitProgress = { msg, pct -> progressSinks[task.id]?.invoke(msg, pct) },
        emitArtifact = { art -> lastArtifacts[task.id]?.add(art) },
        settingsSnapshot = ToolRuntimeSettings(
            allowShell = settings.allowShell,
            shellWhitelist = settings.shellWhitelist.toSet(),
            allowShellChaining = settings.allowShellChaining,
            maxFileWriteBytes = 64L * 1024 * 1024,
            internetAccess = settings.internetAccess,
            searchEndpoint = settings.searchEndpoint,
        ),
        platform = ToolPlatform.ANDROID,
        permissionCheck = { true },
    )

    /**
     * A per-turn registry view: all tools for BUILD, read-only subset for PLAN.
     * Implemented by wrapping the shared registry so plugins registered at runtime
     * remain visible.
     */
    private fun scopedRegistry(allowed: Set<String>?): ToolRegistry {
        if (allowed == null) return registry
        val filtered = ToolRegistry()
        registry.all().filter { it.id in allowed }.forEach { filtered.register(it) }
        return filtered
    }

    private fun describeTools(allowed: Set<String>?): String {
        val tools = registry.enabled().filter { allowed == null || it.id in allowed }
        return buildString {
            appendLine("| tool id | category | when to use |")
            appendLine("|---|---|---|")
            tools.forEach { t ->
                appendLine("| `${t.id}` | ${t.category.label} | ${t.description.replace("\n", " ").take(240)} |")
            }
        }
    }

    private suspend fun checkpoint(taskId: String, plan: Plan?, summary: String?, runId: String?) {
        stores.checkpoints.save(
            TaskCheckpoint(
                taskId = taskId, createdAt = clock.nowMillis(),
                lastPlanTaskId = plan?.tasks?.firstOrNull {
                    it.status == PlanTaskStatus.IN_PROGRESS || it.status == PlanTaskStatus.PENDING
                }?.id,
                lastCompletedStepSummary = summary,
                assistantDraft = null,
                planRevision = plan?.revision,
            )
        )
        emit(taskId, runId, EventType.CHECKPOINT_SAVED, "Checkpoint saved", summary?.take(200))
    }

    private suspend fun finish(
        kind: RunOutcome.Kind, message: String?, errorKind: ErrorKind?,
        taskId: String, runId: String, live: LiveRunRegistry, iterations: Int, summary: String?,
        pendingApprovalJson: String? = null,
    ): RunOutcome {
        stores.runs.finish(runId, clock.nowMillis(), kind.name.lowercase(), iterations, summary)
        return RunOutcome(kind, message, errorKind, pendingApprovalJson)
    }

    private fun classifyError(message: String): ErrorKind {
        val lower = message.lowercase()
        return when {
            "401" in lower || "403" in lower || "unauthor" in lower || "api key" in lower ||
                "authentication" in lower || "invalid_api_key" in lower -> ErrorKind.AUTH
            "429" in lower || "rate limit" in lower || "quota" in lower || "too many requests" in lower -> ErrorKind.RATE_LIMIT
            "timeout" in lower || "timed out" in lower -> ErrorKind.TIMEOUT
            "network" in lower || "connect" in lower || "unreachable" in lower ||
                "socket" in lower || "offline" in lower || "unknown host" in lower -> ErrorKind.NETWORK
            "400" in lower || "422" in lower -> ErrorKind.PROVIDER
            else -> ErrorKind.PROVIDER
        }
    }

    private suspend fun emit(taskId: String, runId: String?, type: EventType, title: String, body: String?) {
        val m = manager()
        if (m != null) m.emit(taskId, runId, type, title, body)
        else AgentLog.i("Task/${taskId.take(8)}", title)
    }
}
