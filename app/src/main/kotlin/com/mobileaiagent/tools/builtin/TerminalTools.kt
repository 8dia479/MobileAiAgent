package com.mobileaiagent.tools.builtin

import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.tools.spec.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sandboxed terminal.
 *
 * Design constraints (spec §12):
 *  - the working directory is always inside the project workspace, never `/`
 *  - every pipeline segment is validated against the allow-list
 *  - command substitution and a fixed set of destructive patterns are rejected
 *  - output is bounded and the process is killed on timeout or cancellation
 *  - the whole capability is off unless the user enables it in Settings
 *
 * Three actions:
 *  - `run`      — execute one command line
 *  - `env`      — report what this device actually has (shell, binaries, Python)
 *  - `sessions` — list previously run commands so the agent can re-run or build
 *                 on them without the user retyping (a shell's state cannot be
 *                 preserved across tool calls: each call is a fresh process, so
 *                 we keep *commands*, not live variables)
 *
 * Honest limits, surfaced in the result rather than hidden:
 *  - there is no persistent shell; `cd` and variable assignments do not survive
 *    between calls (use the `cwd` argument instead)
 *  - Python is only present if the user installed Termux and the binary is on
 *    the allow-list
 *  - the process runs as the app UID; it cannot see other apps' data or root
 */
class TerminalTool : AgentTool {
    override val id = "terminal"
    override val name = "Run shell command"
    override val description =
        "Run commands inside the project workspace. Actions: 'run' (default) executes a command, " +
            "'env' reports available binaries/Python on this device, 'sessions' lists recent commands. " +
            "Disabled by default; the user must enable shell access in Settings. Every pipeline " +
            "segment must be in the allow-list. No state persists between calls (use 'cwd')."
    override val version = "1.1.0"
    override val category = ToolCategory.TERMINAL
    override val inputSchema = schema {
        requiredString("action", "run | env | sessions", listOf("run", "env", "sessions"))
        optionalString("command", "For action=run: the command line, e.g. 'python3 script.py'.")
        optionalString("cwd", "Working directory relative to project root (default project root).")
        optionalInt("timeout_seconds", "Max runtime, 1-300 (default 60).")
        optionalBool("require_approval", "Ask the user before running (default false unless the setting demands it).")
        optionalString("stdin", "Text to feed to the process on stdin (max 64 KB).")
        optionalString("env_vars", "Extra environment variables as 'K=V,K2=V2' (max 16).")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_WITH_APPROVAL
    override val permissions = listOf(ToolPermission.SHELL, ToolPermission.FILES_READ)
    override val mutagenic = true
    override val cancellationSupported = true
    override val maxResultChars = 24_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
      try {
        val args = ToolArgs(input)
        val settings = ctx.settingsSnapshot
        if (!settings.allowShell) {
            return ToolResult.Failure(
                "Shell access is disabled. The user can enable it in Settings → Tools → Terminal after accepting the risk.",
                errorKind = "disabled",
            )
        }
        val root = ctx.projectRoot ?: return ToolResult.Failure("No project workspace", errorKind = "no_workspace")
        return when (args.string("action", required = false, default = "run")) {
            "env" -> reportEnvironment(root, settings.shellWhitelist.toSet())
            "sessions" -> listSessions()
            "run" -> runCommand(args, ctx, root, settings)
            else -> ToolResult.Failure("Unknown action. Use run | env | sessions.", errorKind = "invalid_arguments")
        }
      } catch (e: ToolInputException) {
        return ToolResult.Failure(e.message ?: "Invalid arguments", errorKind = "invalid_arguments")
      }
    }
}

// ------------------------------------------------------------------- sessions

/**
 * Recent commands, in memory for the lifetime of the app process.
 *
 * Every tool call starts a new `/bin/sh -c`, so environment state cannot be
 * carried over; this records *what was run* (the command, cwd and exit code) so
 * the agent can re-run a command, reference its output, or continue a workflow.
 */
object ShellHistory {
    data class Entry(
        val seq: Int,
        val command: String,
        val cwd: String,
        val exitCode: Int,
        val durationMs: Long,
        val at: Long,
    )

    private const val MAX = 40
    private val entries = ArrayDeque<Entry>()
    private var counter = 0

