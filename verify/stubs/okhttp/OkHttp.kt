@file:Suppress("unused", "UNUSED_PARAMETER")
package okhttp3

/** okhttp3 type-check stubs (used directly by KtorHttpTransport for streaming). */
class MediaType {
    companion object {
        fun parse(value: String): MediaType? = MediaType()
    }
}
fun String.toMediaTypeOrNull(): MediaType = MediaType()


class RequestBody {
    companion object {
        fun create(contentType: MediaType?, content: String): RequestBody = RequestBody()
    }
}

class Headers

class Request {
    val url: String get() = ""
    class Builder {
        fun url(url: String): Builder = this
        fun post(body: RequestBody): Builder = this
        fun get(): Builder = this
        fun addHeader(name: String, value: String): Builder = this
        fun header(name: String, value: String): Builder = this
        fun build(): Request = Request()
    }
}

class ResponseBody {
    fun byteStream(): java.io.InputStream = throw UnsupportedOperationException("stub")
    fun string(): String = ""
}

class Response {
    val code: Int get() = 200
    val headers: Headers get() = Headers()
    val isSuccessful: Boolean get() = true
    val body: ResponseBody? get() = null
    fun close() {}
}

class Call {
    fun execute(): Response = Response()
    fun cancel() {}
    fun isCanceled(): Boolean = false
}

class Dispatcher {
    val executorService: java.util.concurrent.ExecutorService get() = throw UnsupportedOperationException("stub")
}

class ConnectionPool {
    fun evictAll() {}
}

class OkHttpClient {
    val dispatcher: Dispatcher get() = Dispatcher()
    val connectionPool: ConnectionPool get() = ConnectionPool()
    fun newCall(request: Request): Call = Call()
    class Builder {
        fun connectTimeout(timeout: Long, unit: java.util.concurrent.TimeUnit): Builder = this
        fun readTimeout(timeout: Long, unit: java.util.concurrent.TimeUnit): Builder = this
        fun writeTimeout(timeout: Long, unit: java.util.concurrent.TimeUnit): Builder = this
        fun callTimeout(timeout: Long, unit: java.util.concurrent.TimeUnit): Builder = this
        fun retryOnConnectionFailure(retry: Boolean): Builder = this
        fun build(): OkHttpClient = OkHttpClient()
    }
}
