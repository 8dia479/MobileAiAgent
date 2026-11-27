package com.mobileaiagent.tools.spec

import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.core.util.CancellationToken
import java.io.File

/**
 * The single tool interface implemented by every built-in, plugin-provided and
 * remote tool. Local and remote runtimes therefore expose identical semantics.
 */
interface AgentTool {
    val id: String
    val name: String
    val description: String
    val version: String
    val category: ToolCategory
    /** JSON Schema (subset) describing arguments. Used verbatim in model prompts. */
    val inputSchema: JsonValue
    val executionEnvironment: ExecutionEnvironment
    val permissions: List<ToolPermission>
    /** True when the operation can mutate data; recovery must not auto-replay these. */
    val mutagenic: Boolean
    val cancellationSupported: Boolean
    /** Conservative token/character budget hint for the result when sent back to the model. */
    val maxResultChars: Int

    suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult

    suspend fun cancelTask(context: ToolContext) { /* optional */ }

    fun shortSignature(): String = "$id@$version"
}

enum class ToolCategory(val label: String) {
    SYSTEM("System"),
    TERMINAL("Terminal"),
    FILES("Files"),
    ARCHIVE("Archive"),
    CAD("CAD"),
    MODELING_3D("3D Modeling"),
    ANIMATION("Animation"),
    GRAPHICS_2D("2D Graphics"),
    RESEARCH("Research"),
    PRODUCTIVITY("Productivity"),
    PLANNING("Planning"),
    DELEGATION("Delegation"),
    WORKSPACE("Workspace"),
    PLUGIN("Plugin"),
    REMOTE("Remote"),
}

enum class ExecutionEnvironment {
    /** Runs inside the app process on the device. */
    LOCAL_ANDROID,

    /** Runs on the optional remote server. */
    REMOTE_SERVER,

    /** Runs in a child process / sandbox provided by a plugin. */
    PLUGIN_SANDBOX,

    /** Implemented purely in-process but needs user-visible approval gates. */
    LOCAL_WITH_APPROVAL,
}

enum class ToolPermission(val label: String) {
    FILES_READ("Read project files"),
    FILES_WRITE("Write project files"),
    FILES_DELETE("Delete files"),
    SHELL("Execute shell commands"),
    NETWORK("Access the network"),
    CAMERA("Use camera"),
    CONTENT_URI("Access user-selected documents"),
    GPU_HEAVY("Heavy compute"),
    EXTERNAL_APP("Launch external apps"),
}

data class ToolContext(
    val taskId: String,
    val runId: String,
    val projectId: String,
    val projectRoot: File?,
    /** Virtual, sandboxed root of the per-project workspace; null on plain JVM. */
    val workspace: ToolWorkspace?,
    val cancellation: CancellationToken,
    val log: (String) -> Unit,
    val permissionCheck: (ToolPermission) -> Boolean = { true },
    val requestApproval: suspend (ApprovalRequest) -> ApprovalOutcome = { ApprovalOutcome.Approved("auto") },
    val emitProgress: (String, Float?) -> Unit = { _, _ -> },
    val emitArtifact: (ArtifactRef) -> Unit = { _ -> },
    val settingsSnapshot: ToolRuntimeSettings = ToolRuntimeSettings(),
    val platform: ToolPlatform = ToolPlatform.JVM_TEST,
)

enum class ToolPlatform { ANDROID, JVM_TEST, REMOTE }

data class ToolRuntimeSettings(
    val allowShell: Boolean = false,
    val shellWhitelist: Set<String> = emptySet(),
    /** Whether `a && b` / `a | b` may run. Off by default; each segment is still validated. */
    val allowShellChaining: Boolean = false,
    /** Whether chained commands force a user approval prompt even if not requested. */
    val requireApprovalForChaining: Boolean = true,
    val maxFileWriteBytes: Long = 64L * 1024 * 1024,
    val internetAccess: Boolean = true,
    val searchEndpoint: String? = null,
)

data class ApprovalRequest(
    val title: String,
    val description: String,
    val risk: RiskLevel,
    val destructive: Boolean,
    val previewArtifacts: List<String> = emptyList(),
)

enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

sealed class ApprovalOutcome {
    data class Approved(val reason: String) : ApprovalOutcome()
    data class Denied(val reason: String) : ApprovalOutcome()
    data object TimedOut : ApprovalOutcome()
}

data class ArtifactRef(
    val path: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long = 0,
)

sealed class ToolResult {
    data class Success(
        val content: String,
        val structured: JsonValue? = null,
        val artifacts: List<ArtifactRef> = emptyList(),
        val durationMs: Long = 0,
        val truncated: Boolean = false,
    ) : ToolResult()

    data class Failure(
        val message: String,
        /** Whether re-running with more context could succeed (agent may retry). */
        val retryable: Boolean = false,
        val errorKind: String = "tool_error",
        val structured: JsonValue? = null,
    ) : ToolResult()

    data object Cancelled : ToolResult()

    data object NeedsApproval : ToolResult()

    fun render(maxChars: Int = 20_000): String = when (this) {
        is Success -> if (content.length > maxChars) {
            content.take(maxChars) + "\n…[tool output truncated at $maxChars chars]"
        } else content
        is Failure -> "ERROR($errorKind): $message"
        Cancelled -> "ERROR(cancelled): tool was cancelled"
        NeedsApproval -> "ERROR(needs_approval): user approval required"
    }
}

/**
 * Abstracted file operations so tool code is identical on-device and in tests.
 */
