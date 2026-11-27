package com.mobileaiagent.tools.builtin

import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.core.util.humanBytes
import com.mobileaiagent.tools.spec.*

/**
 * File tools. All operations are confined to the project workspace by
 * [FileWorkspace]; none of them silently no-op.
 */
class FileListTool : AgentTool {
    override val id = "file_list"
    override val name = "List files"
    override val description = "List files and folders in the project workspace. Use recursive=true to see the whole tree (capped at 5000 entries)."
    override val version = "1.0.0"
    override val category = ToolCategory.FILES
    override val inputSchema = schema {
        optionalString("path", "Relative directory path (default: project root).")
        optionalBool("recursive", "Recurse into subdirectories. Default false.")
        optionalString("glob", "Optional simple pattern like '*.kt' or '*.json' to filter file names.")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_READ)
    override val mutagenic = false
    override val cancellationSupported = true
    override val maxResultChars = 30_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return NoWorkspace
        val path = args.optionalString("path") ?: ""
        val recursive = args.boolean("recursive", false)
        val glob = args.optionalString("glob")
        val entries = ws.list(path, recursive)
        val filtered = if (glob.isNullOrBlank()) entries else {
            val regex = globToRegex(glob)
            entries.filter { regex.matches(it.name) }
        }
        if (filtered.isEmpty()) {
            return ToolResult.Success("(no files) in '${path.ifBlank { "/" }}'")
        }
        val sb = StringBuilder()
        sb.appendLine("${filtered.size} entr${if (filtered.size == 1) "y" else "ies"} in '${path.ifBlank { "/" }}':")
        var total = 0L
        filtered.take(2000).forEach { e ->
            total += e.sizeBytes
            sb.appendLine(
                if (e.isDirectory) "  [dir]  ${e.path}/"
                else "  ${"%10s".format(humanBytes(e.sizeBytes))}  ${e.path}"
            )
        }
        if (filtered.size > 2000) sb.appendLine("…(${filtered.size - 2000} more)")
        sb.appendLine("Total size: ${humanBytes(total)}")
        return ToolResult.Success(
            sb.toString(),
            structured = JsonValue.Obj(
                linkedMapOf(
                    "count" to JsonValue.of(filtered.size),
                    "entries" to JsonValue.Arr(filtered.take(500).map {
                        JsonValue.obj(
                            "path" to JsonValue.of(it.path),
                            "dir" to JsonValue.of(it.isDirectory),
                            "size" to JsonValue.of(it.sizeBytes),
                        )
                    }),
                )
            ),
        )
    }

    companion object {
        val NoWorkspace = ToolResult.Failure("No project workspace is bound to this task", errorKind = "no_workspace")

        fun globToRegex(glob: String): Regex {
            val escaped = Regex.escape(glob).replace("\\*", ".*").replace("\\?", ".")
            return Regex(escaped, RegexOption.IGNORE_CASE)
        }
    }
}

class FileReadTool : AgentTool {
    override val id = "file_read"
    override val name = "Read file"
    override val description =
        "Read a UTF-8 text file from the workspace. Supports line ranges for large files. Binary files return an error listing the correct specialised tool."
    override val version = "1.0.0"
    override val category = ToolCategory.FILES
    override val inputSchema = schema {
        requiredString("path", "Relative file path.")
        optionalInt("start_line", "1-based first line to return.")
        optionalInt("line_count", "Number of lines to return (default: whole file, max 4000).")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_READ)
    override val mutagenic = false
    override val cancellationSupported = true
    override val maxResultChars = 60_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val path = args.string("path")
        return try {
            val text = ws.readText(path, 8L * 1024 * 1024)
            val lines = text.split("\n")
            val start = (args.int("start_line", required = false, default = 1) - 1).coerceAtLeast(0)
            val count = args.int("line_count", required = false, default = 4000).coerceIn(1, 4000)
            val slice = lines.drop(start).take(count)
            val header = buildString {
                appendLine("File: $path (${lines.size} lines, ${humanBytes(ws.sizeBytes(path).toLong())})")
                if (start > 0 || slice.size < lines.size) {
                    appendLine("Showing lines ${start + 1}..${start + slice.size}")
                }
                appendLine("---")
            }
            ToolResult.Success(header + slice.joinToString("\n"))
        } catch (e: WorkspaceSecurityException) {
            ToolResult.Failure(e.message ?: "blocked path", errorKind = "security")
        } catch (e: java.io.IOException) {
            ToolResult.Failure(e.message ?: "read failed", errorKind = "io")
        }
    }
}

