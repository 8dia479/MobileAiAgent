package com.mobileaiagent.providers

import com.mobileaiagent.core.log.AgentLog
import com.mobileaiagent.domain.repo.AgentSettings
import com.mobileaiagent.domain.repo.CredentialStore
import com.mobileaiagent.domain.repo.ProviderRepository
import com.mobileaiagent.domain.repo.SettingsRepository
import com.mobileaiagent.providers.impl.AnthropicProvider
import com.mobileaiagent.providers.impl.GeminiProvider
import com.mobileaiagent.providers.impl.NvidiaNimProvider
import com.mobileaiagent.providers.impl.OpenAiCompatibleProvider
import com.mobileaiagent.providers.impl.OpenAiProvider
import com.mobileaiagent.providers.transport.HttpTransport

/**
 * Builds provider adapters from settings + encrypted credentials.
 *
 * Provider priority (spec §3): NVIDIA NIM (default) → OpenAI → Anthropic →
 * Gemini → OpenAI-compatible → custom. All are always *available to select*; a
 * provider simply reports `isConfigured() == false` until a key is stored.
 */
class ProviderRepositoryImpl(
    private val settings: SettingsRepository,
    private val credentials: CredentialStore,
    private val transport: HttpTransport,
) : ProviderRepository {

    @Volatile
    private var cachedSettings: AgentSettings? = null

    private suspend fun currentSettings(): AgentSettings =
        cachedSettings ?: settings.snapshot().also { cachedSettings = it }

    fun invalidate() { cachedSettings = null }

    private suspend fun keyFor(providerId: String): String? = credentials.getKey(providerId)

    override suspend fun providers(): Map<String, AiProvider> {
        val s = currentSettings()
        val result = LinkedHashMap<String, AiProvider>()
        result["nvidia_nim"] = NvidiaNimProvider(
            transport = transport,
            credentials = { runCatching { kotlinx.coroutines.runBlocking { keyFor("nvidia_nim") } }.getOrNull() },
            baseUrl = { s.baseUrlFor("nvidia_nim", ProviderKind.NVIDIA_NIM.defaultBaseUrl) },
        )
        result["openai"] = OpenAiProvider(
            transport = transport,
            credentials = { runCatching { kotlinx.coroutines.runBlocking { keyFor("openai") } }.getOrNull() },
            baseUrl = { s.baseUrlFor("openai", ProviderKind.OPENAI.defaultBaseUrl) },
        )
        result["anthropic"] = AnthropicProvider(
            transport = transport,
            credentials = { runCatching { kotlinx.coroutines.runBlocking { keyFor("anthropic") } }.getOrNull() },
            baseUrl = { s.baseUrlFor("anthropic", ProviderKind.ANTHROPIC.defaultBaseUrl) },
        )
        result["gemini"] = GeminiProvider(
            transport = transport,
            credentials = { runCatching { kotlinx.coroutines.runBlocking { keyFor("gemini") } }.getOrNull() },
            baseUrl = { s.baseUrlFor("gemini", ProviderKind.GEMINI.defaultBaseUrl) },
        )
        val compatibleBase = s.baseUrlFor("openai_compatible", "http://localhost:8080/v1")
        result["openai_compatible"] = OpenAiCompatibleProvider(
            id = "openai_compatible",
            displayName = "OpenAI-compatible endpoint",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            transport = transport,
            credentials = { runCatching { kotlinx.coroutines.runBlocking { keyFor("openai_compatible") } }.getOrNull() ?: "" },
            baseUrl = { compatibleBase },
            capabilities = ProviderCapabilities(streaming = true, toolCalling = true, imageInput = false),
        )
        val customBase = s.baseUrlFor("custom", "")
        result["custom"] = OpenAiCompatibleProvider(
            id = "custom",
            displayName = s.customBaseUrls["custom"]?.let { "Custom ($it)" } ?: "Custom provider",
            kind = ProviderKind.CUSTOM,
            transport = transport,
            credentials = { runCatching { kotlinx.coroutines.runBlocking { keyFor("custom") } }.getOrNull() ?: "" },
            baseUrl = { customBase },
            capabilities = ProviderCapabilities(streaming = true, toolCalling = true, imageInput = false),
        )
        return result
    }

    override suspend fun provider(id: String): AiProvider? = providers()[id]

    override suspend fun activeProviderId(): String = currentSettings().defaultProviderId

    override fun defaultBaseUrl(providerKindId: String): String = when (providerKindId) {
        "nvidia_nim" -> ProviderKind.NVIDIA_NIM.defaultBaseUrl
        "openai" -> ProviderKind.OPENAI.defaultBaseUrl
        "anthropic" -> ProviderKind.ANTHROPIC.defaultBaseUrl
        "gemini" -> ProviderKind.GEMINI.defaultBaseUrl
        else -> ""
    }

    suspend fun logStatus() {
        val configured = providers().filterValues { it.isConfigured() }.keys
        AgentLog.i("Providers", "Configured providers: ${configured.joinToString().ifEmpty { "none" }}")
    }
}
