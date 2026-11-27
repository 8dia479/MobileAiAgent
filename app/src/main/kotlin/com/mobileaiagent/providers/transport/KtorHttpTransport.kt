package com.mobileaiagent.providers.transport

import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.core.log.AgentLog
import com.mobileaiagent.core.util.CancellationToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Ktor/OkHttp implementation of [HttpTransport].
 *
 * Streaming uses OkHttp directly for the SSE body because it gives us a plain
 * InputStream that is trivial to close and cancel (Ktor's channel API adds no
 * value for line-delimited SSE and complicates cancellation guarantees).
 */
class KtorHttpTransport(
    private val client: HttpClient = defaultClient(),
) : HttpTransport {

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        cancellation: CancellationToken?,
    ): HttpResult = withContext(Dispatchers.IO) {
        cancellation?.throwIfCancelled()
        try {
            val response = client.get(url) {
                headers.forEach { (k, v) -> header(k, v) }
            }
            HttpResult(
                statusCode = response.status.value,
                body = response.bodyAsText().take(8_000_000),
                headers = response.headers.entries().associate { it.key to it.value.joinToString(",") },
            )
        } catch (e: Exception) {
            if (cancellation?.isCancelled == true) throw e
            AgentLog.w("HttpTransport", "GET failed for $url: ${e.message}")
            HttpResult(statusCode = 0, body = """{"error":{"message":"${escape(e.message ?: "network failure")}"}}""")
        }
    }

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: JsonValue,
        timeoutMs: Long,
        cancellation: CancellationToken?,
    ): HttpResult = withContext(Dispatchers.IO) {
        cancellation?.throwIfCancelled()
        try {
            val response = client.post(url) {
                headers.forEach { (k, v) -> header(k, v) }
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(body.toJson())
            }
            HttpResult(
                statusCode = response.status.value,
                body = response.bodyAsText().take(8_000_000),
                headers = response.headers.entries().associate { it.key to it.value.joinToString(",") },
            )
        } catch (e: Exception) {
            if (cancellation?.isCancelled == true) throw e
            HttpResult(statusCode = 0, body = """{"error":{"message":"${escape(e.message ?: "network failure")}"}}""")
        }
    }

    override suspend fun postJsonStream(
        url: String,
        headers: Map<String, String>,
        body: JsonValue,
        connectTimeoutMs: Long,
        readTimeoutMs: Long,
        cancellation: CancellationToken?,
    ): StreamingHttpResponse = withContext(Dispatchers.IO) {
        val okClient = okHttpClient(connectTimeoutMs, readTimeoutMs)
        val request = okhttp3.Request.Builder()
            .url(url)
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), body.toJson()))
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        cancellation?.throwIfCancelled()
        val call = okClient.newCall(request)
        val cancelHandle = cancellation?.onCancel { call.cancel() }
        val response = call.execute()
        val stream = response.body?.byteStream() ?: throw java.io.IOException("Empty response body")
        StreamResponse(response.code, stream, if (response.isSuccessful) null else stream.readBytes().toString(Charsets.UTF_8), call, okClient, cancelHandle)
    }

    private fun okHttpClient(connectTimeoutMs: Long, readTimeoutMs: Long): okhttp3.OkHttpClient =
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    private class StreamResponse(
        override val statusCode: Int,
        private val stream: InputStream,
        override val errorBodyIfNotSuccess: String?,
        private val call: okhttp3.Call,
        private val client: okhttp3.OkHttpClient,
        private val cancelHandle: Closeable?,
    ) : StreamingHttpResponse {
        override fun sseEvents(): Sequence<ServerSentEvent> =
            SseReader(BufferedReader(InputStreamReader(stream, Charsets.UTF_8)))

        override fun close() {
            cancelHandle?.close()
            runCatching { stream.close() }
            runCatching { client.dispatcher.executorService.shutdown() }
            runCatching { client.connectionPool.evictAll() }
        }
    }

    companion object {
        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            engine {
                config {
                    followRedirects(true)
                    connectTimeout(60, TimeUnit.SECONDS)
                    readTimeout(300, TimeUnit.SECONDS)
                }
            }
        }

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    }
}

private fun String.toMediaTypeOrNull(): okhttp3.MediaType? = okhttp3.MediaType.parse("application/json")
