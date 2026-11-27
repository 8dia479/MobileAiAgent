package com.mobileaiagent.providers.transport

import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.core.util.CancellationToken
import java.io.Closeable

/**
 * Minimal HTTP abstraction so provider adapters are testable without Ktor or
 * Android. `HttpTransportKtor` is the production implementation; tests inject
 * a scripted fake.
 */
interface HttpTransport {
    suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long = 60_000,
        cancellation: CancellationToken? = null,
    ): HttpResult

    suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: JsonValue,
        timeoutMs: Long = 120_000,
        cancellation: CancellationToken? = null,
    ): HttpResult

    /**
     * Opens a streaming POST. The caller iterates SSE events and closes the
     * stream; this must not buffer the entire response.
     */
    suspend fun postJsonStream(
        url: String,
        headers: Map<String, String>,
        body: JsonValue,
        connectTimeoutMs: Long = 60_000,
        readTimeoutMs: Long = 300_000,
        cancellation: CancellationToken? = null,
    ): StreamingHttpResponse
}

data class HttpResult(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
) {
    val isSuccess: Boolean get() = statusCode in 200..299

    fun jsonOrNull(): JsonValue? = com.mobileaiagent.core.json.JsonParser.parseOrNull(body)

    fun errorSummary(): String {
        val json = jsonOrNull()
        if (json != null) {
            val msg = json.path("error", "message").asStringOrNull()
                ?: json["message"].asStringOrNull()
                ?: json["detail"].asStringOrNull()
                ?: json.path("error", "detail").asStringOrNull()
            if (msg != null) return "HTTP $statusCode: $msg (body: ${body.take(600)})"
        }
        return "HTTP $statusCode: ${body.take(800)}"
    }
}

interface StreamingHttpResponse : Closeable {
    val statusCode: Int
    val errorBodyIfNotSuccess: String?

    /** Sequence of `data:` payload strings (protocol-specific parsing happens in the adapter). */
    fun sseEvents(): Sequence<ServerSentEvent>
}

data class ServerSentEvent(
    val event: String?,
    val data: String,
)

/**
 * Incremental SSE frame reader. Kept separate from networking so it can be
 * unit tested against awkward chunk boundaries (split CRLF, multi-line data,
 * comment keep-alives).
 */
class SseReader(private val lineReader: java.io.BufferedReader) : Sequence<ServerSentEvent>, Iterator<ServerSentEvent> {

    private var nextEvent: ServerSentEvent? = null
    private var finished = false

    override fun iterator(): Iterator<ServerSentEvent> = this

    override fun hasNext(): Boolean {
        if (nextEvent != null) return true
        if (finished) return false
        nextEvent = readNext()
        return nextEvent != null
    }

    override fun next(): ServerSentEvent {
        if (!hasNext()) throw NoSuchElementException("No more SSE events")
        val e = nextEvent!!
        nextEvent = null
        return e
    }

    private fun readNext(): ServerSentEvent? {
        var eventName: String? = null
        val data = StringBuilder()
        var sawAnyField = false
        while (true) {
            val line = try {
                lineReader.readLine()
            } catch (io: java.io.IOException) {
                finished = true
                return if (sawAnyField && data.isNotEmpty()) ServerSentEvent(eventName, data.toString().trimEnd('\n')) else null
            } ?: run {
                finished = true
                return if (sawAnyField && data.isNotEmpty()) ServerSentEvent(eventName, data.toString().trimEnd('\n')) else null
            }
            if (line.isEmpty()) {
                if (!sawAnyField) continue // ignore stray blank lines
                val payload = data.toString().trimEnd('\n')
                if (payload.isEmpty() && eventName == null) { sawAnyField = false; continue }
                return ServerSentEvent(eventName, payload)
            }
            if (line.startsWith(":")) continue // comment/keep-alive
            sawAnyField = true
            val colon = line.indexOf(':')
            val field = if (colon == -1) line else line.substring(0, colon)
            var value = if (colon == -1) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "event" -> eventName = value
                "data" -> data.append(value).append('\n')
                else -> { /* id, retry ignored */ }
            }
        }
    }
}
