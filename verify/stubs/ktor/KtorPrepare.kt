@file:Suppress("unused", "UNUSED_PARAMETER")
package io.ktor.client.request

import io.ktor.client.HttpClient
import io.ktor.client.HttpRequest

suspend fun HttpClient.preparePost(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpRequest.Builder =
    HttpRequest.Builder()

suspend fun HttpClient.prepareGet(url: String, block: HttpRequestBuilder.() -> Unit = {}): HttpRequest.Builder =
    HttpRequest.Builder()
