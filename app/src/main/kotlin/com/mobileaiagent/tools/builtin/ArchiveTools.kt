package com.mobileaiagent.tools.builtin

import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.core.util.humanBytes
import com.mobileaiagent.tools.spec.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ZIP archive creation/extraction using java.util.zip (available on Android).
 * Extraction is zip-slip safe: every entry must resolve inside the workspace, and
 * total uncompressed size is capped to avoid zip bombs.
 */
class ZipArchiveTool(private val extract: Boolean) : AgentTool {
    override val id = if (extract) "archive_extract" else "archive_create"
    override val name = if (extract) "Extract archive" else "Create ZIP archive"
    override val description = if (extract) {
        "Safely extract a .zip inside the workspace. Entries escaping the workspace are skipped and reported."
    } else {
        "Create a .zip archive from a file or folder in the workspace."
    }
    override val version = "1.0.0"
    override val category = ToolCategory.ARCHIVE
    override val inputSchema: JsonValue = if (extract) schema {
        requiredString("archive", "Relative path to the .zip file.")
        optionalString("destination", "Relative destination folder (default: folder named after the archive).")
    } else schema {
        requiredString("source", "Relative path of the file or folder to archive.")
        requiredString("archive", "Relative path for the output .zip file.")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions =
        if (extract) listOf(ToolPermission.FILES_READ, ToolPermission.FILES_WRITE)
        else listOf(ToolPermission.FILES_READ, ToolPermission.FILES_WRITE)
    override val mutagenic = true
    override val cancellationSupported = true
    override val maxResultChars = 8_000

    private val maxExtractedBytes = 512L * 1024 * 1024

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val args = ToolArgs(input)
            val ws = ctx.workspace ?: return@withContext FileListTool.NoWorkspace
            if (extract) {
                val archivePath = args.string("archive")
                val archive = ws.resolveSafe(archivePath)
                if (!archive.isFile) return@withContext ToolResult.Failure("Archive not found: $archivePath", errorKind = "not_found")
                val destRel = args.optionalString("destination")
                    ?: archive.nameWithoutExtension.ifBlank { "extracted" }
                val dest = ws.resolveSafe(destRel)
                dest.mkdirs()
                var count = 0
                var totalBytes = 0L
                val skipped = mutableListOf<String>()
                val written = mutableListOf<ArtifactRef>()
                try {
                    ZipInputStream(archive.inputStream().buffered()).use { zin ->
                        var entry: ZipEntry? = zin.nextEntry
                        while (entry != null) {
                            ctx.cancellation.throwIfCancelled()
                            val name = entry.name
                            if (name.isNotBlank() && !entry.isDirectory && !name.endsWith("/")) {
                                val target = runCatching { ws.resolveSafe("$destRel/$name") }.getOrNull()
                                if (target == null) {
                                    skipped.add(name)
                                } else {
                                    target.parentFile?.mkdirs()
                                    target.outputStream().use { out ->
                                        val buffer = ByteArray(32 * 1024)
                                        var read: Int
                                        var entryBytes = 0L
                                        while (zin.read(buffer).also { read = it } > 0) {
                                            entryBytes += read
                                            totalBytes += read
                                            if (totalBytes > maxExtractedBytes) {
                                                return@withContext ToolResult.Failure(
                                                    "Extraction aborted: uncompressed size exceeds ${humanBytes(maxExtractedBytes)}",
                                                    errorKind = "too_large",
                                                )
                                            }
                                            out.write(buffer, 0, read)
                                        }
                                    }
                                    count++
                                    if (count <= 200) {
                                        written.add(
                                            ArtifactRef(
                                                path = "$destRel/$name",
                                                displayName = name.substringAfterLast('/'),
                                                mimeType = FileWriteTool.mimeFor(name),
                                                sizeBytes = entry.size.coerceAtLeast(0),
                                            )
                                        )
                                    }
                                }
                            }
                            zin.closeEntry()
                            entry = zin.nextEntry
                        }
                    }
                } catch (e: Exception) {
                    return@withContext ToolResult.Failure("Extraction failed: ${e.message}", errorKind = "io")
                }
                ToolResult.Success(
                    "Extracted $count file(s) (${humanBytes(totalBytes)}) into $destRel/" +
                        if (skipped.isNotEmpty()) "\nSkipped unsafe entries: ${skipped.take(20).joinToString()}" else "",
                    artifacts = written,
                )
            } else {
                val sourceRel = args.string("source")
                val source = ws.resolveSafe(sourceRel)
                if (!source.exists()) return@withContext ToolResult.Failure("Source not found: $sourceRel", errorKind = "not_found")
                val archiveRel = args.string("archive")
                val archiveFile = ws.resolveSafe(archiveRel)
                archiveFile.parentFile?.mkdirs()
                var entries = 0
                ZipOutputStream(archiveFile.outputStream().buffered()).use { zout ->
                    val files = if (source.isDirectory) {
                        source.walkTopDown().filter { it.isFile }.toList()
                    } else listOf(source)
                    for (f in files) {
                        ctx.cancellation.throwIfCancelled()
                        val rel = source.parentFile?.let { parent ->
                            runCatching { parent.toPath().relativize(f.toPath()).toString() }.getOrDefault(f.name)
                        } ?: f.name
                        zout.putNextEntry(ZipEntry(rel.replace('\\', '/')))
                        f.inputStream().use { it.copyTo(zout, 32 * 1024) }
                        zout.closeEntry()
                        entries++
                        if (entries % 25 == 0) ctx.emitProgress("Archiving $entries files", null)
                    }
                }
                ToolResult.Success(
                    "Created $archiveRel with $entries file(s), ${humanBytes(archiveFile.length())}",
                    artifacts = listOf(ArtifactRef(archiveRel, archiveFile.name, "application/zip", archiveFile.length())),
                )
            }
        }
}
