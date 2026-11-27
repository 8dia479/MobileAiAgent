package com.mobileaiagent.providers.impl

import com.mobileaiagent.domain.model.ReasoningEffort
import com.mobileaiagent.providers.ModelCapabilities
import com.mobileaiagent.providers.ProviderCapabilities
import com.mobileaiagent.providers.ProviderKind
import com.mobileaiagent.providers.ReasoningMapping
import com.mobileaiagent.providers.ReasoningMechanism
import com.mobileaiagent.providers.transport.HttpTransport
import com.mobileaiagent.core.json.JsonValue

/**
 * OpenAI adapter. Reasoning-capable model families (o-series, gpt-5*) accept
 * `reasoning_effort`; other models do not, so we only advertise effort levels
 * for families that document them. Notably `minimal` is not one of the six
 * levels, and `high` is the provider ceiling for classic o-series: we report
 * xHigh/Max as unsupported rather than silently downgrading.
 */
class OpenAiProvider(
    transport: HttpTransport,
    credentials: () -> String?,
    baseUrl: () -> String = { ProviderKind.OPENAI.defaultBaseUrl },
) : OpenAiCompatibleProvider(
    id = "openai",
    displayName = "OpenAI",
    kind = ProviderKind.OPENAI,
    transport = transport,
    credentials = credentials,
    baseUrl = baseUrl,
    capabilities = ProviderCapabilities(
        streaming = true,
        toolCalling = true,
        imageInput = true,
        fileInput = true,
        reasoningControl = true,
        structuredOutput = true,
        reasoningMapping = ReasoningMapping(
            supported = setOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            wireValues = mapOf(
                ReasoningEffort.NONE to "minimal",
                ReasoningEffort.LOW to "low",
                ReasoningEffort.MEDIUM to "medium",
                ReasoningEffort.HIGH to "high",
            ),
            mechanism = ReasoningMechanism.EFFORT_ENUM,
        ),
        supportsReasoningContent = true,
    ),
) {

    override fun reasoningMappingFor(model: String): ReasoningMapping? {
        val lower = model.lowercase()
        val reasoningFamily = lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4") ||
            lower.startsWith("gpt-5") || lower.contains("gpt-5") || lower.startsWith("codex")
        if (!reasoningFamily) return null
        return ReasoningMapping(
            supported = setOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            wireValues = mapOf(
                ReasoningEffort.NONE to "minimal",
                ReasoningEffort.LOW to "low",
                ReasoningEffort.MEDIUM to "medium",
                ReasoningEffort.HIGH to "high",
            ),
            mechanism = ReasoningMechanism.EFFORT_ENUM,
        )
    }

    override fun inferCapabilities(modelId: String, raw: JsonValue?): ModelCapabilities {
        val lower = modelId.lowercase()
        val vision = lower.startsWith("gpt-4o") || lower.startsWith("gpt-4.1") || lower.startsWith("gpt-5") ||
            lower.startsWith("o3") || lower.startsWith("o4") || lower.startsWith("chatgpt-4o")
        val reasoning = lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4") ||
            lower.startsWith("gpt-5")
        return ModelCapabilities(
            vision = vision,
            toolCalling = true,
            reasoning = reasoning,
            structuredOutput = true,
            fileInput = vision,
        )
    }
}
