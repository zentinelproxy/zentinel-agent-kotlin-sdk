package io.raskell.zentinel.agent

import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * HTTP response wrapper with ergonomic access methods.
 *
 * Provides convenient methods for accessing response data including
 * status code, headers, and body parsing.
 */
class Response internal constructor(
    private val event: ResponseHeadersEvent,
    private var bodyData: ByteArray? = null
) {
    // ========================================================================
    // Response Metadata
    // ========================================================================

    /** Correlation ID for request tracing. */
    val correlationId: String get() = event.correlationId

    /** HTTP status code. */
    val statusCode: Int get() = event.status

    // ========================================================================
    // Status Code Categories
    // ========================================================================

    /** Check if status is informational (1xx). */
    fun isInformational(): Boolean = statusCode in 100..199

    /** Check if status is success (2xx). */
    fun isSuccess(): Boolean = statusCode in 200..299

    /** Check if status is redirection (3xx). */
    fun isRedirection(): Boolean = statusCode in 300..399

    /** Check if status is client error (4xx). */
    fun isClientError(): Boolean = statusCode in 400..499

    /** Check if status is server error (5xx). */
    fun isServerError(): Boolean = statusCode in 500..599

    /** Check if status is any error (4xx or 5xx). */
    fun isError(): Boolean = statusCode >= 400

    /** Check if status is OK (200). */
    fun isOk(): Boolean = statusCode == 200

    /** Check if status is Not Found (404). */
    fun isNotFound(): Boolean = statusCode == 404

    /** Check if status is Unauthorized (401). */
    fun isUnauthorized(): Boolean = statusCode == 401

    /** Check if status is Forbidden (403). */
    fun isForbidden(): Boolean = statusCode == 403

    // ========================================================================
    // Headers
    // ========================================================================

    /** Get all headers. */
    val headers: Map<String, List<String>> get() = event.headers

    /** Get a header value (case-insensitive). Returns first value if multiple. */
    fun header(name: String): String? {
        val key = event.headers.keys.find { it.equals(name, ignoreCase = true) }
        return key?.let { event.headers[it]?.firstOrNull() }
    }

    /** Get all values for a header (case-insensitive). */
    fun headerAll(name: String): List<String> {
        val key = event.headers.keys.find { it.equals(name, ignoreCase = true) }
        return key?.let { event.headers[it] } ?: emptyList()
    }

    /** Check if a header exists (case-insensitive). */
    fun hasHeader(name: String): Boolean {
        return event.headers.keys.any { it.equals(name, ignoreCase = true) }
    }

    /** Content-Type header. */
    val contentType: String? get() = header("content-type")

    /** Content-Length header. */
    val contentLength: Long? get() = header("content-length")?.toLongOrNull()

    /** Cache-Control header. */
    val cacheControl: String? get() = header("cache-control")

    /** ETag header. */
    val etag: String? get() = header("etag")

    /** Last-Modified header. */
    val lastModified: String? get() = header("last-modified")

    /** Location header (for redirects). */
    val location: String? get() = header("location")

    /** Server header. */
    val server: String? get() = header("server")

    // ========================================================================
    // Body
    // ========================================================================

    /** Get the response body as bytes. */
    fun body(): ByteArray? = bodyData

    /** Get the response body as a string. */
    fun bodyString(): String? = bodyData?.toString(StandardCharsets.UTF_8)

    /** Parse the response body as JSON. */
    inline fun <reified T> bodyJson(): T? {
        val body = bodyString() ?: return null
        return try {
            Json.decodeFromString<T>(body)
        } catch (e: Exception) {
            null
        }
    }

    /** Check if the response has a body. */
    fun hasBody(): Boolean = bodyData != null && bodyData!!.isNotEmpty()

    /** Create a copy with body data. */
    internal fun withBody(body: ByteArray): Response {
        return Response(event, body)
    }

    /** Create a copy with base64-encoded body data. */
    internal fun withBase64Body(base64: String): Response {
        val decoded = Base64.getDecoder().decode(base64)
        return Response(event, decoded)
    }

    // ========================================================================
    // Content Type Checks
    // ========================================================================

    /** Check if content type is JSON. */
    fun isJson(): Boolean = contentType?.contains("application/json", ignoreCase = true) == true

    /** Check if content type is HTML. */
    fun isHtml(): Boolean = contentType?.contains("text/html", ignoreCase = true) == true

    /** Check if content type is plain text. */
    fun isPlainText(): Boolean = contentType?.contains("text/plain", ignoreCase = true) == true

    /** Check if content type is XML. */
    fun isXml(): Boolean = contentType?.let {
        it.contains("application/xml", ignoreCase = true) || it.contains("text/xml", ignoreCase = true)
    } == true

    /** Check if content type is CSS. */
    fun isCss(): Boolean = contentType?.contains("text/css", ignoreCase = true) == true

    /** Check if content type is JavaScript. */
    fun isJavaScript(): Boolean = contentType?.let {
        it.contains("application/javascript", ignoreCase = true) ||
        it.contains("text/javascript", ignoreCase = true)
    } == true

    /** Check if content type is an image. */
    fun isImage(): Boolean = contentType?.startsWith("image/", ignoreCase = true) == true

    override fun toString(): String = "Response(statusCode=$statusCode, correlationId=$correlationId)"

    companion object {
        /** Create a Response from a ResponseHeadersEvent. */
        fun fromEvent(event: ResponseHeadersEvent): Response = Response(event)
    }
}
