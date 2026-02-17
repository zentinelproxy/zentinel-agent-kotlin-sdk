package io.raskell.zentinel.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestTest {

    private fun createRequest(
        method: String = "GET",
        uri: String = "/test",
        headers: Map<String, List<String>> = emptyMap()
    ): Request {
        val event = RequestHeadersEvent(
            metadata = RequestMetadata(
                correlationId = "test-123",
                requestId = "req-456",
                clientIp = "192.168.1.1",
                clientPort = 12345,
                protocol = "HTTP/1.1"
            ),
            method = method,
            uri = uri,
            headers = headers
        )
        return Request.fromEvent(event)
    }

    @Test
    fun `basic request properties`() {
        val request = createRequest(method = "POST", uri = "/api/users")

        assertEquals("POST", request.method)
        assertEquals("/api/users", request.uri)
        assertEquals("/api/users", request.path)
        assertEquals("test-123", request.correlationId)
        assertEquals("req-456", request.requestId)
        assertEquals("192.168.1.1", request.clientIp)
        assertEquals(12345, request.clientPort)
    }

    @Test
    fun `path without query string`() {
        val request = createRequest(uri = "/api/users?page=1&limit=10")

        assertEquals("/api/users", request.path)
        assertEquals("page=1&limit=10", request.queryString)
    }

    @Test
    fun `path matching`() {
        val request = createRequest(uri = "/api/v1/users/123")

        assertTrue(request.pathEquals("/api/v1/users/123"))
        assertFalse(request.pathEquals("/api/v1/users"))

        assertTrue(request.pathStartsWith("/api"))
        assertTrue(request.pathStartsWith("/api/v1"))
        assertFalse(request.pathStartsWith("/admin"))

        assertTrue(request.pathEndsWith("/123"))
        assertTrue(request.pathEndsWith("users/123"))
        assertFalse(request.pathEndsWith("/456"))

        assertTrue(request.pathContains("users"))
        assertTrue(request.pathContains("v1"))
        assertFalse(request.pathContains("admin"))
    }

    @Test
    fun `path matches regex`() {
        val request = createRequest(uri = "/api/users/123")

        assertTrue(request.pathMatches(Regex("/api/users/\\d+")))
        assertFalse(request.pathMatches(Regex("/api/posts/\\d+")))
    }

    @Test
    fun `headers case insensitive`() {
        val request = createRequest(
            headers = mapOf(
                "Content-Type" to listOf("application/json"),
                "Authorization" to listOf("Bearer token123"),
                "X-Custom-Header" to listOf("value1", "value2")
            )
        )

        assertEquals("application/json", request.header("content-type"))
        assertEquals("application/json", request.header("Content-Type"))
        assertEquals("application/json", request.header("CONTENT-TYPE"))

        assertEquals("Bearer token123", request.authorization)

        assertTrue(request.hasHeader("authorization"))
        assertTrue(request.hasHeader("AUTHORIZATION"))
        assertFalse(request.hasHeader("X-Missing"))
    }

    @Test
    fun `header all values`() {
        val request = createRequest(
            headers = mapOf(
                "Accept" to listOf("text/html", "application/json")
            )
        )

        val values = request.headerAll("accept")
        assertEquals(2, values.size)
        assertTrue("text/html" in values)
        assertTrue("application/json" in values)
    }

    @Test
    fun `common headers`() {
        val request = createRequest(
            headers = mapOf(
                "Host" to listOf("example.com"),
                "User-Agent" to listOf("Mozilla/5.0"),
                "Content-Type" to listOf("application/json"),
                "Content-Length" to listOf("123"),
                "Cookie" to listOf("session=abc123"),
                "Accept" to listOf("application/json"),
                "X-Forwarded-For" to listOf("10.0.0.1"),
                "X-Real-IP" to listOf("10.0.0.2")
            )
        )

        assertEquals("example.com", request.host)
        assertEquals("Mozilla/5.0", request.userAgent)
        assertEquals("application/json", request.contentType)
        assertEquals(123L, request.contentLength)
        assertEquals("session=abc123", request.cookie)
        assertEquals("application/json", request.accept)
        assertEquals("10.0.0.1", request.xForwardedFor)
        assertEquals("10.0.0.2", request.xRealIp)
    }

    @Test
    fun `query parameters`() {
        val request = createRequest(uri = "/search?q=hello&page=1&tag=a&tag=b")

        assertEquals("hello", request.query("q"))
        assertEquals("1", request.query("page"))
        assertNull(request.query("missing"))

        val tags = request.queryAll("tag")
        assertEquals(2, tags.size)
        assertTrue("a" in tags)
        assertTrue("b" in tags)

        assertTrue(request.hasQuery("q"))
        assertFalse(request.hasQuery("missing"))
    }

    @Test
    fun `query parameters url decoded`() {
        val request = createRequest(uri = "/search?q=hello%20world&name=John%26Jane")

        assertEquals("hello world", request.query("q"))
        assertEquals("John&Jane", request.query("name"))
    }

    @Test
    fun `content type checks`() {
        val jsonRequest = createRequest(headers = mapOf("Content-Type" to listOf("application/json")))
        assertTrue(jsonRequest.isJson())
        assertFalse(jsonRequest.isFormData())

        val formRequest = createRequest(headers = mapOf("Content-Type" to listOf("application/x-www-form-urlencoded")))
        assertTrue(formRequest.isFormData())
        assertFalse(formRequest.isJson())

        val multipartRequest = createRequest(headers = mapOf("Content-Type" to listOf("multipart/form-data; boundary=---")))
        assertTrue(multipartRequest.isMultipart())

        val xmlRequest = createRequest(headers = mapOf("Content-Type" to listOf("application/xml")))
        assertTrue(xmlRequest.isXml())
    }

    @Test
    fun `body operations`() {
        val request = createRequest()
        assertFalse(request.hasBody())
        assertNull(request.body())
        assertNull(request.bodyString())

        val requestWithBody = request.withBody("Hello, World!".toByteArray())
        assertTrue(requestWithBody.hasBody())
        assertEquals("Hello, World!", requestWithBody.bodyString())
    }

    @Test
    fun `empty query string`() {
        val request = createRequest(uri = "/path")
        assertNull(request.queryString)
        assertTrue(request.queryParams().isEmpty())
    }

    @Test
    fun `http method checks`() {
        val getRequest = createRequest(method = "GET")
        assertTrue(getRequest.isGet())
        assertFalse(getRequest.isPost())
        assertFalse(getRequest.isPut())
        assertFalse(getRequest.isDelete())
        assertFalse(getRequest.isPatch())

        val postRequest = createRequest(method = "POST")
        assertFalse(postRequest.isGet())
        assertTrue(postRequest.isPost())

        val putRequest = createRequest(method = "PUT")
        assertTrue(putRequest.isPut())

        val deleteRequest = createRequest(method = "DELETE")
        assertTrue(deleteRequest.isDelete())

        val patchRequest = createRequest(method = "PATCH")
        assertTrue(patchRequest.isPatch())
    }

    @Test
    fun `http method checks case insensitive`() {
        val getRequest = createRequest(method = "get")
        assertTrue(getRequest.isGet())

        val postRequest = createRequest(method = "Post")
        assertTrue(postRequest.isPost())
    }

    @Test
    fun `head and options method checks`() {
        val headRequest = createRequest(method = "HEAD")
        assertTrue(headRequest.isHead())
        assertFalse(headRequest.isGet())

        val optionsRequest = createRequest(method = "OPTIONS")
        assertTrue(optionsRequest.isOptions())
    }
}
