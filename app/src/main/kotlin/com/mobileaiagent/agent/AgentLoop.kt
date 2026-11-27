package com.mobileaiagent.agent

import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.core.log.AgentLog
import com.mobileaiagent.core.util.CancellationToken
import com.mobileaiagent.domain.model.ApiConversions
import com.mobileaiagent.domain.model.ChatMessage
import com.mobileaiagent.providers.AiProvider
import com.mobileaiagent.providers.ApiMessage
import com.mobileaiagent.providers.ApiToolCall
import com.mobileaiagent.providers.ApiToolSpec
import com.mobileaiagent.providers.ChatRequest
import com.mobileaiagent.providers.FinishReason
import com.mobileaiagent.providers.StreamSink
import com.mobileaiagent.providers.Usage
import com.mobileaiagent.tools.spec.AgentTool
import com.mobileaiagent.tools.spec.ToolContext
import com.mobileaiagent.tools.spec.ToolRegistry
import com.mobileaiagent.tools.spec.ToolResult

/**
 * Observes one agent turn: streaming deltas on the hot path (non-suspend so
 * providers can call them freely) and suspendable before/after tool hooks (so
 * the journal is written *before* a side effect, which recovery depends on).
 */
interface AgentTurnObserver {
    fun onTextDelta(text: String) {}
    fun onReasoningDelta(text: String) {}
    suspend fun onToolStarted(call: ApiToolCall, tool: AgentTool?) {}
    suspend fun onToolFinished(call: ApiToolCall, tool: AgentTool?, result: ToolResult, durationMs: Long) {}
    suspend fun onUsage(usage: Usage) {}
}

/**
 * One iteration of the agent's ReAct loop: a single model turn plus the tool
 * calls it requested. The outer orchestrator (DefaultTaskRunner) decides whether
 * to iterate again, so this class stays deterministic and unit-testable.
 */
class AgentLoop(
    private val provider: AiProvider,
    private val registry: ToolRegistry,
    private val spec: AgentSpec,
    private val modelName: String,
    private val maxOutputTokens: Int = 4096,
    private val temperature: Double? = 0.6,
) {

    data class TurnOutcome(
        val assistantText: String,
        val reasoning: String?,
        val toolCalls: List<ToolCallRecord>,
        val usage: Usage,
        val finishReason: FinishReason,
        val fatalError: String? = null,
        val retryable: Boolean = false,
    ) {
        val hadToolCalls: Boolean get() = toolCalls.isNotEmpty()
    }

    data class ToolCallRecord(
        val call: ApiToolCall,
        val result: ToolResult,
        val durationMs: Long,
    )

    suspend fun runTurn(
        conversation: List<ChatMessage>,
        ctx: ToolContext,
        observer: AgentTurnObserver,
        extraSystem: String? = null,
    ): TurnOutcome {
        val system = AgentPrompts.systemPrompt(spec) + (extraSystem?.let { "\n\n$it" } ?: "")
        val apiMessages = buildList {
            add(ApiMessage(role = "system", content = system))
            conversation.forEach { add(ApiConversions.toApi(it)) }
        }
        val toolSpecs = registry.enabled().map { t ->
            ApiToolSpec(name = t.id, description = t.description, parameters = t.inputSchema)
        }

        val streamed = StringBuilder()
        var usage = Usage()
        var streamError: String? = null
        var streamRetryable = false

        val sink = object : StreamSink {
            override fun onTextDelta(text: String) {
                streamed.append(text)
                observer.onTextDelta(text)
            }

            override fun onReasoningDelta(text: String) = observer.onReasoningDelta(text)

            override fun onUsage(u: Usage) { usage = u }

            override fun onError(message: String, retryable: Boolean) {
                streamError = message
                streamRetryable = retryable
            }
        }

        val result = try {
            provider.streamChat(
                ChatRequest(
                    model = modelName,
                    reasoningEffort = spec.reasoningEffort,
                    messages = apiMessages,
                    tools = toolSpecs,
                    maxOutputTokens = maxOutputTokens,
                    temperature = temperature,
                ),
                sink,
                ctx.cancellation,
            )
        } catch (e: CancellationToken.CancellationException) {
            return TurnOutcome("", null, emptyList(), usage, FinishReason.CANCELLED)
        } catch (e: Exception) {
            AgentLog.e("AgentLoop", "Model call failed", e)
            return TurnOutcome(
                streamed.toString(), null, emptyList(), usage, FinishReason.ERROR,
                fatalError = e.message ?: e::class.simpleName, retryable = true,
            )
        }

        val text = result.text.ifEmpty { streamed.toString() }
        val finalUsage = if (result.usage.totalTokens > 0 || result.usage.inputTokens > 0) result.usage else usage
        observer.onUsage(finalUsage)

        if (result.finishReason == FinishReason.CANCELLED || ctx.cancellation.isCancelled) {
            return TurnOutcome(text, result.reasoning, emptyList(), finalUsage, FinishReason.CANCELLED)
        }
        if (result.finishReason == FinishReason.ERROR && result.toolCalls.isEmpty()) {
            val message = streamError ?: result.rawFinishDetail ?: "provider error"
            return TurnOutcome(
                text, result.reasoning, emptyList(), finalUsage, FinishReason.ERROR,
                fatalError = message, retryable = streamRetryable,
            )
        }

        val records = mutableListOf<ToolCallRecord>()
        for (call in result.toolCalls) {
            if (ctx.cancellation.isCancelled) break
            val tool = registry.get(call.name)
            observer.onToolStarted(call, tool)
            val started = System.currentTimeMillis()
            val res: ToolResult = if (tool == null) {
                ToolResult.Failure(
                    "Unknown tool '${call.name}'. Available tools: " +
                        registry.enabled().joinToString { it.id },
                    errorKind = "unknown_tool",
                )
            } else {
                ToolExecutor.execute(tool, call, ctx)
            }
            val duration = System.currentTimeMillis() - started
            observer.onToolFinished(call, tool, res, duration)
            records.add(ToolCallRecord(call, res, duration))
        }

        return TurnOutcome(
            assistantText = text,
            reasoning = result.reasoning,
            toolCalls = records,
            usage = finalUsage,
            finishReason = result.finishReason,
        )
    }
}

