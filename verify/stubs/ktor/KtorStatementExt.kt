@file:Suppress("unused", "UNUSED_PARAMETER")
package io.ktor.client.statement

import io.ktor.utils.io.ByteReadChannel

suspend fun HttpResponse.bodyAsText(): String = ""
suspend fun HttpResponse.bodyAsChannel(): ByteReadChannel = ByteReadChannel()
