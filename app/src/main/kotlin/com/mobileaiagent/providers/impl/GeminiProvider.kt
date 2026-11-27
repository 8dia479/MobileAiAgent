package com.mobileaiagent.providers.impl

import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.core.util.CancellationToken
import com.mobileaiagent.domain.model.ReasoningEffort
import com.mobileaiagent.providers.*
import com.mobileaiagent.providers.transport.HttpTransport
import com.mobileaiagent.providers.transport.JsonParserRef

/**
 * Google Gemini (Generative Language API) adapter.
 *
 * Uses `streamGenerateContent?alt=sse`. Thinking is controlled by
 * `generationConfig.thinkingConfig.thinkingBudget`; Gemini-2.5-flash documents a
 * 0..24576 budget, pro a larger one. We map the six levels onto explicit budgets
 * and report MAX/unsupported levels honestly instead of clamping silently.
 */
class GeminiProvider(
    private val transport: HttpTransport,
    private val credentials: () -> String?,
    private val baseUrl: () -> String = { ProviderKind.GEMINI.defaultBaseUrl },
) : AiProvider {
    override val id = "gemini"
    override val displayName = "Google Gemini"
    override val kind = ProviderKind.GEMINI
    override val supportsModelDiscovery = true
    override val capabilities = ProviderCapabilities(
        streaming = true,
        toolCalling = true,
        imageInput = true,
        fileInput = true,
        reasoningControl = true,
        structuredOutput = true,
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

    private fun headers() = mapOf("Content-Type" to "application/json")

    private fun url(path: String, key: String): String =
        "${baseUrl().trimEnd('/')}/$path${if (path.contains('?')) "&" else "?"}key=$key"

    override suspend fun discoverModels(): ModelDiscoveryResult {
        val key = credentials() ?: return ModelDiscoveryResult.Failure("No API key configured", authFailure = true)
        val r = transport.get(url("models?pageSize=200", key), headers(), timeoutMs = 30_000)
        if (!r.isSuccess) {
            return ModelDiscoveryResult.Failure(r.errorSummary(), r.statusCode == 400 || r.statusCode == 403)
        }
        val json = r.jsonOrNull() ?: return ModelDiscoveryResult.Failure("Invalid JSON from model discovery")
        val models = json["models"].asArrayOrNull() ?: return ModelDiscoveryResult.Failure("No models in response")
        val now = System.currentTimeMillis()
        val list = models.mapNotNull { m ->
            val name = m["name"].asStringOrNull() ?: return@mapNotNull null
            val modelId = name.removePrefix("models/")
            val methods = m["supportedGenerationMethods"].asArrayOrNull()?.mapNotNull { it.asStringOrNull() } ?: emptyList()
            if (methods.isNotEmpty() && "generateContent" !in methods) return@mapNotNull null
            ModelDescriptor(
                id = modelId,
                displayName = m["displayName"].asStringOrNull() ?: modelId,
                providerId = id,
                contextWindow = m["inputTokenLimit"].asIntOrNull(),
                maxOutputTokens = m["outputTokenLimit"].asIntOrNull(),
                capabilities = ModelCapabilities(
                    vision = true,
                    toolCalling = true,
                    reasoning = modelId.contains("2.5") || modelId.contains("thinking"),
                    structuredOutput = true,
                ),
                capabilitiesSource = CapabilitySource.INFERRED,
                raw = m,
                fetchedAt = now,
            )
        }
        return if (list.isEmpty()) ModelDiscoveryResult.Failure("No generative models returned")
        else ModelDiscoveryResult.Success(list)
    }

    fun thinkingBudgetFor(effort: ReasoningEffort): Int? = when (effort) {
        ReasoningEffort.NONE -> 0
        ReasoningEffort.LOW -> 1024
        ReasoningEffort.MEDIUM -> 4096
        ReasoningEffort.HIGH -> 12288
        ReasoningEffort.XHIGH -> 24576
        ReasoningEffort.MAX -> null
    }

    override suspend fun streamChat(
        request: ChatRequest,
        sink: StreamSink,
        cancellation: CancellationToken,
    ): ChatResult {
        val key = credentials()
        if (key.isNullOrBlank()) {
            val msg = "Gemini API key missing"; sink.onError(msg, false)
            return ChatResult("", null, emptyList(), FinishReason.ERROR, Usage(), msg)
        }
        val body = buildBody(request)
        val model = request.model.removePrefix("models/")
        sink.onStart()
        val response = transport.postJsonStream(
            url("models/$model:streamGenerateContent?alt=sse", key),
            headers(),
            body,
            cancellation = cancellation,
        )
        if (response.statusCode !in 200..299) {
            val err = response.errorBodyIfNotSuccess ?: ""
            response.close()
            sink.onError("HTTP ${response.statusCode}: ${err.take(600)}", response.statusCode in 500..599)
            return ChatResult("", null, emptyList(), FinishReason.ERROR, Usage(), err)
        }
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<ApiToolCall>()
        var usage = Usage()
        var finish = FinishReason.STOP
        response.use { resp ->
            for (event in resp.sseEvents()) {
                if (cancellation.isCancelled) break
                val json = JsonParserRef.parseOrNull(event.data) ?: continue
                val candidate = json["candidates"][0]
                val parts = candidate.path("content")["parts"].asArrayOrNull() ?: emptyList()
                for (part in parts) {
                    part["text"].asStringOrNull()?.let {
                        if (part["thought"].asBooleanOrNull() == true) {
                            reasoning.append(it); sink.onReasoningDelta(it)
                        } else {
                            text.append(it); sink.onTextDelta(it)
                        }
                    }
                    part["functionCall"]?.let { fc ->
                        if (fc !is JsonValue.Null) {
                            toolCalls.add(
                                ApiToolCall(
                                    id = "gemini_${toolCalls.size}",
                                    name = fc.stringOr("name", ""),
                                    arguments = (fc["args"]).toJson(),
                                )
                            )
                        }
                    }
                }
                candidate["finishReason"].asStringOrNull()?.let {
                    finish = when (it) {
                        "MAX_TOKENS" -> FinishReason.LENGTH
                        "SAFETY", "RECITATION" -> FinishReason.CONTENT_FILTER
                        "STOP" -> if (toolCalls.isNotEmpty()) FinishReason.TOOL_CALLS else FinishReason.STOP
                        else -> FinishReason.STOP
                    }
                }
                json["usageMetadata"]?.let { u ->
                    if (u !is JsonValue.Null) {
                        usage = Usage(
                            inputTokens = u.intOr("promptTokenCount", 0).toLong(),
                            outputTokens = u.intOr("candidatesTokenCount", 0).toLong(),
                            totalTokens = u.intOr("totalTokenCount", 0).toLong(),
                            reasoningTokens = u.intOr("thoughtsTokenCount", 0).toLong(),
                        )
                    }
                }
            }
        }
        sink.onUsage(usage)
        sink.onDone()
        return ChatResult(text.toString(), reasoning.toString().ifEmpty { null }, toolCalls, finish, usage)
    }

    fun buildBody(request: ChatRequest): JsonValue {
        val systemParts = request.messages.filter { it.role == "system" }
        val contents = request.messages.filter { it.role != "system" }.map { msg ->
            val parts = mutableListOf<JsonValue>()
            if (msg.content.isNotBlank()) parts.add(JsonValue.obj("text" to JsonValue.of(msg.content)))
            msg.images.forEach { img ->
                if (img.base64 != null) {
                    parts.add(
                        JsonValue.obj(
                            "inline_data" to JsonValue.obj(
                                "mime_type" to JsonValue.of(img.mimeType),
                                "data" to JsonValue.of(img.base64),
                            )
                        )
                    )
                }
            }
            JsonValue.obj(
                "role" to JsonValue.of(if (msg.role == "assistant") "model" else "user"),
                "parts" to JsonValue.Arr(parts),
            )
        }
        val genConfig = linkedMapOf<String, JsonValue>(
            "maxOutputTokens" to JsonValue.of(request.maxOutputTokens),
        )
        request.temperature?.let { genConfig["temperature"] = JsonValue.of(it) }
        val budget = request.reasoningEffort?.let { thinkingBudgetFor(it) }
        if (budget != null) {
            genConfig["thinkingConfig"] = JsonValue.obj("thinkingBudget" to JsonValue.of(budget))
        }
        val map = linkedMapOf<String, JsonValue>(
            "contents" to JsonValue.Arr(contents),
            "generationConfig" to JsonValue.Obj(genConfig),
        )
        if (systemParts.isNotEmpty()) {
            map["systemInstruction"] = JsonValue.obj(
                "parts" to JsonValue.Arr(systemParts.map { JsonValue.obj("text" to JsonValue.of(it.content)) }),
            )
        }
        if (request.tools.isNotEmpty()) {
            map["tools"] = JsonValue.Arr(
                listOf(
                    JsonValue.obj(
                        "functionDeclarations" to JsonValue.Arr(
                            request.tools.map { t ->
                                JsonValue.obj(
                                    "name" to JsonValue.of(t.name),
                                    "description" to JsonValue.of(t.description),
                                    "parameters" to t.parameters,
                                )
                            }
                        )
                    )
                )
            )
        }
        return JsonValue.Obj(map)
    }
}