    @Synchronized
    fun record(command: String, cwd: String, exitCode: Int, durationMs: Long): Entry {
        counter += 1
        val entry = Entry(counter, command, cwd, exitCode, durationMs, System.currentTimeMillis())
        entries.addLast(entry)
        while (entries.size > MAX) entries.removeFirst()
        return entry
    }

    @Synchronized
    fun recent(limit: Int = 20): List<Entry> = entries.toList().takeLast(limit)

    @Synchronized
    fun clear() = entries.clear()
}

// ---------------------------------------------------------------- execution

private suspend fun TerminalTool.runCommand(
    args: ToolArgs,
    ctx: ToolContext,
    root: File,
    settings: ToolRuntimeSettings,
): ToolResult {
    val command = args.string("command").trim()
    val allowList = settings.shellWhitelist
    val allowChaining = settings.allowShellChaining

    when (val verdict = TerminalPolicy.validate(command, allowList, allowChaining)) {
        is TerminalPolicy.Verdict.Rejected -> return ToolResult.Failure(verdict.reason, errorKind = verdict.errorKind)
        is TerminalPolicy.Verdict.Allowed -> Unit
    }

    val cwd = args.optionalString("cwd")?.let {
        runCatching { ctx.workspace?.resolveSafe(it) }.getOrNull()
    } ?: root
    if (!cwd.exists() || !cwd.isDirectory) {
        return ToolResult.Failure("Working directory does not exist: ${cwd.path}", errorKind = "not_found")
    }
    val canonicalRoot = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
    val canonicalCwd = runCatching { cwd.canonicalPath }.getOrDefault(cwd.absolutePath)
    if (canonicalCwd != canonicalRoot && !canonicalCwd.startsWith(canonicalRoot + File.separator)) {
        return ToolResult.Failure("Working directory escapes the project", errorKind = "security")
    }

    val stdin = args.optionalString("stdin")
    if (stdin != null && stdin.toByteArray().size > 64 * 1024) {
        return ToolResult.Failure("stdin exceeds the 64 KB limit", errorKind = "invalid_arguments")
    }
    val extraEnv = TerminalPolicy.parseEnv(args.optionalString("env_vars"))
        ?: return ToolResult.Failure(
            "env_vars must be at most 16 'KEY=VALUE' pairs separated by commas, with plain keys",
            errorKind = "invalid_arguments",
        )

    val requestedApproval = args.boolean("require_approval", false)
    // Chained commands and shell access in general are riskier; always ask when
    // the user asked us to, or when the command chains several binaries.
    val needsApproval = requestedApproval || (TerminalPolicy.hasChaining(command) && settings.requireApprovalForChaining)
    if (needsApproval) {
        when (val approval = ctx.requestApproval(
            ApprovalRequest(
                title = "Run shell command",
                description = command.take(400),
                risk = RiskLevel.MEDIUM,
                destructive = false,
            ),
        )) {
            is ApprovalOutcome.Approved -> Unit
            is ApprovalOutcome.Denied -> return ToolResult.Failure("User denied command execution", errorKind = "denied")
            ApprovalOutcome.TimedOut -> return ToolResult.Failure("Approval timed out", errorKind = "denied")
        }
    }

    val timeoutSec = args.int("timeout_seconds", required = false, default = 60).coerceIn(1, 300)
    ctx.emitProgress("Running: ${command.take(60)}", null)

    val result = withContext(Dispatchers.IO) {
        ShellRunner.run(
            command = command,
            cwd = cwd,
            timeoutSeconds = timeoutSec,
            stdin = stdin,
            extraEnv = extraEnv,
            rootForEnv = canonicalRoot,
            cancellation = ctx.cancellation,
        )
    }
    ShellHistory.record(command, relativeTo(canonicalRoot, canonicalCwd), result.exitCode, result.durationMs)

    val body = buildString {
        appendLine("$ $command")
        appendLine(
            "(exit ${if (result.timedOut) "timeout" else result.exitCode.toString()}, " +
                "${result.durationMs} ms, cwd ${relativeTo(canonicalRoot, canonicalCwd)})",
        )
        if (result.stdout.isNotBlank()) {
            appendLine("--- stdout ---")
            appendLine(result.stdout.trimEnd())
        }
        if (result.stderr.isNotBlank()) {
            appendLine("--- stderr ---")
            appendLine(result.stderr.trimEnd())
        }
        if (result.stdout.isBlank() && result.stderr.isBlank()) appendLine("(no output)")
        if (result.truncatedStdout || result.truncatedStderr) {
            appendLine("(output was truncated; redirect to a file and read it in chunks if you need the rest)")
        }
    }
    return when {
        result.timedOut -> ToolResult.Failure(
            "Command timed out after ${timeoutSec}s and was killed\n$body",
            errorKind = "timeout",
        )
        result.exitCode != 0 -> ToolResult.Failure(
            "Command failed with exit code ${result.exitCode}\n$body",
            retryable = true,
            errorKind = "exit_code",
        )
        else -> ToolResult.Success(body, durationMs = result.durationMs)
    }
}

