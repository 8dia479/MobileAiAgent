package com.mobileaiagent.providers.impl

import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.core.log.AgentLog
import com.mobileaiagent.core.util.CancellationToken
import com.mobileaiagent.domain.model.ReasoningEffort
import com.mobileaiagent.providers.*
import com.mobileaiagent.providers.transport.HttpTransport
import com.mobileaiagent.providers.transport.JsonParserRef
import com.mobileaiagent.providers.transport.OpenAiWire

/**
 * Base adapter for any endpoint speaking the OpenAI chat-completions wire format
 * (NVIDIA NIM, OpenAI, llama.cpp servers, vLLM, local gateways…).
 *
 * Subclasses only differ in base URL, auth header shape and capability mapping.
 */
open class OpenAiCompatibleProvider(
    override val id: String,
    override val displayName: String,
    override val kind: ProviderKind,
    private val transport: HttpTransport,
    private val credentials: () -> String?,
    private val baseUrl: () -> String,
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        streaming = true,
        toolCalling = true,
        imageInput = true,
        reasoningControl = true,
    ),
) : AiProvider {

    override val supportsModelDiscovery: Boolean = true

    override fun isConfigured(): Boolean = !credentials().isNullOrBlank()

    protected open fun authHeaders(apiKey: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $apiKey",
    )

    protected open fun reasoningMappingFor(model: String): ReasoningMapping? = null

    protected open fun chatUrl(): String = "${baseUrl().trimEnd('/')}/chat/completions"

    protected open fun modelsUrl(): String = "${baseUrl().trimEnd('/')}/models"

    override suspend fun discoverModels(): ModelDiscoveryResult {
        val key = credentials()
        if (key.isNullOrBlank()) return ModelDiscoveryResult.Failure("No API key configured", authFailure = true)
        val result = transport.get(modelsUrl(), authHeaders(key) + jsonHeaders(), timeoutMs = 30_000)
        if (!result.isSuccess) {
            return ModelDiscoveryResult.Failure(
                result.errorSummary(),
                authFailure = result.statusCode == 401 || result.statusCode == 403,
            )
        }
        val json = result.jsonOrNull()
            ?: return ModelDiscoveryResult.Failure("Model list was not valid JSON")
        val parsed = OpenAiWire.parseModelList(json)
        if (parsed.isEmpty()) return ModelDiscoveryResult.Failure("Provider returned no models")
        val now = System.currentTimeMillis()
        val models = parsed.map { p ->
            val caps = inferCapabilities(p.id, p.raw)
            ModelDescriptor(
                id = p.id,
                displayName = p.displayName,
                providerId = id,
                contextWindow = p.contextWindow,
                maxOutputTokens = p.maxOutputTokens,
                capabilities = caps,
                capabilitiesSource = CapabilitySource.INFERRED,
                raw = p.raw,
                fetchedAt = now,
            )
        }
        AgentLog.i("Provider/$id", "Discovered ${models.size} models")
        return ModelDiscoveryResult.Success(models)
    }

    /**
     * Model-list entries rarely advertise capabilities. We infer conservatively
     * and clearly mark it INFERRED; capability overrides recorded by the user
     * (Model Manager) win over this.
     */
    protected open fun inferCapabilities(modelId: String, raw: JsonValue?): ModelCapabilities {
        val lower = modelId.lowercase()
        val architectureModality = raw?.get("architecture")?.let { it["modality"].asStringOrNull() }
        val declaredCaps = raw?.get("capabilities")?.asArrayOrNull()?.mapNotNull { it.asStringOrNull() } ?: emptyList()
        val visionHint = architectureModality?.contains("image") == true ||
            declaredCaps.contains("vision") || declaredCaps.contains("image_input")
        val reasoningHint = lower.contains("reasoning") || lower.contains("thinking") ||
            lower.contains("o1") || lower.contains("o3") || lower.contains("r1") ||
            lower.contains("nemotron") && lower.contains("reasoning")
        return ModelCapabilities(
            vision = if (visionHint) true else null,
            toolCalling = null,
            reasoning = if (reasoningHint) true else null,
        )
    }

    override suspend fun streamChat(
        request: ChatRequest,
        sink: StreamSink,
        cancellation: CancellationToken,
    ): ChatResult {
        val key = credentials()
        if (key.isNullOrBlank()) {
            val msg = "Provider $displayName has no API key configured"
            sink.onError(msg, retryable = false)
            return ChatResult("", null, emptyList(), FinishReason.ERROR, Usage(), msg)
        }
        val body = OpenAiWire.buildRequestBody(request, reasoningMappingFor(request.model))
        sink.onStart()
        val response = transport.postJsonStream(
            url = chatUrl(),
            headers = authHeaders(key) + jsonHeaders(),
            body = body,
            cancellation = cancellation,
        )
        if (response.statusCode !in 200..299) {
            val errBody = response.errorBodyIfNotSuccess ?: ""
            response.close()
            val retryable = response.statusCode == 429 || response.statusCode in 500..599
            val message = "HTTP ${response.statusCode}: ${errBody.take(800)}"
            sink.onError(message, retryable)
            return ChatResult("", null, emptyList(), FinishReason.ERROR, Usage(), message)
        }

        val accum = OpenAiWire.StreamAccumulator()
        try {
            response.use { resp ->
                for (event in resp.sseEvents()) {
                    if (cancellation.isCancelled) break
                    val data = event.data.trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") { accum.sawDone = true; break }
                    val json = JsonParserRef.parseOrNull(data) ?: continue
                    for (delta in accum.absorb(json)) {
                        when (delta) {
                            is OpenAiWire.DeltaEvent.Text -> sink.onTextDelta(delta.text)
                            is OpenAiWire.DeltaEvent.Reasoning -> sink.onReasoningDelta(delta.text)
                            is OpenAiWire.DeltaEvent.ToolCall ->
                                sink.onToolCallPartial(delta.index, delta.id, delta.name, delta.argsDelta)
                            is OpenAiWire.DeltaEvent.Error -> sink.onError(delta.message, retryable = false)
                        }
                    }
                    if (accum.firstErrorMessage != null) break
                }
            }
        } catch (e: java.io.IOException) {
            if (!cancellation.isCancelled) {
                val msg = "Stream interrupted: ${e.message}"
                sink.onError(msg, retryable = true)
                return ChatResult(
                    accum.result().text, accum.result().reasoning, accum.result().toolCalls,
                    FinishReason.ERROR, accum.result().usage, msg,
                )
            }
        }
        val result = accum.result()
        if (accum.firstErrorMessage != null) {
            return ChatResult(
                text = result.text,
                reasoning = result.reasoning,
                toolCalls = result.toolCalls,
                finishReason = FinishReason.ERROR,
                usage = result.usage,
                rawFinishDetail = accum.firstErrorMessage,
            )
        }
        sink.onUsage(result.usage)
        sink.onDone()
        return ChatResult(
            text = result.text,
            reasoning = result.reasoning,
            toolCalls = result.toolCalls,
            finishReason = if (cancellation.isCancelled) FinishReason.CANCELLED else result.finishReason,
            usage = result.usage,
            rawFinishDetail = accum.finishReason,
        )
    }

    override suspend fun testConnection(): ProviderTestResult {
        val key = credentials()
        if (key.isNullOrBlank()) return ProviderTestResult(false, "No API key configured")
        return try {
            val r = transport.get(modelsUrl(), authHeaders(key) + jsonHeaders(), timeoutMs = 20_000)
            if (r.isSuccess) ProviderTestResult(true, "Connected (${r.statusCode})")
            else ProviderTestResult(false, r.errorSummary())
        } catch (e: Exception) {
            ProviderTestResult(false, e.message ?: "Network error")
        }
    }

    protected fun jsonHeaders(): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json, text/event-stream",
    )

    protected fun reasoningLevels(vararg levels: ReasoningEffort): ReasoningMapping = ReasoningMapping(
        supported = levels.toSet(),
        wireValues = levels.associateWith { it.id },
        mechanism = ReasoningMechanism.EFFORT_ENUM,
    )
}