interface ToolWorkspace {
    fun exists(relativePath: String): Boolean
    fun isDirectory(relativePath: String): Boolean
    fun readText(relativePath: String, maxBytes: Long = 4L * 1024 * 1024): String
    fun readBytes(relativePath: String, maxBytes: Long = 64L * 1024 * 1024): ByteArray
    fun writeText(relativePath: String, content: String, overwrite: Boolean = true): String
    fun writeBytes(relativePath: String, bytes: ByteArray, overwrite: Boolean = true): String
    /** Appends to a file, creating it when absent. */
    fun appendText(relativePath: String, content: String): String
    fun list(relativePath: String = "", recursive: Boolean = false): List<WorkspaceEntry>
    fun delete(relativePath: String, recursive: Boolean = false): Boolean
    fun move(from: String, to: String): String
    fun copy(from: String, to: String): String
    fun mkdir(relativePath: String): String
    fun absolutePath(relativePath: String): String
    fun sizeBytes(relativePath: String): Long
    /** Normalises + validates a path stays inside the workspace. Throws on escape. */
    fun resolveSafe(relativePath: String): java.io.File
}

data class WorkspaceEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
)

class ToolInputException(message: String) : Exception(message)

/**
 * Small helper that validates/coerces tool arguments with precise error
 * messages. Tool authors never touch raw maps directly.
 */
class ToolArgs(private val json: JsonValue) {
    private fun raw(key: String): JsonValue = json[key]

    fun string(key: String, required: Boolean = true, default: String? = null): String {
        val v = raw(key)
        val s = v.asStringOrNull()
        if (s == null) {
            if (required) throw ToolInputException("Missing required string argument '$key' (got ${v.typeName()})")
            return default ?: ""
        }
        return s
    }

    fun optionalString(key: String): String? = raw(key).asStringOrNull()

    fun int(key: String, required: Boolean = true, default: Int = 0): Int {
        val v = raw(key)
        val i = v.asIntOrNull()
        if (i == null) {
            if (required) throw ToolInputException("Missing required integer argument '$key'")
            return default
        }
        return i
    }

    fun double(key: String, required: Boolean = true, default: Double = 0.0): Double {
        val d = raw(key).asDoubleOrNull()
        if (d == null) {
            if (required) throw ToolInputException("Missing required number argument '$key'")
            return default
        }
        return d
    }

    fun boolean(key: String, default: Boolean = false): Boolean = raw(key).asBooleanOrNull() ?: default

    fun array(key: String, required: Boolean = true): List<JsonValue> {
        val a = raw(key).asArrayOrNull()
        if (a == null) {
            if (required) throw ToolInputException("Missing required array argument '$key'")
            return emptyList()
        }
        return a
    }

    fun objectArg(key: String): JsonValue = raw(key)

    fun has(key: String): Boolean = !raw(key).isNull

    fun rawJson(): JsonValue = json

    fun validateRequired(vararg keys: String) {
        keys.forEach { if (!has(it)) throw ToolInputException("Missing required argument '$it'") }
    }

    override fun toString(): String = json.toJson()
}

/** Convenience DSL for declaring JSON-schema input objects. */
class SchemaBuilder {
    private val props = LinkedHashMap<String, JsonValue>()
    private val required = mutableListOf<String>()

    fun required(name: String, schema: JsonValue): SchemaBuilder {
        props[name] = schema
        required.add(name)
        return this
    }

    fun optional(name: String, schema: JsonValue): SchemaBuilder {
        props[name] = schema
        return this
    }

    fun requiredString(name: String, description: String, enumValues: List<String>? = null) =
        required(name, stringSchema(description, enumValues))

    fun optionalString(name: String, description: String, enumValues: List<String>? = null) =
        optional(name, stringSchema(description, enumValues))

    fun optionalBool(name: String, description: String) =
        optional(name, obj("type" to JsonValue.of("boolean"), "description" to JsonValue.of(description)))

    fun requiredInt(name: String, description: String) =
        required(name, obj("type" to JsonValue.of("integer"), "description" to JsonValue.of(description)))

    fun optionalInt(name: String, description: String, default: Int? = null) =
        optional(
            name,
            obj(
                "type" to JsonValue.of("integer"),
                "description" to JsonValue.of(description),
                *if (default != null) arrayOf("default" to JsonValue.of(default)) else emptyArray(),
            ),
        )

    fun requiredNumber(name: String, description: String) =
        required(name, obj("type" to JsonValue.of("number"), "description" to JsonValue.of(description)))

    fun optionalNumber(name: String, description: String) =
        optional(name, obj("type" to JsonValue.of("number"), "description" to JsonValue.of(description)))

    fun requiredArray(name: String, description: String) =
        required(name, obj("type" to JsonValue.of("array"), "description" to JsonValue.of(description)))

    fun optionalArray(name: String, description: String) =
        optional(name, obj("type" to JsonValue.of("array"), "description" to JsonValue.of(description)))

    fun build(): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "type" to JsonValue.of("object"),
            "properties" to JsonValue.Obj(LinkedHashMap(props)),
            "required" to JsonValue.Arr(required.map { JsonValue.of(it) }),
            "additionalProperties" to JsonValue.of(false),
        )
    )

    companion object {
        fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue.Obj =
            JsonValue.Obj(linkedMapOf(*pairs))

        fun stringSchema(description: String, enumValues: List<String>? = null): JsonValue {
            val base = linkedMapOf<String, JsonValue>(
                "type" to JsonValue.of("string"),
                "description" to JsonValue.of(description),
            )
            if (enumValues != null) base["enum"] = JsonValue.Arr(enumValues.map { JsonValue.of(it) })
            return JsonValue.Obj(base)
        }
    }
}

fun schema(block: SchemaBuilder.() -> Unit): JsonValue = SchemaBuilder().apply(block).build()
