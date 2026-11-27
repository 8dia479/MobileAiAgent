package com.mobileaiagent.providers.impl

import com.mobileaiagent.domain.model.ReasoningEffort
import com.mobileaiagent.providers.CapabilitySource
import com.mobileaiagent.providers.ModelCapabilities
import com.mobileaiagent.providers.ProviderCapabilities
import com.mobileaiagent.providers.ProviderKind
import com.mobileaiagent.providers.ReasoningMapping
import com.mobileaiagent.providers.ReasoningMechanism
import com.mobileaiagent.providers.transport.HttpTransport
import com.mobileaiagent.core.json.JsonValue

/**
 * NVIDIA NIM adapter (default provider).
 *
 * NIM endpoints are OpenAI chat-completions compatible, so transport/streaming
 * are inherited. What differs:
 *  - base URL default https://integrate.api.nvidia.com/v1
 *  - model discovery via GET /models (no capability metadata -> INFERRED/UNKNOWN)
 *  - reasoning control is model-dependent: only models that expose a
 *    `reasoning_effort`/`reasoning` parameter accept it. We expose the levels and
 *    let the user map them; unsupported levels are omitted, never substituted.
 */
class NvidiaNimProvider(
    transport: HttpTransport,
    credentials: () -> String?,
    baseUrl: () -> String = { ProviderKind.NVIDIA_NIM.defaultBaseUrl },
    /** modelId -> set of levels the operator verified as supported */
    private val verifiedReasoningModels: () -> Map<String, Set<ReasoningEffort>> = { emptyMap() },
    private val verifiedCapabilities: () -> Map<String, ModelCapabilities> = { emptyMap() },
) : OpenAiCompatibleProvider(
    id = "nvidia_nim",
    displayName = "NVIDIA NIM",
    kind = ProviderKind.NVIDIA_NIM,
    transport = transport,
    credentials = credentials,
    baseUrl = baseUrl,
    capabilities = ProviderCapabilities(
        streaming = true,
        toolCalling = true,
        imageInput = true,
        reasoningControl = true,
        structuredOutput = true,
        reasoningMapping = ReasoningMapping(
            supported = ReasoningEffort.entries.toSet(),
            mechanism = ReasoningMechanism.EFFORT_ENUM,
        ),
        supportsReasoningContent = true,
    ),
) {

    override fun reasoningMappingFor(model: String): ReasoningMapping? {
        val verified = verifiedReasoningModels()[model] ?: return null
        if (verified.isEmpty()) return null
        return ReasoningMapping(
            supported = verified,
            wireValues = verified.associateWith { effort ->
                when (effort) {
                    ReasoningEffort.XHIGH -> "xhigh"
                    else -> effort.id
                }
            },
            mechanism = ReasoningMechanism.EFFORT_ENUM,
        )
    }

    override fun inferCapabilities(modelId: String, raw: JsonValue?): ModelCapabilities {
        val verified = verifiedCapabilities()[modelId]
        if (verified != null) return verified
        val inferred = super.inferCapabilities(modelId, raw)
        // NIM hosts many text-only and vision language models; be conservative.
        val lower = modelId.lowercase()
        return inferred.copy(
            vision = inferred.vision ?: when {
                lower.contains("vl") || lower.contains("vision") || lower.contains("llava") ||
                    lower.contains("pixtral") || lower.contains("vila") -> null
                else -> null
            },
            // Tool calling is supported by most NIM chat endpoints but not all;
            // mark unknown unless verified, and let the user override per model.
            toolCalling = inferred.toolCalling,
        )
    }

    fun verifiedSource(): CapabilitySource = CapabilitySource.VERIFIED_MANUAL
}
