package com.mobileaiagent.core.util

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Idempotent cancellation token handed to long-running operations. */
class CancellationToken {
    private val cancelled = AtomicBoolean(false)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    val isCancelled: Boolean get() = cancelled.get()

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            listeners.forEach { runCatching { it() } }
            listeners.clear()
        }
    }

    fun throwIfCancelled() {
        if (isCancelled) throw CancellationException()
    }

    @Synchronized
    fun onCancel(action: () -> Unit): Closeable {
        if (isCancelled) {
            runCatching { action() }
            return Closeable { }
        }
        listeners.add(action)
        return Closeable { listeners.remove(action) }
    }

    class CancellationException(message: String = "Operation cancelled") : Exception(message)
}

/** Simple bounded byte sink usable from CLI tools that never touch Android. */
class BoundedOutput(private val limitBytes: Int = 512 * 1024) {
    private val buffer = StringBuilder()
    private var truncated = false

    @Synchronized
    fun append(chunk: String) {
        if (truncated) return
        buffer.append(chunk)
        if (buffer.length > limitBytes) {
            truncated = true
            val keep = buffer.substring(0, limitBytes)
            buffer.setLength(0)
            buffer.append(keep)
        }
    }

    @Synchronized
    fun text(): String = if (truncated) buffer.toString() + "\n…[output truncated]" else buffer.toString()

    @Synchronized
    fun isEmpty(): Boolean = buffer.isEmpty()

    /** Whether the bound was hit, so callers can report truncation explicitly. */
    @Synchronized
    fun wasTruncated(): Boolean = truncated

    @Synchronized
    fun clear() {
        buffer.setLength(0)
        truncated = false
    }
}

fun String.truncateMiddle(max: Int, marker: String = "\n…[truncated]…\n"): String {
    if (length <= max) return this
    val half = (max - marker.length).coerceAtLeast(2) / 2
    return substring(0, half) + marker + substring(length - half)
}

fun safePathSegment(name: String): String {
    val cleaned = name.trim().replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim('.', ' ')
    val nonEmpty = if (cleaned.isEmpty()) "unnamed" else cleaned
    return if (nonEmpty.length > 80) nonEmpty.take(80) else nonEmpty
}

fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024
        idx++
    }
    return String.format("%.1f %s", value, units[idx])
}
