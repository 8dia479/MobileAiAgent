package com.mobileaiagent.tools.builtin

import com.mobileaiagent.tools.spec.ToolWorkspace
import com.mobileaiagent.tools.spec.WorkspaceEntry
import java.io.File
import java.io.IOException

class WorkspaceSecurityException(message: String) : IOException(message)

/**
 * Filesystem sandbox for one project. Every path is resolved against the project
 * root and must stay inside it: `..`, absolute paths, symlink escapes and
 * NUL bytes are rejected. This is the enforcement point for §12 of the spec.
 */
class FileWorkspace(val root: File) : ToolWorkspace {

    init {
        root.mkdirs()
    }

    override fun resolveSafe(relativePath: String): File {
        if (relativePath.contains('\u0000')) throw WorkspaceSecurityException("Path contains NUL byte")
        val cleaned = relativePath.replace('\\', '/').trim()
        if (cleaned.startsWith("~")) throw WorkspaceSecurityException("Home-relative paths are not allowed: $relativePath")
        val target = if (File(cleaned).isAbsolute) {
            // Absolute paths must still land inside the sandbox.
            File(cleaned)
        } else {
            File(root, cleaned)
        }
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = safestCanonical(target)
        val rootPath = canonicalRoot.path
        val targetPath = canonicalTarget.path
        val inside = targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
        if (!inside) {
            throw WorkspaceSecurityException(
                "Path escapes the project workspace: '$relativePath' → '$targetPath' (root: '$rootPath')"
            )
        }
        return canonicalTarget
    }

    private fun safestCanonical(file: File): File {
        // Canonicalise the deepest existing ancestor so symlinked parents cannot escape.
        var probe: File? = file
        val tail = ArrayDeque<String>()
        while (probe != null && !probe.exists()) {
            tail.addFirst(probe.name)
            probe = probe.parentFile
        }
        if (probe == null) return file.canonicalFile
        var resolved = probe.canonicalFile
        tail.forEach { resolved = File(resolved, it) }
        return resolved
    }

    private fun relPath(file: File): String {
        val rootPath = root.canonicalFile.toPath()
        val path = file.toPath()
        return if (path.startsWith(rootPath)) {
            runCatching { rootPath.relativize(path).toString() }.getOrDefault(file.name)
        } else file.name
    }

    override fun exists(relativePath: String): Boolean = try { resolveSafe(relativePath).exists() } catch (e: Exception) { false }

    override fun isDirectory(relativePath: String): Boolean = resolveSafe(relativePath).isDirectory

    override fun readText(relativePath: String, maxBytes: Long): String {
        val f = resolveSafe(relativePath)
        if (!f.isFile) throw IOException("Not a file: $relativePath")
        if (f.length() > maxBytes) throw IOException("File too large to read as text (${f.length()} B > $maxBytes B): $relativePath")
        val bytes = f.readBytes()
        val isBinary = bytes.take(4096).any { it == 0.toByte() }
        if (isBinary) throw IOException("File appears binary: $relativePath (use an image/asset-specific tool)")
        return String(bytes, Charsets.UTF_8)
    }

    override fun readBytes(relativePath: String, maxBytes: Long): ByteArray {
        val f = resolveSafe(relativePath)
        if (!f.isFile) throw IOException("Not a file: $relativePath")
        if (f.length() > maxBytes) throw IOException("File too large (${f.length()} B > $maxBytes B): $relativePath")
        return f.readBytes()
    }

    override fun writeText(relativePath: String, content: String, overwrite: Boolean): String {
        val f = resolveSafe(relativePath)
        if (f.exists() && !overwrite) throw IOException("File exists and overwrite=false: $relativePath")
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".part")
        tmp.writeText(content, Charsets.UTF_8)
        if (f.exists()) f.delete()
        if (!tmp.renameTo(f)) {
            f.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
        return relPath(f)
    }

    override fun writeBytes(relativePath: String, bytes: ByteArray, overwrite: Boolean): String {
        val f = resolveSafe(relativePath)
        if (f.exists() && !overwrite) throw IOException("File exists and overwrite=false: $relativePath")
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return relPath(f)
    }

    override fun appendText(relativePath: String, content: String): String {
        val f = resolveSafe(relativePath)
        f.parentFile?.mkdirs()
        f.appendText(content, Charsets.UTF_8)
        return relPath(f)
    }

    override fun list(relativePath: String, recursive: Boolean): List<WorkspaceEntry> {
        val dir = resolveSafe(relativePath.ifBlank { "" })
        if (!dir.exists()) return emptyList()
        if (dir.isFile) return listOf(entryOf(dir))
        val out = mutableListOf<WorkspaceEntry>()
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeFirst()
            val children = current.listFiles()?.sortedBy { it.name } ?: continue
            for (child in children) {
                if (child.name.endsWith(".part")) continue
                out.add(entryOf(child))
                if (recursive && child.isDirectory) stack.addLast(child)
                if (out.size >= 5000) return out
            }
            if (!recursive && stack.isEmpty()) break
        }
        return out
    }

    private fun entryOf(f: File) = WorkspaceEntry(
        path = relPath(f),
        name = f.name,
        isDirectory = f.isDirectory,
        sizeBytes = if (f.isFile) f.length() else 0,
        lastModified = f.lastModified(),
    )

    override fun delete(relativePath: String, recursive: Boolean): Boolean {
        val f = resolveSafe(relativePath)
        if (f == root.canonicalFile) throw WorkspaceSecurityException("Refusing to delete the project root")
        if (!f.exists()) return false
        if (f.isDirectory && !recursive) throw IOException("Directory is not empty or recursive=false: $relativePath")
        return f.deleteRecursively()
    }

    override fun move(from: String, to: String): String {
        val src = resolveSafe(from)
        val dst = resolveSafe(to)
        if (!src.exists()) throw IOException("Source does not exist: $from")
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) {
            src.copyRecursively(dst, overwrite = true)
            if (!src.deleteRecursively()) throw IOException("Moved copy but could not remove source: $from")
        }
        return relPath(dst)
    }

    override fun copy(from: String, to: String): String {
        val src = resolveSafe(from)
        val dst = resolveSafe(to)
        if (!src.exists()) throw IOException("Source does not exist: $from")
        dst.parentFile?.mkdirs()
        if (src.isDirectory) src.copyRecursively(dst, overwrite = true) else src.copyTo(dst, overwrite = true)
        return relPath(dst)
    }

    override fun mkdir(relativePath: String): String {
        val f = resolveSafe(relativePath)
        f.mkdirs()
        return relPath(f)
    }

    override fun absolutePath(relativePath: String): String = resolveSafe(relativePath).absolutePath

    override fun sizeBytes(relativePath: String): Long = resolveSafe(relativePath).length()
}