class FileWriteTool : AgentTool {
    override val id = "file_write"
    override val name = "Write file"
    override val description =
        "Create or overwrite a UTF-8 text file with the full given content. Parent folders are created. Use file_append to add to an existing file."
    override val version = "1.0.0"
    override val category = ToolCategory.FILES
    override val inputSchema = schema {
        requiredString("path", "Relative file path.")
        requiredString("content", "Entire file content to write.")
        optionalBool("overwrite", "Overwrite if the file exists (default true).")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_WRITE)
    override val mutagenic = true
    override val cancellationSupported = true
    override val maxResultChars = 4_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val path = args.string("path")
        val content = args.string("content")
        val overwrite = args.boolean("overwrite", true)
        val max = ctx.settingsSnapshot.maxFileWriteBytes
        if (content.toByteArray().size > max) {
            return ToolResult.Failure("Content exceeds the ${humanBytes(max)} write limit", errorKind = "too_large")
        }
        val existed = ws.exists(path)
        return try {
            val written = ws.writeText(path, content, overwrite)
            val size = ws.sizeBytes(written)
            ToolResult.Success(
                "${if (existed) "Overwrote" else "Created"} $written (${humanBytes(size)}, ${content.length} chars)",
                artifacts = listOf(ArtifactRef(written, written.substringAfterLast('/'), mimeFor(written), size)),
            )
        } catch (e: WorkspaceSecurityException) {
            ToolResult.Failure(e.message ?: "blocked path", errorKind = "security")
        } catch (e: java.io.IOException) {
            ToolResult.Failure(e.message ?: "write failed", errorKind = "io")
        }
    }

    companion object {
        fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "json" -> "application/json"
            "md" -> "text/markdown"
            "txt", "kt", "py", "js", "ts", "html", "css", "xml", "csv", "sh" -> "text/plain"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "svg" -> "image/svg+xml"
            "stl" -> "model/stl"
            "obj" -> "model/obj"
            "glb" -> "model/gltf-binary"
            "step", "stp" -> "application/step"
            "pdf" -> "application/pdf"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
    }
}

class FileAppendTool : AgentTool {
    override val id = "file_append"
    override val name = "Append to file"
    override val description = "Append text to a file, creating it if needed. Useful for logs and incremental data writing."
    override val version = "1.0.0"
    override val category = ToolCategory.FILES
    override val inputSchema = schema {
        requiredString("path", "Relative file path.")
        requiredString("content", "Text to append.")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_WRITE)
    override val mutagenic = true
    override val cancellationSupported = true
    override val maxResultChars = 2_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val written = ws.appendText(args.string("path"), args.string("content"))
        return ToolResult.Success("Appended to $written (now ${humanBytes(ws.sizeBytes(written))})")
    }
}

class FileMkdirTool : AgentTool {
    override val id = "file_mkdir"
    override val name = "Create folder"
    override val description = "Create a folder (and parents) inside the workspace."
    override val version = "1.0.0"
    override val category = ToolCategory.FILES
    override val inputSchema = schema { requiredString("path", "Relative folder path to create.") }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_WRITE)
    override val mutagenic = true
    override val cancellationSupported = false
    override val maxResultChars = 1_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val created = ws.mkdir(ToolArgs(input).string("path"))
        return ToolResult.Success("Ensured folder: $created")
    }
}

class FileDeleteTool : AgentTool {
    override val id = "file_delete"
    override val name = "Delete file or folder"
    override val description = "Delete a file or folder inside the workspace. Requires recursive=true for folders. This cannot be undone."
    override val version = "1.0.0"
    override val category = ToolCategory.FILES
    override val inputSchema = schema {
        requiredString("path", "Relative path to delete.")
        optionalBool("recursive", "Required true to delete a folder with contents.")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_WITH_APPROVAL
    override val permissions = listOf(ToolPermission.FILES_DELETE)
    override val mutagenic = true
    override val cancellationSupported = false
    override val maxResultChars = 1_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val path = args.string("path")
        if (!ws.exists(path)) return ToolResult.Failure("No such path: $path", errorKind = "not_found")
        val outcome = ctx.requestApproval(
            ApprovalRequest(
                title = "Delete ${if (ws.isDirectory(path)) "folder" else "file"}",
                description = "The agent wants to permanently delete '$path' from the project.",
                risk = RiskLevel.HIGH, destructive = true,
            )
        )
        return when (outcome) {
            is ApprovalOutcome.Approved -> {
                try {
                    val ok = ws.delete(path, args.boolean("recursive", false))
                    if (ok) ToolResult.Success("Deleted $path")
                    else ToolResult.Failure("Deletion did not complete for $path", errorKind = "io")
                } catch (e: WorkspaceSecurityException) {
                    ToolResult.Failure(e.message ?: "blocked path", errorKind = "security")
                } catch (e: java.io.IOException) {
                    ToolResult.Failure(
                        e.message ?: "delete failed",
                        errorKind = "io",
                    )
                }
            }
            is ApprovalOutcome.Denied -> ToolResult.Failure("User denied deletion of $path: ${outcome.reason}", errorKind = "denied")
            ApprovalOutcome.TimedOut -> ToolResult.Failure("Deletion approval timed out", errorKind = "denied")
        }
    }
}

