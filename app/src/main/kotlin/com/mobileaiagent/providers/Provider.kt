package com.mobileaiagent.providers

import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.core.util.CancellationToken
import com.mobileaiagent.domain.model.ReasoningEffort
import java.io.Closeable

/**
 * Provider abstraction. Every adapter (NIM, OpenAI, Anthropic, Gemini,
 * OpenAI-compatible, custom) implements the same surface, and advertises what
 * it genuinely supports so the app never pretends a capability exists.
 */
interface AiProvider {
    val id: String
    val displayName: String
    val kind: ProviderKind
    /** True when the provider exposes a /models-style discovery endpoint. */
    val supportsModelDiscovery: Boolean
    val capabilities: ProviderCapabilities

    fun isConfigured(): Boolean

    suspend fun discoverModels(): ModelDiscoveryResult

    /**
     * Streams a chat completion. Implementations must call [sink] progressively
     * and honour [cancellation]. Returning normally means the turn completed.
     */
    suspend fun streamChat(
        request: ChatRequest,
        sink: StreamSink,
        cancellation: CancellationToken,
    ): ChatResult

    /**
     * Validates credentials without spending a completion.
     *
     * The default uses [discoverModels], which is a cheap, authenticated GET that
     * every supported provider exposes. That makes the test meaningful (a bad key
     * or a wrong base URL fails) instead of the previous stub, which always
     * reported success and told the user nothing.
     */
    suspend fun testConnection(): ProviderTestResult = try {
        when (val result = discoverModels()) {
            is ModelDiscoveryResult.Success -> ProviderTestResult(
                ok = true,
                message = "Connected. ${result.models.size} model(s) visible" +
                    (result.note?.let { " — $it" } ?: ""),
            )
            is ModelDiscoveryResult.Failure -> ProviderTestResult(
                ok = false,
                message = if (result.authFailure) {
                    "Rejected the credentials: ${result.message}"
                } else {
                    "Could not reach the provider: ${result.message}"
                },
            )
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ProviderTestResult(ok = false, message = "Connection test failed: ${e.message ?: e::class.simpleName}")
    }
}

enum class ProviderKind(val defaultBaseUrl: String, val credentialHint: String) {
    NVIDIA_NIM("https://integrate.api.nvidia.com/v1", "nvapi-…"),
    OPENAI("https://api.openai.com/v1", "sk-…"),
    ANTHROPIC("https://api.anthropic.com/v1", "sk-ant-…"),
    GEMINI("https://generativelanguage.googleapis.com/v1beta", "AIza…"),
    OPENAI_COMPATIBLE("", "provider-specific"),
    CUSTOM("", "provider-specific"),
}

data class ProviderCapabilities(
    val streaming: Boolean = true,
    val toolCalling: Boolean = false,
    val imageInput: Boolean = false,
    val fileInput: Boolean = false,
    val reasoningControl: Boolean = false,
    val structuredOutput: Boolean = false,
    /** How the provider expects reasoning effort to be expressed, if at all. */
    val reasoningMapping: ReasoningMapping? = null,
    val maxContextTokens: Int? = null,
    val supportsReasoningContent: Boolean = false,
    val supportsParallelToolCalls: Boolean = false,
)

/**
 * Declares exactly which of our six levels a provider accepts. Levels not listed
 * are surfaced as unsupported in the UI; nothing is silently converted.
 */
data class ReasoningMapping(
    val supported: Set<ReasoningEffort>,
    /**
     * Provider-native string for a level, or null if the level needs a
     * different mechanism (e.g. budget_tokens) handled by the adapter.
     */
    val wireValues: Map<ReasoningEffort, String> = emptyMap(),
    val mechanism: ReasoningMechanism = ReasoningMechanism.NONE,
)

enum class ReasoningMechanism {
    /** Level is sent as a `reasoning_effort` enum string. */
    EFFORT_ENUM,

    /** Level is translated to a token budget (Anthropic thinking.budget_tokens). */
    TOKEN_BUDGET,

    /** Level is sent as a numeric/other provider-specific parameter. */
    NATIVE_PARAM,

    NONE,
}

data class ChatRequest(
    val model: String,
    val reasoningEffort: ReasoningEffort?,
    val messages: List<ApiMessage>,
    val tools: List<ApiToolSpec> = emptyList(),
    val maxOutputTokens: Int = 4096,
    val temperature: Double? = 0.6,
    val topP: Double? = null,
    val stop: List<String> = emptyList(),
    val responseJsonSchema: JsonValue? = null,
    val extra: Map<String, JsonValue> = emptyMap(),
)

data class ApiMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ApiToolCall> = emptyList(),
    val images: List<InputImage> = emptyList(),
    val name: String? = null,
)

data class ApiToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/** Images carried as base64 OR remote URLs; adapters pick a supported form. */
data class InputImage(
    val mimeType: String,
    val base64: String? = null,
    val url: String? = null,
)

data class ApiToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonValue,
)

interface StreamSink {
    fun onStart() {}
    fun onTextDelta(text: String) {}
    fun onReasoningDelta(text: String) {}
    /** Called as tool call fragments arrive, so the UI can show progress. */
    fun onToolCallPartial(index: Int, id: String?, name: String?, argumentsDelta: String) {}
    fun onUsage(usage: Usage) {}
    fun onNotice(message: String) {}
    fun onError(message: String, retryable: Boolean) {}
    fun onDone() {}
}

data class Usage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val reasoningTokens: Long = 0,
)

data class ChatResult(
    val text: String,
    val reasoning: String?,
    val toolCalls: List<ApiToolCall>,
    val finishReason: FinishReason,
    val usage: Usage,
    val rawFinishDetail: String? = null,
)

enum class FinishReason { STOP, LENGTH, TOOL_CALLS, CONTENT_FILTER, ERROR, CANCELLED }

data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val providerId: String,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val capabilities: ModelCapabilities = ModelCapabilities.unknown(),
    val capabilitiesSource: CapabilitySource = CapabilitySource.UNKNOWN,
    val retired: Boolean = false,
    val raw: JsonValue? = null,
    val fetchedAt: Long = 0,
)

/** Capabilities are always tri-state: verified true, verified false, or unknown. */
data class ModelCapabilities(
    val vision: Boolean? = null,
    val toolCalling: Boolean? = null,
    val reasoning: Boolean? = null,
    val structuredOutput: Boolean? = null,
    val fileInput: Boolean? = null,
) {
    companion object {
        fun unknown() = ModelCapabilities()
    }
}

enum class CapabilitySource { VERIFIED_DISCOVERY, VERIFIED_MANUAL, INFERRED, UNKNOWN }

sealed class ModelDiscoveryResult {
    data class Success(val models: List<ModelDescriptor>, val note: String? = null) : ModelDiscoveryResult()
    data class Failure(val message: String, val authFailure: Boolean = false) : ModelDiscoveryResult()
}

data class ProviderTestResult(val ok: Boolean, val message: String)
