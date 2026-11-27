@file:Suppress("unused", "UNUSED_PARAMETER")
package io.ktor.client

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers

/**
 * Ktor type-check stubs modelling exactly the DSL surface KtorHttpTransport uses:
 * `client.get(url) { header(...) }`, `client.post(url) { header; setBody }`,
 * `response.status.value`, `response.headers`, `bodyAsText`, `bodyAsChannel`.
 */
open class HttpClientConfig<T> {
    var expectSuccess: Boolean = false
    fun install(plugin: Any?, configure: (Any) -> Unit = {}) {}
    fun engine(block: HttpClientEngineConfig.() -> Unit) {}
}

open class HttpClientEngineConfig {
    fun config(block: HttpClientEngineConfig.() -> Unit) {}
    fun followRedirects(enabled: Boolean) {}
    fun connectTimeout(millis: Long, unit: java.util.concurrent.TimeUnit) {}
    fun readTimeout(millis: Long, unit: java.util.concurrent.TimeUnit) {}
}

class HttpClient(val engine: Any? = null) {
    constructor(engine: Any, block: HttpClientConfig<Any>.() -> Unit) : this(engine)

    suspend fun get(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse = HttpResponse()
    suspend fun post(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpResponse = HttpResponse()
    fun close() {}
}

class HttpRequest {
    class Builder {
        suspend fun execute(): HttpResponse = HttpResponse()
        fun build(): HttpRequest = HttpRequest()
    }
}
