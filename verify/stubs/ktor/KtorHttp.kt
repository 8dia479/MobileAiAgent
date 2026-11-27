@file:Suppress("unused", "UNUSED_PARAMETER")
package io.ktor.http

class ContentType(val value: String) {
    override fun toString(): String = value
    object Application {
        val Json: ContentType = ContentType("application/json")
        val OctetStream: ContentType = ContentType("application/octet-stream")
    }
    companion object {
        val Application_Json: ContentType = Application.Json
        val Text_Plain: ContentType = ContentType("text/plain")
        val Any: ContentType = ContentType("*/*")
    }
}

object HttpHeaders {
    const val ContentType = "Content-Type"
    const val Authorization = "Authorization"
    const val Accept = "Accept"
}

class HttpStatusCode(val value: Int)
val HttpStatusCode.isSuccess: Boolean get() = value in 200..299

class Headers {
    fun entries(): Set<Entry> = emptySet()
    fun get(name: String): String? = null
    class Entry(val key: String, val value: List<String>)
}

class HeadersBuilder {
    val entries: MutableMap<String, List<String>> = mutableMapOf()
    fun append(name: String, value: String) {}
}
