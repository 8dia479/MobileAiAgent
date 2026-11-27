package com.mobileaiagent.data.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mobileaiagent.domain.model.ExecutionMode
import com.mobileaiagent.domain.model.ReasoningEffort
import com.mobileaiagent.domain.repo.AgentSettings
import com.mobileaiagent.domain.repo.SettingsRepository
import kotlinx.coroutines.flow.first
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore(name = "agent_settings")

/**
 * Settings backed by DataStore. The mapping is explicit and total: every field
 * round-trips, and corrupted values fall back to documented defaults instead of
 * crashing the agent.
 */
class SettingsRepositoryImpl(private val context: Context) : SettingsRepository {

    private object Keys {
        val defaultProvider = stringPreferencesKey("default_provider")
        val mainModel = stringPreferencesKey("main_model")
        val mainEffort = stringPreferencesKey("main_effort")
        val secondaryProvider = stringPreferencesKey("secondary_provider")
        val secondaryModel = stringPreferencesKey("secondary_model")
        val secondaryEffort = stringPreferencesKey("secondary_effort")
        val defaultMode = stringPreferencesKey("default_mode")
        val autoResume = booleanPreferencesKey("auto_resume_on_boot")
        val maxToolIterations = intPreferencesKey("max_tool_iterations")
        val maxTokens = intPreferencesKey("max_tokens")
        val temperature = stringPreferencesKey("temperature")
        val allowShell = booleanPreferencesKey("allow_shell")
        val allowShellChaining = booleanPreferencesKey("allow_shell_chaining")
        val shellWhitelist = stringPreferencesKey("shell_whitelist")
        val internetAccess = booleanPreferencesKey("internet_access")
        val backgroundEnabled = booleanPreferencesKey("background_enabled")
        val backgroundWifiOnly = booleanPreferencesKey("background_wifi_only")
        val maxConcurrentTasks = intPreferencesKey("max_concurrent_tasks")
        val privacyUploadApproval = booleanPreferencesKey("privacy_upload_approval")
        val remoteUrl = stringPreferencesKey("remote_server_url")
        val remoteToken = stringPreferencesKey("remote_server_token")
        val customBaseUrls = stringPreferencesKey("custom_base_urls")
        val searchEndpoint = stringPreferencesKey("search_endpoint")
        val disabledTools = stringPreferencesKey("disabled_tools")
    }

    override suspend fun snapshot(): AgentSettings {
        val prefs = try {
            context.settingsDataStore.data.first()
        } catch (e: IOException) {
            emptyPreferences()
        }
        return AgentSettings(
            defaultProviderId = prefs[Keys.defaultProvider] ?: "nvidia_nim",
            mainModel = prefs[Keys.mainModel] ?: "meta/llama-3.3-70b-instruct",
            mainReasoningEffort = ReasoningEffort.fromId(prefs[Keys.mainEffort]) ?: ReasoningEffort.MEDIUM,
            secondaryProviderId = prefs[Keys.secondaryProvider]?.takeIf { it.isNotBlank() },
            secondaryModel = prefs[Keys.secondaryModel]?.takeIf { it.isNotBlank() },
            secondaryReasoningEffort = prefs[Keys.secondaryEffort]?.let { ReasoningEffort.fromId(it) },
            defaultMode = prefs[Keys.defaultMode]?.let { m -> ExecutionMode.entries.firstOrNull { it.name == m } }
                ?: ExecutionMode.BUILD,
            autoResumeOnBoot = prefs[Keys.autoResume] ?: true,
            maxToolIterations = prefs[Keys.maxToolIterations] ?: 40,
            maxTokensPerRequest = prefs[Keys.maxTokens] ?: 4096,
            temperature = prefs[Keys.temperature]?.toDoubleOrNull() ?: 0.6,
            allowShell = prefs[Keys.allowShell] ?: false,
            allowShellChaining = prefs[Keys.allowShellChaining] ?: false,
            shellWhitelist = prefs[Keys.shellWhitelist]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: AgentSettings().shellWhitelist,
            internetAccess = prefs[Keys.internetAccess] ?: true,
            backgroundExecutionEnabled = prefs[Keys.backgroundEnabled] ?: true,
            backgroundWifiOnly = prefs[Keys.backgroundWifiOnly] ?: false,
            maxConcurrentTasks = prefs[Keys.maxConcurrentTasks] ?: 1,
            privacyRequireApprovalForUpload = prefs[Keys.privacyUploadApproval] ?: true,
            remoteServerUrl = prefs[Keys.remoteUrl]?.takeIf { it.isNotBlank() },
            remoteServerToken = prefs[Keys.remoteToken]?.takeIf { it.isNotBlank() },
            customBaseUrls = parseMap(prefs[Keys.customBaseUrls]),
            searchEndpoint = prefs[Keys.searchEndpoint]?.takeIf { it.isNotBlank() },
            toolOverridesDisabled = prefs[Keys.disabledTools]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                ?: emptySet(),
        )
    }

    override suspend fun update(transform: (AgentSettings) -> AgentSettings) {
        val current = snapshot()
        val next = transform(current)
        context.settingsDataStore.edit { prefs ->
            writeAll(prefs, next)
        }
    }

    private fun writeAll(prefs: MutablePreferences, s: AgentSettings) {
        prefs[Keys.defaultProvider] = s.defaultProviderId
        prefs[Keys.mainModel] = s.mainModel
        prefs[Keys.mainEffort] = s.mainReasoningEffort.id
        prefs[Keys.secondaryProvider] = s.secondaryProviderId ?: ""
        prefs[Keys.secondaryModel] = s.secondaryModel ?: ""
        prefs[Keys.secondaryEffort] = s.secondaryReasoningEffort?.id ?: ""
        prefs[Keys.defaultMode] = s.defaultMode.name
        prefs[Keys.autoResume] = s.autoResumeOnBoot
        prefs[Keys.maxToolIterations] = s.maxToolIterations
        prefs[Keys.maxTokens] = s.maxTokensPerRequest
        prefs[Keys.temperature] = s.temperature.toString()
        prefs[Keys.allowShell] = s.allowShell
        prefs[Keys.allowShellChaining] = s.allowShellChaining
        prefs[Keys.shellWhitelist] = s.shellWhitelist.joinToString(",")
        prefs[Keys.internetAccess] = s.internetAccess
        prefs[Keys.backgroundEnabled] = s.backgroundExecutionEnabled
        prefs[Keys.backgroundWifiOnly] = s.backgroundWifiOnly
        prefs[Keys.maxConcurrentTasks] = s.maxConcurrentTasks
        prefs[Keys.privacyUploadApproval] = s.privacyRequireApprovalForUpload
        prefs[Keys.remoteUrl] = s.remoteServerUrl ?: ""
        prefs[Keys.remoteToken] = s.remoteServerToken ?: ""
        prefs[Keys.customBaseUrls] = s.customBaseUrls.entries.joinToString("\n") { "${it.key}=${it.value}" }
        prefs[Keys.searchEndpoint] = s.searchEndpoint ?: ""
        prefs[Keys.disabledTools] = s.toolOverridesDisabled.joinToString(",")
    }

    private fun parseMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('\n').mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }.toMap()
    }
}

/** In-memory settings for tests and for the first-launch bootstrap. */
class InMemorySettingsRepository(initial: AgentSettings = AgentSettings()) : SettingsRepository {
    private var current = initial
    override suspend fun snapshot(): AgentSettings = current
    override suspend fun update(transform: (AgentSettings) -> AgentSettings) { current = transform(current) }
}

private fun <T> Preferences.getOrNull(key: Preferences.Key<T>): T? = this[key]
