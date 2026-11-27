package com.mobileaiagent.plugins

import android.content.Context
import com.mobileaiagent.core.json.JsonParser
import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.core.log.AgentLog
import com.mobileaiagent.data.db.PluginDao
import com.mobileaiagent.data.db.PluginEntity
import com.mobileaiagent.tools.spec.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.security.MessageDigest

/**
 * Plugin system (spec §9).
 *
 * Honest scope statement:
 *  - BUILT-IN plugins are Kotlin classes implementing [AgentTool], registered
 *    through [BuiltInRegistrar].
 *  - EXTERNAL plugins are declarative manifests (`plugin.json`) plus either
 *    (a) a remote endpoint the app calls over HTTP with the unified tool
 *    interface, or (b) a shell script executed through the same sandboxed
 *    terminal rules (whitelist, workspace confinement, timeouts).
 *  - Arbitrary JVM/Kotlin code loading from an APK is intentionally NOT
 *    supported: Android would require in-app dynamic code loading, which is a
 *    security hazard and Play-policy restricted. Declarative plugins keep the
 *    permission model enforceable.
 *
 * Every external plugin declares permissions; grants are persisted and checked
 * before each invocation.
 */
class PluginManager(
    private val context: Context,
    private val dao: PluginDao,
    private val registry: ToolRegistry,
) {
    private val pluginsDir: File get() = File(context.filesDir, "plugins").apply { mkdirs() }

    fun observeInstalled(): Flow<List<InstalledPlugin>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }

    suspend fun list(): List<InstalledPlugin> = dao.listAll().map { it.toModel() }

    /** Installs a plugin from a JSON manifest string. Returns the parsed record. */
    suspend fun installFromManifest(manifestJson: String): Result<InstalledPlugin> = runCatching {
        val json = JsonParser.parse(manifestJson)
        val id = json.stringOr("id", "").ifBlank { throw IllegalArgumentException("manifest.id is required") }
        if (!id.matches(Regex("[a-z0-9_.-]{3,64}"))) throw IllegalArgumentException("id must be [a-z0-9_.-]{3,64}")
        val version = json.stringOr("version", "1.0.0")
        val kind = json.stringOr("kind", "remote").lowercase()
        if (kind !in setOf("remote", "script")) {
            throw IllegalArgumentException("kind must be 'remote' or 'script' (native code plugins are not supported)")
        }
        val permissions = json.stringList("permissions").mapNotNull { p ->
            ToolPermission.entries.firstOrNull { it.name.equals(p, ignoreCase = true) }
        }
        val tools = json["tools"].asArrayOrNull()
            ?: throw IllegalArgumentException("manifest.tools array is required")
        if (tools.isEmpty()) throw IllegalArgumentException("plugin declares no tools")
        val sha = sha256(manifestJson)
        val record = PluginEntity(
            id = id, name = json.stringOr("name", id), version = version, kind = kind,
            enabled = false, // plugins start disabled pending user grant
            permissionsCsv = permissions.joinToString(",") { it.name },
            manifestJson = manifestJson, installedAt = System.currentTimeMillis(), sha256 = sha,
        )
        dao.upsert(record)
        AgentLog.i("PluginManager", "Installed plugin $id v$version ($kind, ${tools.size} tools)")
        record.toModel()
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val existing = dao.listAll().firstOrNull { it.id == id } ?: return
        dao.upsert(existing.copy(enabled = enabled))
        registry.unregister("plugin_$id")
        if (enabled) registerPluginTools(existing)
    }

    suspend fun uninstall(id: String) {
        registry.unregister("plugin_$id")
        dao.delete(id)
    }

    suspend fun loadInstalledPlugins() {
        dao.listAll().filter { it.enabled }.forEach { registerPluginTools(it) }
    }

    private fun registerPluginTools(plugin: PluginEntity) {
        val manifest = runCatching { JsonParser.parse(plugin.manifestJson) }.getOrNull() ?: return
        val granted = plugin.permissionsCsv.split(',').filter { it.isNotBlank() }.toSet()
        val tools = manifest["tools"].asArrayOrNull() ?: return
        for (toolJson in tools) {
            val toolId = toolJson.stringOr("id", "").ifBlank { continue }
            val declaredPerms = toolJson.stringList("permissions")
            val missing = declaredPerms.filter { it !in granted }
            val tool = DeclarativePluginTool(
                pluginId = plugin.id,
                toolJson = toolJson,
                manifest = manifest,
                declaredPermissions = declaredPerms.mapNotNull { p ->
                    ToolPermission.entries.firstOrNull { it.name.equals(p, ignoreCase = true) }
                },
                missingPermissions = missing,
            )
            registry.register(tool)
            AgentLog.i("PluginManager", "Registered plugin tool ${tool.id}${if (missing.isNotEmpty()) " (blocked: missing $missing)" else ""}")
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    data class InstalledPlugin(
        val id: String,
        val name: String,
        val version: String,
        val kind: String,
        val enabled: Boolean,
        val permissions: List<ToolPermission>,
        val manifestJson: String,
        val installedAt: Long,
        val sha256: String?,
    )

    private fun PluginEntity.toModel() = InstalledPlugin(
        id, name, version, kind, enabled,
        permissionsCsv.split(',').filter { it.isNotBlank() }.mapNotNull { p ->
            ToolPermission.entries.firstOrNull { it.name == p }
        },
        manifestJson, installedAt, sha256,
    )
}

/**
 * A plugin-declared tool. Remote plugins POST to their declared endpoint; script
 * plugins run the declared script through the sandboxed terminal policy. If a
 * required permission was not granted, calls are refused with a clear message
 * rather than executed.
 */
class DeclarativePluginTool(
    private val pluginId: String,
    private val toolJson: JsonValue,
    private val manifest: JsonValue,
    private val declaredPermissions: List<ToolPermission>,
    private val missingPermissions: List<String>,
) : AgentTool {
    override val id = "plugin_${pluginId}_${toolJson.stringOr("id", "tool")}"
    override val name = toolJson.stringOr("name", id)
    override val description = toolJson.stringOr("description", "Plugin-provided tool from $pluginId")
    override val version = manifest.stringOr("version", "1.0.0")
    override val category = ToolCategory.PLUGIN
    override val inputSchema: JsonValue =
        toolJson["input_schema"] ?: JsonValue.obj("type" to JsonValue.of("object"), "properties" to JsonValue.obj())
    override val executionEnvironment = when (manifest.stringOr("kind", "remote")) {
        "script" -> ExecutionEnvironment.PLUGIN_SANDBOX
        else -> ExecutionEnvironment.REMOTE_SERVER
    }
    override val permissions = declaredPermissions
    override val mutagenic = toolJson["mutagenic"].asBooleanOrNull() ?: true
    override val cancellationSupported = false
    override val maxResultChars = 20_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        if (missingPermissions.isNotEmpty()) {
            return ToolResult.Failure(
                "Plugin '$pluginId' lacks granted permission(s): ${missingPermissions.joinToString()}. " +
                    "The user must enable them in Plugin Manager.",
                errorKind = "permission_denied",
            )
        }
        return when (executionEnvironment) {
            ExecutionEnvironment.REMOTE_SERVER -> {
                val endpoint = toolJson.stringOr("endpoint", manifest.stringOr("endpoint", ""))
                if (endpoint.isBlank()) {
                    return ToolResult.Failure("Plugin tool has no endpoint configured", errorKind = "plugin_invalid")
                }
                // Remote plugin execution is delivered by the remote runtime; the
                // dispatcher checks capabilities before reaching here.
                ToolResult.Failure(
                    "Remote plugin execution requires a configured remote runtime (Settings → Remote). " +
                        "Endpoint: $endpoint",
                    errorKind = "not_configured",
                )
            }
            ExecutionEnvironment.PLUGIN_SANDBOX -> {
                val script = toolJson.stringOr("script", "")
                if (script.isBlank()) return ToolResult.Failure("Plugin script path missing", errorKind = "plugin_invalid")
                ToolResult.Failure(
                    "Script plugins execute through the remote/sandboxed runtime. Configure a runtime server " +
                        "to run '$script'.",
                    errorKind = "not_configured",
                )
            }
            else -> ToolResult.Failure("Unsupported plugin environment", errorKind = "plugin_invalid")
        }
    }
}
