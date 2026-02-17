package io.raskell.zentinel.agent

import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * HTTP request wrapper with ergonomic access methods.
 *
 * Provides convenient methods for accessing request data including
 * headers, query parameters, path matching, and body parsing.
 */
class Request internal constructor(
    private val event: RequestHeadersEvent,
    private var bodyData: ByteArray? = null
) {
    private val parsedPath: String
    private val parsedQuery: String?
    private val queryParams: Map<String, List<String>>

    init {
        val uri = event.uri
        val queryIndex = uri.indexOf('?')
        if (queryIndex >= 0) {
            parsedPath = uri.substring(0, queryIndex)
            parsedQuery = uri.substring(queryIndex + 1)
            queryParams = parseQueryString(parsedQuery)
        } else {
            parsedPath = uri
            parsedQuery = null
            queryParams = emptyMap()
        }
    }

    // ========================================================================
    // Request Metadata
    // ========================================================================

    /** Correlation ID for request tracing. */
    val correlationId: String get() = event.metadata.correlationId

    /** Internal request ID. */
    val requestId: String get() = event.metadata.requestId

    /** Client IP address. */
    val clientIp: String get() = event.metadata.clientIp

    /** Client port. */
    val clientPort: Int get() = event.metadata.clientPort

    /** Server name (SNI or Host header). */
    val serverName: String? get() = event.metadata.serverName

    /** Protocol (HTTP/1.1, HTTP/2, etc.). */
    val protocol: String get() = event.metadata.protocol

    /** TLS version if applicable. */
    val tlsVersion: String? get() = event.metadata.tlsVersion

    /** Route ID that matched. */
    val routeId: String? get() = event.metadata.routeId

    // ========================================================================
    // HTTP Method and Path
    // ========================================================================

    /** HTTP method (GET, POST, etc.). */
    val method: String get() = event.method

    /** Full URI including query string. */
    val uri: String get() = event.uri

    /** Path without query string. */
    val path: String get() = parsedPath

    /** Query string without leading '?'. */
    val queryString: String? get() = parsedQuery

    /** Check if path equals the given value. */
    fun pathEquals(value: String): Boolean = path == value

    /** Check if path starts with the given prefix. */
    fun pathStartsWith(prefix: String): Boolean = path.startsWith(prefix)

    /** Check if path ends with the given suffix. */
    fun pathEndsWith(suffix: String): Boolean = path.endsWith(suffix)

    /** Check if path contains the given substring. */
    fun pathContains(substring: String): Boolean = path.contains(substring)

    /** Check if path matches the given regex. */
    fun pathMatches(regex: Regex): Boolean = regex.matches(path)

    // ========================================================================
    // HTTP Method Checks
    // ========================================================================

    /** Check if this is a GET request. */
    fun isGet(): Boolean = method.equals("GET", ignoreCase = true)

    /** Check if this is a POST request. */
    fun isPost(): Boolean = method.equals("POST", ignoreCase = true)

    /** Check if this is a PUT request. */
    fun isPut(): Boolean = method.equals("PUT", ignoreCase = true)

    /** Check if this is a DELETE request. */
    fun isDelete(): Boolean = method.equals("DELETE", ignoreCase = true)

    /** Check if this is a PATCH request. */
    fun isPatch(): Boolean = method.equals("PATCH", ignoreCase = true)

    /** Check if this is a HEAD request. */
    fun isHead(): Boolean = method.equals("HEAD", ignoreCase = true)

    /** Check if this is an OPTIONS request. */
    fun isOptions(): Boolean = method.equals("OPTIONS", ignoreCase = true)

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

    /** Host header. */
    val host: String? get() = header("host")

    /** User-Agent header. */
    val userAgent: String? get() = header("user-agent")

    /** Content-Type header. */
    val contentType: String? get() = header("content-type")

    /** Content-Length header. */
    val contentLength: Long? get() = header("content-length")?.toLongOrNull()

    /** Authorization header. */
    val authorization: String? get() = header("authorization")

    /** Cookie header. */
    val cookie: String? get() = header("cookie")

    /** Accept header. */
    val accept: String? get() = header("accept")

    /** X-Forwarded-For header. */
    val xForwardedFor: String? get() = header("x-forwarded-for")

    /** X-Real-IP header. */
    val xRealIp: String? get() = header("x-real-ip")

    // ========================================================================
    // Query Parameters
    // ========================================================================

    /** Get a query parameter value. Returns first value if multiple. */
    fun query(name: String): String? = queryParams[name]?.firstOrNull()

    /** Get all values for a query parameter. */
    fun queryAll(name: String): List<String> = queryParams[name] ?: emptyList()

    /** Check if a query parameter exists. */
    fun hasQuery(name: String): Boolean = queryParams.containsKey(name)

    /** Get all query parameters. */
    fun queryParams(): Map<String, List<String>> = queryParams

    // ========================================================================
    // Body
    // ========================================================================

    /** Get the request body as bytes. */
    fun body(): ByteArray? = bodyData

    /** Get the request body as a string. */
    fun bodyString(): String? = bodyData?.toString(StandardCharsets.UTF_8)

    /** Parse the request body as JSON. */
    inline fun <reified T> bodyJson(): T? {
        val body = bodyString() ?: return null
        return try {
            Json.decodeFromString<T>(body)
        } catch (e: Exception) {
            null
        }
    }

    /** Check if the request has a body. */
    fun hasBody(): Boolean = bodyData != null && bodyData!!.isNotEmpty()

    /** Create a copy with body data. */
    internal fun withBody(body: ByteArray): Request {
        return Request(event, body)
    }

    /** Create a copy with base64-encoded body data. */
    internal fun withBase64Body(base64: String): Request {
        val decoded = Base64.getDecoder().decode(base64)
        return Request(event, decoded)
    }

    // ========================================================================
    // Content Type Checks
    // ========================================================================

    /** Check if content type is JSON. */
    fun isJson(): Boolean = contentType?.contains("application/json", ignoreCase = true) == true

    /** Check if content type is form data. */
    fun isFormData(): Boolean = contentType?.contains("application/x-www-form-urlencoded", ignoreCase = true) == true

    /** Check if content type is multipart. */
    fun isMultipart(): Boolean = contentType?.contains("multipart/", ignoreCase = true) == true

    /** Check if content type is XML. */
    fun isXml(): Boolean = contentType?.let {
        it.contains("application/xml", ignoreCase = true) || it.contains("text/xml", ignoreCase = true)
    } == true

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun parseQueryString(query: String): Map<String, List<String>> {
        if (query.isBlank()) return emptyMap()

        return query.split('&')
            .filter { it.isNotEmpty() }
            .mapNotNull { param ->
                val parts = param.split('=', limit = 2)
                if (parts.isEmpty()) return@mapNotNull null
                val key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8)
                val value = if (parts.size > 1) {
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                } else {
                    ""
                }
                key to value
            }
            .groupBy({ it.first }, { it.second })
    }

    override fun toString(): String = "Request(method=$method, path=$path, correlationId=$correlationId)"

    companion object {
        /** Create a Request from a RequestHeadersEvent. */
        fun fromEvent(event: RequestHeadersEvent): Request = Request(event)
    }
}
