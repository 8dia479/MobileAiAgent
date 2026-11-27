package com.mobileaiagent.core.log

import com.mobileaiagent.core.util.Clock
import com.mobileaiagent.core.util.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogRecord(
    val id: Long,
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val details: String? = null,
)

interface LogSink {
    fun write(record: LogRecord)
}

/** In-memory ring buffer; the UI tail and the in-app log viewer read from here. */
class InMemoryLogSink(
    private val capacity: Int = 2_000,
    private val clock: Clock = SystemClock,
) : LogSink {
    private val records = ArrayDeque<LogRecord>()
    private var nextId = 1L

    @Synchronized
    override fun write(record: LogRecord) {
        records.addLast(record)
        while (records.size > capacity) records.removeFirst()
    }

    @Synchronized
    fun snapshot(level: LogLevel = LogLevel.DEBUG, limit: Int = 500): List<LogRecord> =
        records.filter { it.level.ordinal >= level.ordinal }.takeLast(limit)

    @Synchronized
    fun clear() = records.clear()

    fun newId(): Long = clock.nowMillis()
}

/**
 * Global logging facade. On Android this fans out to Logcat and an in-memory
 * sink; on the JVM it is stdout-only. Never throws: logging must not break a task.
 */
object AgentLog {
    private val sinks = CopyOnWriteArrayList<LogSink>()
    private val buffer = InMemoryLogSink()
    @Volatile
    var minLevel: LogLevel = LogLevel.DEBUG

    @Volatile
    var androidSink: ((LogRecord) -> Unit)? = null

    private val counter = java.util.concurrent.atomic.AtomicLong(0)

    init {
        sinks.add(buffer)
    }

    fun addSink(sink: LogSink) { sinks.add(sink) }

    fun recent(level: LogLevel = LogLevel.DEBUG, limit: Int = 500): List<LogRecord> =
        buffer.snapshot(level, limit)

    fun d(tag: String, message: String, details: String? = null) = log(LogLevel.DEBUG, tag, message, details)
    fun i(tag: String, message: String, details: String? = null) = log(LogLevel.INFO, tag, message, details)
    fun w(tag: String, message: String, details: String? = null) = log(LogLevel.WARN, tag, message, details)
    fun e(tag: String, message: String, details: String? = null) = log(LogLevel.ERROR, tag, message, details)

    fun e(tag: String, message: String, throwable: Throwable) =
        log(LogLevel.ERROR, tag, message, throwable.stackTraceToString())

    private fun log(level: LogLevel, tag: String, message: String, details: String?) {
        if (level.ordinal < minLevel.ordinal) return
        val record = LogRecord(counter.incrementAndGet(), System.currentTimeMillis(), level, tag, message, details)
        androidSink?.let { runCatching { it(record) } }
        sinks.forEach { runCatching { it.write(record) } }
        if (level == LogLevel.ERROR || level == LogLevel.WARN) {
            System.err.println("${level.name}/$tag: $message" + (details?.let { " :: $it" } ?: ""))
        }
    }
}
