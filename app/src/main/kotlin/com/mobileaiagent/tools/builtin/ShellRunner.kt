package com.mobileaiagent.tools.builtin

import com.mobileaiagent.core.util.BoundedOutput
import com.mobileaiagent.core.util.CancellationToken
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shell command execution, separated from the tool's policy checks so the
 * security rules can be unit-tested without spawning processes.
 *
 * Everything here is intentionally small and explicit: this runs arbitrary user
 * commands on the user's own device, so the behaviour must be auditable.
 */
object ShellRunner {

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val durationMs: Long,
        val timedOut: Boolean,
        val truncatedStdout: Boolean,
        val truncatedStderr: Boolean,
    )

    /** Android has /system/bin/sh; desktop JVMs use /bin/sh. */
    fun resolveShell(): String {
        val candidates = listOf("/system/bin/sh", "/bin/sh", "/usr/bin/sh", "/system/xbin/sh", "sh")
        return candidates.firstOrNull { File(it).exists() } ?: "/system/bin/sh"
    }

    /**
     * Runs [command] with `/bin/sh -c`, so the caller controls quoting and
     * pipelines *after* validating them (see [TerminalPolicy]).
     *
     * stdout/stderr are drained on separate threads to avoid the classic
     * deadlock where a full pipe buffer blocks the child forever.
     */
    fun run(
        command: String,
        cwd: File,
        timeoutSeconds: Int,
        stdin: String? = null,
        extraEnv: Map<String, String> = emptyMap(),
        rootForEnv: String = cwd.absolutePath,
        cancellation: CancellationToken? = null,
        maxStdout: Int = 200_000,
        maxStderr: Int = 60_000,
    ): Result {
        require(timeoutSeconds in 1..300) { "timeout must be 1..300 seconds" }
        val started = System.currentTimeMillis()
        val process = runCatching {
            ProcessBuilder(resolveShell(), "-c", command)
                .directory(cwd)
                .redirectErrorStream(false)
                .apply {
                    environment()["HOME"] = rootForEnv
                    environment()["TMPDIR"] = File(rootForEnv, ".tmp").apply { mkdirs() }.path
                    environment()["PATH"] = System.getenv("PATH")
                        ?: if (File("/system/bin/sh").exists()) "/system/bin:/system/xbin" else "/usr/bin:/bin"
                    environment().putAll(extraEnv)
                }
                .start()
        }.getOrElse { e ->
            return Result(
                exitCode = 127,
                stdout = "",
                stderr = "Could not start the shell: ${e.message ?: e::class.simpleName}",
                durationMs = System.currentTimeMillis() - started,
                timedOut = false,
                truncatedStdout = false,
                truncatedStderr = false,
            )
        }

        val stdout = BoundedOutput(maxStdout)
        val stderr = BoundedOutput(maxStderr)
        val readers = listOf(
            drain(process.inputStream, stdout),
            drain(process.errorStream, stderr),
        )

        if (stdin != null) {
            // Feed input on its own thread: a large stdin with a child that is not
            // reading would otherwise block this coroutine before waitFor().
            Thread {
                runCatching {
                    process.outputStream.bufferedWriter().use { it.write(stdin) }
                }
            }.apply { isDaemon = true; start() }
        } else {
            runCatching { process.outputStream.close() }
        }

        val cancelHandle = cancellation?.onCancel { process.destroyForcibly() }
        val finished = try {
            process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            false
        } finally {
            cancelHandle?.close()
        }
        if (!finished) process.destroyForcibly()
        readers.forEach { it.join(2_000) }

        val exit = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -9
        return Result(
            exitCode = exit,
            stdout = stdout.text(),
            stderr = stderr.text(),
            durationMs = System.currentTimeMillis() - started,
            timedOut = !finished,
            truncatedStdout = stdout.wasTruncated(),
            truncatedStderr = stderr.wasTruncated(),
        )
    }

    private fun drain(stream: java.io.InputStream, sink: BoundedOutput): Thread =
        Thread {
            runCatching {
                stream.bufferedReader().useLines { lines -> lines.forEach { sink.append(it + "\n") } }
            }
        }.apply { isDaemon = true; start() }
}

/**
 * Command policy: which commands may run at all.
 *
 * This is a *deliberate* restriction, not a sandbox. Android has no per-process
 * filesystem jail for an app, so the only defences are: the allow-list, the
 * rejection of obvious escapes, running as the app's own UID (no root), and the
 * working directory being forced inside the project.
 *
 * The rules are pure functions so they can be tested exhaustively.
 */
object TerminalPolicy {

    /** Patterns that are rejected outright, regardless of the allow-list. */
    private val DANGEROUS = listOf(
        "rm -rf /", "rm -rf /*", "mkfs", ":(){", ":(){:|:&};:", "> /dev/block", "dd if=",
        "chmod -r 777 /", "chmod 777 /", "sudo ", "su -", "su root", "/system/bin/su",
        "pm install", "settings put", "content insert",
    )

    /**
     * Shell operators that are allowed. Chaining with `;` and `&&` is allowed
     * because the agent legitimately needs `a && b`; every segment is validated
     * independently, so `a && rm -rf /` is still rejected.
     */
    private val SEGMENT_SEPARATORS = listOf("&&", "||", ";", "|")

