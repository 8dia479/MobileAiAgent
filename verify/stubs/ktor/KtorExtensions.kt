@file:Suppress("unused", "UNUSED_PARAMETER")
package io.ktor.client.request

import io.ktor.http.ContentType

// Real Ktor declares these as extensions on HttpRequestBuilder.
fun HttpRequestBuilder.setBody(body: Any?) {}
fun HttpRequestBuilder.setBody(body: String) {}
fun HttpRequestBuilder.contentType(type: ContentType) {}
