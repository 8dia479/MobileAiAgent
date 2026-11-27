package com.mobileaiagent.providers.impl

import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.core.util.CancellationToken
import com.mobileaiagent.domain.model.ReasoningEffort
import com.mobileaiagent.providers.*
import com.mobileaiagent.providers.transport.HttpTransport
import com.mobileaiagent.providers.transport.JsonParserRef

/**
 * Anthropic Messages API adapter.
 *
 * Reasoning is controlled through extended thinking with an explicit
 * `budget_tokens` value, so the six levels map to concrete budgets rather than
 * an enum string. Models that do not support thinking get no reasoning field.
 */
class AnthropicProvider(
    private val transport: HttpTransport,
    private val credentials: () -> String?,
    private val baseUrl: () -> String = { ProviderKind.ANTHROPIC.defaultBaseUrl },
) : AiProvider {

    override val id = "anthropic"
    override val displayName = "Anthropic"
    override val kind = ProviderKind.ANTHROPIC
    override val supportsModelDiscovery = true
    override val capabilities = ProviderCapabilities(
        streaming = true,
        toolCalling = true,
        imageInput = true,
        fileInput = false,
        reasoningControl = true,
        structuredOutput = false,
        reasoningMapping = ReasoningMapping(
            supported = setOf(
                ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH, ReasoningEffort.XHIGH,
            ),
            mechanism = ReasoningMechanism.TOKEN_BUDGET,
        ),
        supportsReasoningContent = true,
    )

    override fun isConfigured(): Boolean = !credentials().isNullOrBlank()

    private fun headers(key: String) = mapOf(
        "x-api-key" to key,
        "anthropic-version" to "2023-06-01",
        "Content-Type" to "application/json",
    )

    override suspend fun discoverModels(): ModelDiscoveryResult {
        val key = credentials() ?: return ModelDiscoveryResult.Failure("No API key configured", authFailure = true)
        val r = transport.get("${baseUrl().trimEnd('/')}/models", headers(key), timeoutMs = 30_000)
        if (!r.isSuccess) {
            return ModelDiscoveryResult.Failure(
                r.errorSummary(),
                authFailure = r.statusCode == 401 || r.statusCode == 403,
            )
        }
        val json = r.jsonOrNull() ?: return ModelDiscoveryResult.Failure("Invalid JSON from model discovery")
        val data = json["data"].asArrayOrNull() ?: return ModelDiscoveryResult.Failure("No data in response")
        val now = System.currentTimeMillis()
        val models = data.mapNotNull { m ->
            val modelId = m["id"].asStringOrNull() ?: return@mapNotNull null
            ModelDescriptor(
                id = modelId,
                displayName = m["display_name"].asStringOrNull() ?: modelId,
                providerId = id,
                contextWindow = m["context_window"].asIntOrNull(),
                maxOutputTokens = m["max_tokens"].asIntOrNull(),
                capabilities = inferCapabilities(modelId),
                capabilitiesSource = CapabilitySource.INFERRED,
                raw = m,
                fetchedAt = now,
            )
        }
        return if (models.isEmpty()) ModelDiscoveryResult.Failure("Provider returned no models")
        else ModelDiscoveryResult.Success(models)
    }

    private fun inferCapabilities(model: String): ModelCapabilities {
        val lower = model.lowercase()
        return ModelCapabilities(
            vision = true,
            toolCalling = true,
            reasoning = lower.contains("3-7") || lower.contains("sonnet-4") || lower.contains("opus-4"),
            structuredOutput = false,
        )
    }

    /** Maps our level to an Anthropic thinking budget. NONE disables thinking. */
    fun thinkingBudgetFor(effort: ReasoningEffort, maxTokens: Int): Int? = when (effort) {
        ReasoningEffort.NONE -> null
        ReasoningEffort.LOW -> 1024
        ReasoningEffort.MEDIUM -> 4096
        ReasoningEffort.HIGH -> 12000
        ReasoningEffort.XHIGH -> 24000
        ReasoningEffort.MAX -> null // unsupported: exceeds documented ceiling
    }

    override suspend fun streamChat(
        request: ChatRequest,
        sink: StreamSink,
        cancellation: CancellationToken,
    ): ChatResult {
        val key = credentials()
        if (key.isNullOrBlank()) {
            val msg = "Anthropic API key missing"
            sink.onError(msg, false)
            return ChatResult("", null, emptyList(), FinishReason.ERROR, Usage(), msg)
        }
        val budget = request.reasoningEffort?.let { thinkingBudgetFor(it, request.maxOutputTokens) }
        val body = buildBody(request, budget)
        sink.onStart()
        val response = transport.postJsonStream(
            "${baseUrl().trimEnd('/')}/messages",
            headers(key),
            body,
            cancellation = cancellation,
        )
        if (response.statusCode !in 200..299) {
            val err = response.errorBodyIfNotSuccess ?: ""
            response.close()
            sink.onError("HTTP ${response.statusCode}: ${err.take(600)}", response.statusCode in 500..599 || response.statusCode == 429)
            return ChatResult("", null, emptyList(), FinishReason.ERROR, Usage(), err)
        }

        val text = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<ApiToolCall>()
        var currentToolId: String? = null
        var currentToolName: String? = null
        val currentToolArgs = StringBuilder()
        var inputTokens = 0L
        var outputTokens = 0L
        var stopReason: String? = null

        response.use { resp ->
            for (event in resp.sseEvents()) {
                if (cancellation.isCancelled) break
                val json = JsonParserRef.parseOrNull(event.data) ?: continue
                when (json.stringOr("type", "")) {
                    "message_start" -> {
                        inputTokens = json.path("message", "usage")["input_tokens"].asLongOrNull() ?: 0
                    }
                    "content_block_start" -> {
                        val block = json["content_block"]
                        if (block["type"].asStringOrNull() == "tool_use") {
                            currentToolId = block["id"].asStringOrNull()
                            currentToolName = block["name"].asStringOrNull()
                            currentToolArgs.setLength(0)
                        }
                    }
                    "content_block_delta" -> {
                        val delta = json["delta"]
                        when (delta["type"].asStringOrNull()) {
                            "text_delta" -> {
                                val t = delta["text"].asStringOrNull() ?: ""
                                text.append(t); if (t.isNotEmpty()) sink.onTextDelta(t)
                            }
                            "thinking_delta" -> {
                                val t = delta["thinking"].asStringOrNull() ?: ""
                                reasoning.append(t); if (t.isNotEmpty()) sink.onReasoningDelta(t)
                            }
                            "input_json_delta" -> {
                                val partial = delta["partial_json"].asStringOrNull() ?: ""
                                currentToolArgs.append(partial)
                                sink.onToolCallPartial(0, currentToolId, currentToolName, partial)
                            }
                        }
                    }
                    "content_block_stop" -> {
                        if (currentToolName != null) {
                            toolCalls.add(
                                ApiToolCall(
                                    id = currentToolId ?: "tool_${toolCalls.size}",
                                    name = currentToolName!!,
                                    arguments = currentToolArgs.toString().ifEmpty { "{}" },
                                )
                            )
                            currentToolName = null
                            currentToolArgs.setLength(0)
                        }
                    }
                    "message_delta" -> {
                        outputTokens = json.path("usage")["output_tokens"].asLongOrNull() ?: outputTokens
                        stopReason = json.path("delta")["stop_reason"].asStringOrNull() ?: stopReason
                    }
                    "error" -> {
                        val msg = json.path("error").stringOr("message", "unknown error")
                        sink.onError(msg, retryable = true)
                        return ChatResult(text.toString(), reasoning.toString().ifEmpty { null }, toolCalls, FinishReason.ERROR, Usage(inputTokens, outputTokens), msg)
                    }
                    else -> Unit
                }
            }
        }
        val finish = when (stopReason) {
            "tool_use" -> FinishReason.TOOL_CALLS
            "max_tokens" -> FinishReason.LENGTH
            "end_turn", "stop_sequence", null -> FinishReason.STOP
            else -> FinishReason.STOP
        }
        sink.onUsage(Usage(inputTokens, outputTokens, inputTokens + outputTokens))
        sink.onDone()
        return ChatResult(text.toString(), reasoning.toString().ifEmpty { null }, toolCalls, finish, Usage(inputTokens, outputTokens, inputTokens + outputTokens), stopReason)
    }

    fun buildBody(request: ChatRequest, thinkingBudget: Int?): JsonValue {
        val messages = request.messages.filter { it.role != "system" }.map { msg ->
            val parts = mutableListOf<JsonValue>()
            if (msg.content.isNotBlank()) {
                parts.add(JsonValue.obj("type" to JsonValue.of("text"), "text" to JsonValue.of(msg.content)))
            }
            msg.images.forEach { img ->
                if (img.base64 != null) {
                    parts.add(
                        JsonValue.obj(
                            "type" to JsonValue.of("image"),
                            "source" to JsonValue.obj(
                                "type" to JsonValue.of("base64"),
                                "media_type" to JsonValue.of(img.mimeType),
                                "data" to JsonValue.of(img.base64),
                            ),
                        )
                    )
                }
            }
            if (msg.role == "tool" && msg.toolCallId != null) {
                JsonValue.obj(
                    "role" to JsonValue.of("user"),
                    "content" to JsonValue.Arr(
                        listOf(
                            JsonValue.obj(
                                "type" to JsonValue.of("tool_result"),
                                "tool_use_id" to JsonValue.of(msg.toolCallId),
                                "content" to JsonValue.of(msg.content),
                            )
                        )
                    ),
                )
            } else JsonValue.obj("role" to JsonValue.of(msg.role), "content" to JsonValue.Arr(parts))
        }
        val system = request.messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        val map = linkedMapOf<String, JsonValue>(
            "model" to JsonValue.of(request.model),
            "max_tokens" to JsonValue.of(request.maxOutputTokens),
            "stream" to JsonValue.of(true),
            "messages" to JsonValue.Arr(messages),
        )
        if (system.isNotBlank()) map["system"] = JsonValue.of(system)
        if (thinkingBudget != null) {
            map["thinking"] = JsonValue.obj(
                "type" to JsonValue.of("enabled"),
                "budget_tokens" to JsonValue.of(thinkingBudget),
            )
        }
        request.temperature?.let { if (thinkingBudget == null) map["temperature"] = JsonValue.of(it) }
        if (request.tools.isNotEmpty()) {
            map["tools"] = JsonValue.Arr(
                request.tools.map { t ->
                    JsonValue.obj(
                        "name" to JsonValue.of(t.name),
                        "description" to JsonValue.of(t.description),
                        "input_schema" to t.parameters,
                    )
                }
            )
        }
        return JsonValue.Obj(map)
    }
}