class FileMoveTool : AgentTool {
    override val id = "file_move"
    override val name = "Move or rename"
    override val description = "Move or rename a file or folder inside the workspace."
    override val version = "1.0.0"
    override val category = ToolCategory.FILES
    override val inputSchema = schema {
        requiredString("from", "Source relative path.")
        requiredString("to", "Destination relative path.")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_WRITE)
    override val mutagenic = true
    override val cancellationSupported = false
    override val maxResultChars = 1_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val moved = ws.move(args.string("from"), args.string("to"))
        return ToolResult.Success("Moved to $moved")
    }
}

class FileCopyTool : AgentTool {
    override val id = "file_copy"
    override val name = "Copy"
    override val description = "Copy a file or folder inside the workspace."
    override val version = "1.0.0"
    override val category = ToolCategory.FILES
    override val inputSchema = schema {
        requiredString("from", "Source relative path.")
        requiredString("to", "Destination relative path.")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_WRITE)
    override val mutagenic = true
    override val cancellationSupported = false
    override val maxResultChars = 1_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        return ToolResult.Success("Copied to ${ws.copy(args.string("from"), args.string("to"))}")
    }
}

class FileSearchTool : AgentTool {
    override val id = "file_search"
    override val name = "Search file contents"
    override val description =
        "Search text files for a literal string or regular expression, returning matching lines with numbers. Skips binary files and files larger than 2 MB."
    override val version = "1.0.0"
    override val category = ToolCategory.FILES
    override val inputSchema = schema {
        requiredString("query", "Literal text or regex to search for.")
        optionalBool("regex", "Treat query as a regular expression. Default false.")
        optionalString("path", "Restrict to this subfolder.")
        optionalString("glob", "Only search files whose name matches e.g. '*.py'.")
        optionalInt("max_results", "Maximum matches to return (default 100).")
        optionalBool("case_sensitive", "Default false.")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_READ)
    override val mutagenic = false
    override val cancellationSupported = true
    override val maxResultChars = 30_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val query = args.string("query")
        val useRegex = args.boolean("regex", false)
        val caseSensitive = args.boolean("case_sensitive", false)
        val maxResults = args.int("max_results", required = false, default = 100).coerceIn(1, 1000)
        val glob = args.optionalString("glob")
        val regex = try {
            if (useRegex) {
                Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
            } else {
                Regex(Regex.escape(query), if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
            }
        } catch (e: Exception) {
            return ToolResult.Failure("Invalid regular expression: ${e.message}", errorKind = "invalid_arguments")
        }
        val globRegex = glob?.let { FileListTool.globToRegex(it) }
        val files = ws.list(args.optionalString("path") ?: "", recursive = true)
            .filter { !it.isDirectory }
            .filter { globRegex == null || globRegex.matches(it.name) }
        val results = StringBuilder()
        var matches = 0
        var scanned = 0
        outer@ for (f in files) {
            if (matches >= maxResults) break
            if (f.sizeBytes > 2L * 1024 * 1024) continue
            ctx.cancellation.throwIfCancelled()
            val text = runCatching { ws.readText(f.path, 2L * 1024 * 1024) }.getOrNull() ?: continue
            scanned++
            text.lineSequence().forEachIndexed { idx, line ->
                if (matches >= maxResults) return@forEachIndexed
                if (regex.containsMatchIn(line)) {
                    results.appendLine("${f.path}:${idx + 1}: ${line.trim().take(300)}")
                    matches++
                }
            }
        }
        return if (matches == 0) {
            ToolResult.Success("No matches for '$query' (scanned $scanned text files)")
        } else {
            ToolResult.Success("$matches match(es) in $scanned file(s) scanned:\n$results")
        }
    }
}

class FileInfoTool : AgentTool {
    override val id = "file_info"
    override val name = "File info"
    override val description = "Get metadata for a file or folder: size, type, modified time, and a short text preview for small text files."
    override val version = "1.0.0"
    override val category = ToolCategory.FILES
    override val inputSchema = schema { requiredString("path", "Relative path.") }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_READ)
    override val mutagenic = false
    override val cancellationSupported = false
    override val maxResultChars = 6_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val path = ToolArgs(input).string("path")
        val file = ws.resolveSafe(path)
        if (!file.exists()) return ToolResult.Failure("Not found: $path", errorKind = "not_found")
        val sb = StringBuilder()
        sb.appendLine("Path: ${file.name}")
        sb.appendLine("Type: ${if (file.isDirectory) "folder" else "file"}")
        sb.appendLine("Size: ${humanBytes(file.length())}")
        sb.appendLine("Modified: ${java.time.Instant.ofEpochMilli(file.lastModified())}")
        if (file.isFile) {
            sb.appendLine("MIME: ${FileWriteTool.mimeFor(path)}")
            val head = runCatching { file.readBytes().take(2048).toByteArray() }.getOrNull()
            if (head != null && head.none { it == 0.toByte() }) {
                sb.appendLine("--- preview ---")
                sb.appendLine(String(head, Charsets.UTF_8).take(1500))
            } else {
                sb.appendLine("(binary content; preview omitted)")
            }
        }
        return ToolResult.Success(sb.toString())
    }
}
