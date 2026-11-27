@file:Suppress("unused", "UNUSED_PARAMETER")
package io.ktor.utils.io

class ByteReadChannel
suspend fun ByteReadChannel.readAvailable(buffer: ByteArray, offset: Int, length: Int): Int = -1
suspend fun ByteReadChannel.readRemaining(): Any = Unit