/**
 * Executes one tool call with argument parsing, cancellation and error
 * containment. Every failure becomes a structured ToolResult the model can read.
 */
object ToolExecutor {

    suspend fun execute(tool: AgentTool, call: ApiToolCall, ctx: ToolContext): ToolResult {
        ctx.cancellation.throwIfCancelled()
        val args = try {
            val trimmed = call.arguments.trim().ifEmpty { "{}" }
            com.mobileaiagent.core.json.JsonParser.parse(trimmed)
        } catch (e: Exception) {
            return ToolResult.Failure(
                "Arguments for '${call.name}' are not valid JSON: ${e.message}. Received: ${call.arguments.take(400)}",
                errorKind = "invalid_json",
            )
        }
        if (args !is JsonValue.Obj) {
            return ToolResult.Failure("Arguments for '${call.name}' must be a JSON object", errorKind = "invalid_json")
        }
        return try {
            val result = tool.execute(args, ctx)
            if (result is ToolResult.Success && result.content.length > tool.maxResultChars) {
                result.copy(content = result.content.take(tool.maxResultChars) + "\n…[truncated]", truncated = true)
            } else {
                result
            }
        } catch (e: CancellationToken.CancellationException) {
            ToolResult.Cancelled
        } catch (e: com.mobileaiagent.tools.spec.ToolInputException) {
            ToolResult.Failure("Invalid arguments for '${tool.id}': ${e.message}", errorKind = "invalid_arguments")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentLog.e("ToolExecutor", "Tool ${tool.id} threw", e)
            ToolResult.Failure(
                "Tool '${tool.id}' failed: ${e::class.simpleName}: ${e.message}",
                retryable = true,
                errorKind = "tool_exception",
            )
        }
    }
}

/** Tool visibility filters per execution mode (PLAN must not mutate). */
object ToolFilters {
    val planModeAllowed = setOf(
        "file_list", "file_read", "file_search", "file_info", "project_summary",
        "plan_generate", "plan_get", "plan_update_task", "request_approval", "web_search",
        "web_fetch", "use_model", "task_note", "json_tool", "text_tool", "image_info",
        "mesh_info", "cad_info", "unknown_tool",
    )

    fun forMode(mode: com.mobileaiagent.domain.model.ExecutionMode): Set<String>? = when (mode) {
        com.mobileaiagent.domain.model.ExecutionMode.PLAN -> planModeAllowed
        else -> null // null == everything
    }
}