private fun relativeTo(root: String, path: String): String =
    if (path == root) "." else path.removePrefix(root).trimStart(File.separatorChar).ifEmpty { "." }

// ------------------------------------------------------------------ discovery

/**
 * Reports what the device actually provides instead of assuming.
 *
 * This matters because "python3 script.py" is a normal thing for a model to try,
 * and on stock Android python3 does not exist. Telling the model up front
 * avoids a pointless failed call.
 */
private suspend fun TerminalTool.reportEnvironment(root: File, allowList: Set<String>): ToolResult {
    val report = withContext(Dispatchers.IO) {
        val shell = ShellRunner.resolveShell()
        val candidates = listOf(
            "sh", "ls", "cat", "grep", "find", "sed", "awk", "sort", "uniq", "head", "tail", "wc",
            "python3", "python", "node", "git", "curl", "wget", "unzip", "zip", "tar", "ffmpeg", "magick",
        )
        val available = candidates.filter { binary ->
            ShellRunner.run("command -v $binary", root, timeoutSeconds = 5).let { r ->
                r.exitCode == 0 && r.stdout.isNotBlank()
            }
        }
        val missing = candidates - available.toSet()
        val pythonVersion = if ("python3" in available) {
            ShellRunner.run("python3 --version", root, timeoutSeconds = 5).let { r ->
                (r.stdout.ifBlank { r.stderr }).trim()
            }
        } else {
            null
        }
        Pair(available, Triple(missing, pythonVersion, shell))
    }
    val (available, extra) = report
    val (missing, pythonVersion, shell) = extra
    val body = buildString {
        appendLine("Shell: $shell")
        appendLine("Working directory: project root (writes are confined here)")
        appendLine()
        appendLine("Available: ${available.joinToString(", ").ifEmpty { "none detected" }}")
        appendLine("Missing:   ${missing.joinToString(", ").ifEmpty { "none" }}")
        appendLine()
        if (pythonVersion != null) {
            appendLine("Python: $pythonVersion")
        } else {
            appendLine(
                "Python: not installed. Stock Android has no Python interpreter; it only exists if the " +
                    "user installed Termux and added its binaries to PATH. Do not assume python3 works.",
            )
        }
        appendLine()
        appendLine("Allow-list (${allowList.size}): ${allowList.sorted().joinToString(", ").ifEmpty { "(empty — every binary permitted except blocked patterns)" }}")
        appendLine()
        if (ShellRunner.resolveShell().startsWith("/system/bin")) {
            appendLine(
                "Note: this is Android's toybox shell. Many desktop flags are unsupported " +
                    "(e.g. grep -P, sed -i, find -printf). Prefer the dedicated file tools.",
            )
        } else {
            appendLine("Note: full POSIX shell detected (desktop JVM / Termux).")
        }
    }
    return ToolResult.Success(body)
}

private fun TerminalTool.listSessions(): ToolResult {
    val recent = ShellHistory.recent()
    if (recent.isEmpty()) {
        return ToolResult.Success("No commands have been run in this session yet.")
    }
    val body = buildString {
        appendLine("Recent commands (newest last). State does not persist between calls — this is a log, not a live shell.")
        appendLine()
        recent.forEach { e ->
            val mark = if (e.exitCode == 0) "ok  " else "exit"
            appendLine("#${e.seq} [$mark ${e.exitCode}] (${e.cwd}) ${e.command.take(160)}")
        }
    }
    return ToolResult.Success(body)
}
