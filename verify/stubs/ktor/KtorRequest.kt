@file:Suppress("unused", "UNUSED_PARAMETER")
package io.ktor.client.request

import io.ktor.http.ContentType

class HttpRequestBuilder {
    fun header(name: String, value: String) {}
    fun headers(block: (io.ktor.http.HeadersBuilder) -> Unit) {}
}

fun io.ktor.http.HeadersBuilder.header(name: String, value: String) {}

// Real Ktor declares these as top-level extensions in io.ktor.client.request, so
// calling client.get(...) without importing them must fail — that is exactly the
// bug the first real Gradle build found.
suspend fun io.ktor.client.HttpClient.get(
    url: String,
    block: HttpRequestBuilder.() -> Unit = {},
): io.ktor.client.statement.HttpResponse = io.ktor.client.statement.HttpResponse()

suspend fun io.ktor.client.HttpClient.post(
    url: String,
    block: HttpRequestBuilder.() -> Unit = {},
): io.ktor.client.statement.HttpResponse = io.ktor.client.statement.HttpResponse()