    sealed class Verdict {
        object Allowed : Verdict()
        data class Rejected(val reason: String, val errorKind: String = "security") : Verdict()
    }

    data class ParsedSegment(val binary: String, val text: String)

    /**
     * Splits a command line into individually-validated segments.
     *
     * Quoting is respected: `echo "a && b"` is one segment, because the `&&` is
     * inside double quotes.
     */
    fun splitSegments(command: String): List<ParsedSegment> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escaped = false
        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                escaped -> { current.append(c); escaped = false }
                c == '\\' && !inSingle -> { current.append(c); escaped = true }
                c == '\'' && !inDouble -> { current.append(c); inSingle = !inSingle }
                c == '"' && !inSingle -> { current.append(c); inDouble = !inDouble }
                !inSingle && !inDouble -> {
                    val two = if (i + 1 < command.length) command.substring(i, i + 2) else ""
                    if (two == "&&" || two == "||") {
                        segments.add(current.toString().trim()); current.clear(); i += 2
                        continue
                    }
                    if (c == ';' || c == '|') {
                        segments.add(current.toString().trim()); current.clear(); i++
                        continue
                    }
                    current.append(c)
                }
                else -> current.append(c)
            }
            i++
        }
        segments.add(current.toString().trim())
        return segments.filter { it.isNotEmpty() }.map { text ->
            ParsedSegment(firstToken(text), text)
        }
    }

    /** The binary of a segment, ignoring leading `VAR=value` assignments. */
    fun firstToken(segment: String): String {
        val tokens = tokenize(segment)
        val binary = tokens.firstOrNull { !it.contains('=') || it.startsWith("./") || it.startsWith("/") }
            ?: tokens.firstOrNull()
            ?: ""
        return binary.removeSurrounding("\"").removeSurrounding("'")
    }

    /** Splits on whitespace, keeping quoted runs together. */
    fun tokenize(segment: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inSingle = false
        var inDouble = false
        for (c in segment) {
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c.isWhitespace() && !inSingle && !inDouble -> {
                    if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /**
     * Validates a full command line.
     *
     * @param allowList permitted binaries. Empty means "no allow-list", which the
     *   tool only permits when the user has explicitly enabled shell access and
     *   cleared every default; the tool still applies [DANGEROUS].
     */
    fun validate(command: String, allowList: Set<String>, allowChaining: Boolean): Verdict {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return Verdict.Rejected("command is empty", "invalid_arguments")
        if (trimmed.contains('\n') || trimmed.contains('\u0000')) {
            return Verdict.Rejected("Multi-line commands and NUL bytes are not allowed")
        }
        val lowered = trimmed.lowercase()
        DANGEROUS.firstOrNull { lowered.contains(it) }?.let {
            return Verdict.Rejected("Command rejected: contains dangerous pattern '$it'")
        }
        // Command substitution and backticks can run arbitrary programs without a
        // separator, so they are always rejected; `$(...)` is the common accident.
        if (trimmed.contains('`')) {
            return Verdict.Rejected("Backticks (command substitution) are not permitted")
        }
        if (trimmed.contains("$(")) {
            return Verdict.Rejected("'\\$(...)' command substitution is not permitted")
        }
        val segments = splitSegments(trimmed)
        if (segments.isEmpty()) return Verdict.Rejected("command is empty", "invalid_arguments")
        if (segments.size > 1 && !allowChaining) {
            return Verdict.Rejected(
                "Command chaining is disabled. Enable 'Allow pipes and chaining' in Settings → Tools → Terminal, " +
                    "or run a single command.",
            )
        }
        if (segments.size > 12) {
            return Verdict.Rejected("Command has too many chained segments (${segments.size} > 12)")
        }
        if (allowList.isNotEmpty()) {
            segments.forEach { seg ->
                val bin = seg.binary
                val bare = bin.substringAfterLast('/')
                if (bin.startsWith("/") && bin.contains('/')) {
                    return Verdict.Rejected(
                        "Absolute paths to binaries are not permitted ('$bin'). Use '$bare' from the allow-list.",
                    )
                }
                if (bin !in allowList && bare !in allowList) {
                    return Verdict.Rejected(
                        "Binary '$bin' is not in the allowed list (${allowList.sorted().joinToString()}).",
                    )
                }
            }
        }
        return Verdict.Allowed
    }

    /** True when the command contains a separator, used for approval decisions. */
    fun hasChaining(command: String): Boolean =
        SEGMENT_SEPARATORS.any { command.contains(it) }

    /**
     * Parses `K=V,K2=V2`. Returns null when malformed so the tool can reject the
     * call instead of silently dropping the variables.
     */
    fun parseEnv(spec: String?): Map<String, String>? {
        if (spec.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in spec.split(',')) {
            if (pair.isBlank()) continue
            val idx = pair.indexOf('=')
            if (idx <= 0) return null
            val key = pair.substring(0, idx).trim()
            val value = pair.substring(idx + 1)
            if (!key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return null
            if (out.size >= 16) return null
            out[key] = value
        }
        return out
    }
}
