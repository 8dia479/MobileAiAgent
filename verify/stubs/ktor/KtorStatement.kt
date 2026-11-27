@file:Suppress("unused", "UNUSED_PARAMETER")
package io.ktor.client.statement

import io.ktor.http.HttpStatusCode
import io.ktor.http.Headers
import io.ktor.utils.io.ByteReadChannel

class HttpResponse {
    val status: HttpStatusCode get() = HttpStatusCode(200)
    val headers: Headers get() = Headers()
}
