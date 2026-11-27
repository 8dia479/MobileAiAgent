@file:Suppress("unused", "UNUSED_PARAMETER")
package io.ktor.client.request

import io.ktor.http.ContentType

class HttpRequestBuilder {
    fun header(name: String, value: String) {}
    fun headers(block: (io.ktor.http.HeadersBuilder) -> Unit) {}
}

fun io.ktor.http.HeadersBuilder.header(name: String, value: String) {}
