package com.mobileaiagent.domain.repo

import com.mobileaiagent.providers.ModelCapabilities
import com.mobileaiagent.domain.model.ReasoningEffort
import com.mobileaiagent.providers.AiProvider
import com.mobileaiagent.providers.ModelDescriptor

/** Provider catalogue + credential access (implemented over settings/keystore). */
interface ProviderRepository {
    /** Snapshot of configured providers, keyed by our provider id. */
    suspend fun providers(): Map<String, AiProvider>
    suspend fun provider(id: String): AiProvider?
    suspend fun activeProviderId(): String
    fun defaultBaseUrl(providerKindId: String): String
}

/** Dynamic model discovery with cache + manual entries (never hardcoded lists). */
interface ModelCatalogRepository {
    suspend fun cachedModels(providerId: String): List<ModelDescriptor>
    suspend fun refreshModels(providerId: String): RefreshOutcome
    suspend fun upsertManualModel(model: ModelDescriptor)
    suspend fun setCapabilitiesOverride(providerId: String, modelId: String, caps: ModelCapabilities)
    suspend fun capabilitiesOverride(providerId: String, modelId: String): ModelCapabilities?
    suspend fun lastRefresh(providerId: String): Long?
}

sealed class RefreshOutcome {
    data class Refreshed(val count: Int) : RefreshOutcome()
    data class Failed(val message: String, val usedCache: Boolean) : RefreshOutcome()
    data object NoCredentials : RefreshOutcome()
}

/** User-configurable runtime settings the agent reads at task start. */
interface SettingsRepository {
    suspend fun snapshot(): AgentSettings
    suspend fun update(transform: (AgentSettings) -> AgentSettings)
}

data class AgentSettings(
    val defaultProviderId: String = "nvidia_nim",
    val mainModel: String = "meta/llama-3.3-70b-instruct",
    val mainReasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    val secondaryProviderId: String? = null,
    val secondaryModel: String? = null,
    val secondaryReasoningEffort: ReasoningEffort? = null,
    val defaultMode: com.mobileaiagent.domain.model.ExecutionMode = com.mobileaiagent.domain.model.ExecutionMode.BUILD,
    val autoResumeOnBoot: Boolean = true,
    val maxToolIterations: Int = 40,
    val maxTokensPerRequest: Int = 4096,
    val temperature: Double = 0.6,
    val allowShell: Boolean = false,
    /** Off by default: chaining is powerful and easy to misuse. */
    val allowShellChaining: Boolean = false,
    val shellWhitelist: List<String> = listOf("ls", "cat", "head", "tail", "wc", "grep", "find", "pwd", "echo", "date", "stat", "du", "sort", "uniq", "cut", "sed", "awk", "python3", "sh"),
    val internetAccess: Boolean = true,
    val backgroundExecutionEnabled: Boolean = true,
    val backgroundWifiOnly: Boolean = false,
    val maxConcurrentTasks: Int = 1,
    val privacyRequireApprovalForUpload: Boolean = true,
    val remoteServerUrl: String? = null,
    val remoteServerToken: String? = null,
    val customBaseUrls: Map<String, String> = emptyMap(),
    val searchEndpoint: String? = null,
    val toolOverridesDisabled: Set<String> = emptySet(),
) {
    fun baseUrlFor(providerId: String, default: String): String =
        customBaseUrls[providerId]?.takeIf { it.isNotBlank() } ?: default
}

interface CredentialStore {
    suspend fun getKey(providerId: String): String?
    suspend fun setKey(providerId: String, key: String?)
    suspend fun hasKey(providerId: String): Boolean
}

/** Project storage (filesystem layout + metadata). */
interface ProjectRepository {
    fun projectsRoot(): java.io.File
    fun projectDir(projectId: String): java.io.File
    suspend fun createProject(name: String, description: String? = null): com.mobileaiagent.domain.model.ProjectInfo
    suspend fun listProjects(): List<com.mobileaiagent.domain.model.ProjectInfo>
    suspend fun renameProject(projectId: String, newName: String): Boolean
    suspend fun deleteProject(projectId: String): Boolean
    suspend fun projectInfo(projectId: String): com.mobileaiagent.domain.model.ProjectInfo?
}

/** Network/battery gate consulted before starting heavy work. */
interface ResourceMonitor {
    fun isNetworkAvailable(): Boolean
    fun isUnmeteredNetwork(): Boolean
    fun isBatteryLow(): Boolean
    fun isPowerSaving(): Boolean
    fun availableDiskBytes(): Long
}
