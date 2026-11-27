package com.mobileaiagent.agent

import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.core.log.AgentLog
import com.mobileaiagent.core.util.IdGenerator
import com.mobileaiagent.domain.repo.ProviderRepository
import com.mobileaiagent.domain.repo.SettingsRepository
import com.mobileaiagent.providers.ApiMessage
import com.mobileaiagent.providers.ChatRequest
import com.mobileaiagent.providers.FinishReason
import com.mobileaiagent.providers.StreamSink
import com.mobileaiagent.tools.spec.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * `use_model` — the Main Model's delegation channel to the Secondary Model.
 *
 * The secondary model is NOT an agent: it receives a single scoped task plus
 * explicit context, produces text, and returns. It gets no tools, cannot run
 * anything, and only one invocation may be in flight at any time (enforced by a
 * global mutex). The main model still has to verify and integrate the answer.
 */
class DelegationTool(
    private val providers: ProviderRepository,
    private val settingsRepo: SettingsRepository,
    private val ids: IdGenerator,
) : AgentTool {

    companion object {
        /** Process-wide: only one secondary-model invocation at a time. */
        private val delegationLock = Mutex()
    }

    override val id = "use_model"
    override val name = "Use Secondary Model"
    override val description =
        "Delegate one narrowly-scoped subtask to the secondary model and get its result back. " +
            "The secondary model cannot use tools or touch files; pass all needed context in 'context'. " +
            "Use it for focused drafting, summarising, translation or analysis. Only one call runs at a time."
    override val version = "1.0.0"
    override val category = ToolCategory.DELEGATION
    override val inputSchema = schema {
        requiredString("instruction", "The exact task for the secondary model to perform. Be specific and self-contained.")
        requiredString("context", "All information the secondary model needs (file excerpts, data, constraints). Include any relevant plan/task context.")
        optionalString("expected_format", "How the result should be shaped, e.g. 'JSON array of {name, description}' or 'markdown'.")
        optionalBool("include_project_summary", "Attach an automatic summary of the current project files. Default false.")
        optionalString("subtask_type", "One of: summarise, draft, translate, review, extract, analyse, code. Default analyse.")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.NETWORK)
    override val mutagenic = false
    override val cancellationSupported = true
    override val maxResultChars = 24_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val instruction = args.string("instruction").trim()
        val context = args.string("context")
        if (instruction.isBlank()) return ToolResult.Failure("instruction must not be blank")

        val settings = settingsRepo.snapshot()
        val secondaryModel = settings.secondaryModel
            ?: return ToolResult.Failure(
                "No secondary model is configured. Ask the user to choose one in Settings -> Models.",
                retryable = false, errorKind = "not_configured",
            )
        val providerId = settings.secondaryProviderId ?: settings.defaultProviderId
        val provider = providers.provider(providerId)
            ?: return ToolResult.Failure("Provider '$providerId' is not available.", errorKind = "provider_missing")
        if (!provider.isConfigured()) {
            return ToolResult.Failure("Provider '$providerId' has no API key configured.", errorKind = "auth")
        }

        val taskContext = buildString {
            appendLine("You are the secondary model inside a larger agent session.")
            appendLine("Complete ONLY the requested subtask and return the result. You cannot call tools.")
            appendLine()
            appendLine("Project: ${ctx.projectId}")
            appendLine()
            appendLine("## Subtask for you")
            appendLine(instruction)
            val expected = args.optionalString("expected_format")
            if (!expected.isNullOrBlank()) {
                appendLine()
                appendLine("## Expected output format")
                appendLine(expected)
            }
            if (args.boolean("include_project_summary", false)) {
                val root = ctx.workspace
                if (root != null && ctx.projectRoot != null) {
                    appendLine()
                    appendLine("## Project files")
                    runCatching {
                        val entries = root.list("", recursive = true).take(200)
                        entries.forEach { e ->
                            appendLine("- ${e.path}${if (e.isDirectory) "/" else " (${e.sizeBytes} B)"}")
                        }
                    }.onFailure { appendLine("(file listing unavailable: ${it.message})") }
                }
            }
            appendLine()
            appendLine("## Context provided by the main agent")
            appendLine(context.take(96_000))
        }

        ctx.cancellation.throwIfCancelled()
        val started = System.currentTimeMillis()
        ctx.emitProgress("Delegating to secondary model…", null)
        AgentLog.i("DelegationTool", "Delegating '${instruction.take(80)}' to $secondaryModel")

        val outcome = delegationLock.withLock {
            if (ctx.cancellation.isCancelled) return ToolResult.Cancelled
            var text = StringBuilder()
            val sink = object : StreamSink {
                override fun onTextDelta(t: String) { text.append(t) }
            }
            try {
                val result = provider.streamChat(
                    ChatRequest(
                        model = secondaryModel,
                        reasoningEffort = settings.secondaryReasoningEffort,
                        messages = listOf(
                            ApiMessage(
                                role = "system",
                                content = "You are a precise, focused assistant that completes one delegated subtask thoroughly and returns only the result.",
                            ),
                            ApiMessage(role = "user", content = taskContext),
                        ),
                        tools = emptyList(),
                        maxOutputTokens = 4096,
                        temperature = 0.4,
                    ),
                    sink,
                    ctx.cancellation,
                )
                if (result.finishReason == FinishReason.CANCELLED) return ToolResult.Cancelled
                val finalText = result.text.ifEmpty { text.toString() }
                if (finalText.isBlank()) {
                    return ToolResult.Failure(
                        "Secondary model returned an empty response (finish: ${result.finishReason}).",
                        retryable = true, errorKind = "empty_response",
                    )
                }
                ToolResult.Success(
                    content = "Secondary model ($secondaryModel) result:\n\n$finalText",
                    structured = JsonValue.obj(
                        "model" to JsonValue.of(secondaryModel),
                        "duration_ms" to JsonValue.of(System.currentTimeMillis() - started),
                        "input_tokens" to JsonValue.of(result.usage.inputTokens),
                        "output_tokens" to JsonValue.of(result.usage.outputTokens),
                    ),
                    durationMs = System.currentTimeMillis() - started,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                ToolResult.Cancelled
            } catch (e: Exception) {
                AgentLog.e("DelegationTool", "Secondary model call failed", e)
                ToolResult.Failure(
                    "Secondary model call failed: ${e::class.simpleName}: ${e.message}",
                    retryable = true, errorKind = "provider_error",
                )
            }
        }
        return outcome
    }
}
