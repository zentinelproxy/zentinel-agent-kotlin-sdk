package io.raskell.zentinel.agent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponseTest {

    private fun createResponse(
        status: Int = 200,
        headers: Map<String, List<String>> = emptyMap()
    ): Response {
        val event = ResponseHeadersEvent(
            correlationId = "test-123",
            status = status,
            headers = headers
        )
        return Response.fromEvent(event)
    }

    @Test
    fun `basic response properties`() {
        val response = createResponse(status = 201)

        assertEquals(201, response.statusCode)
        assertEquals("test-123", response.correlationId)
    }

    @Test
    fun `status code categories`() {
        assertTrue(createResponse(100).isInformational())
        assertTrue(createResponse(101).isInformational())
        assertFalse(createResponse(200).isInformational())

        assertTrue(createResponse(200).isSuccess())
        assertTrue(createResponse(201).isSuccess())
        assertTrue(createResponse(204).isSuccess())
        assertFalse(createResponse(300).isSuccess())

        assertTrue(createResponse(301).isRedirection())
        assertTrue(createResponse(302).isRedirection())
        assertTrue(createResponse(304).isRedirection())
        assertFalse(createResponse(400).isRedirection())

        assertTrue(createResponse(400).isClientError())
        assertTrue(createResponse(404).isClientError())
        assertTrue(createResponse(401).isClientError())
        assertFalse(createResponse(500).isClientError())

        assertTrue(createResponse(500).isServerError())
        assertTrue(createResponse(502).isServerError())
        assertTrue(createResponse(503).isServerError())
        assertFalse(createResponse(400).isServerError())

        assertTrue(createResponse(400).isError())
        assertTrue(createResponse(500).isError())
        assertFalse(createResponse(200).isError())
    }

    @Test
    fun `specific status codes`() {
        assertTrue(createResponse(200).isOk())
        assertFalse(createResponse(201).isOk())

        assertTrue(createResponse(404).isNotFound())
        assertFalse(createResponse(200).isNotFound())

        assertTrue(createResponse(401).isUnauthorized())
        assertFalse(createResponse(403).isUnauthorized())

        assertTrue(createResponse(403).isForbidden())
        assertFalse(createResponse(401).isForbidden())
    }

    @Test
    fun `headers case insensitive`() {
        val response = createResponse(
            headers = mapOf(
                "Content-Type" to listOf("application/json"),
                "Cache-Control" to listOf("no-cache")
            )
        )

        assertEquals("application/json", response.header("content-type"))
        assertEquals("application/json", response.header("Content-Type"))
        assertEquals("application/json", response.header("CONTENT-TYPE"))

        assertTrue(response.hasHeader("content-type"))
        assertTrue(response.hasHeader("CONTENT-TYPE"))
        assertFalse(response.hasHeader("X-Missing"))
    }

    @Test
    fun `header all values`() {
        val response = createResponse(
            headers = mapOf(
                "Set-Cookie" to listOf("session=abc", "user=xyz")
            )
        )

        val values = response.headerAll("set-cookie")
        assertEquals(2, values.size)
        assertTrue("session=abc" in values)
        assertTrue("user=xyz" in values)
    }

    @Test
    fun `common headers`() {
        val response = createResponse(
            headers = mapOf(
                "Content-Type" to listOf("text/html"),
                "Content-Length" to listOf("1024"),
                "Cache-Control" to listOf("max-age=3600"),
                "ETag" to listOf("\"abc123\""),
                "Last-Modified" to listOf("Wed, 21 Oct 2023 07:28:00 GMT"),
                "Location" to listOf("/new-path"),
                "Server" to listOf("nginx/1.18.0")
            )
        )

        assertEquals("text/html", response.contentType)
        assertEquals(1024L, response.contentLength)
        assertEquals("max-age=3600", response.cacheControl)
        assertEquals("\"abc123\"", response.etag)
        assertEquals("Wed, 21 Oct 2023 07:28:00 GMT", response.lastModified)
        assertEquals("/new-path", response.location)
        assertEquals("nginx/1.18.0", response.server)
    }

    @Test
    fun `content type checks`() {
        assertTrue(createResponse(headers = mapOf("Content-Type" to listOf("application/json"))).isJson())
        assertTrue(createResponse(headers = mapOf("Content-Type" to listOf("application/json; charset=utf-8"))).isJson())

        assertTrue(createResponse(headers = mapOf("Content-Type" to listOf("text/html"))).isHtml())
        assertTrue(createResponse(headers = mapOf("Content-Type" to listOf("text/html; charset=utf-8"))).isHtml())

        assertTrue(createResponse(headers = mapOf("Content-Type" to listOf("text/plain"))).isPlainText())

        assertTrue(createResponse(headers = mapOf("Content-Type" to listOf("application/xml"))).isXml())
        assertTrue(createResponse(headers = mapOf("Content-Type" to listOf("text/xml"))).isXml())

        assertTrue(createResponse(headers = mapOf("Content-Type" to listOf("text/css"))).isCss())

        assertTrue(createResponse(headers = mapOf("Content-Type" to listOf("application/javascript"))).isJavaScript())
        assertTrue(createResponse(headers = mapOf("Content-Type" to listOf("text/javascript"))).isJavaScript())

        assertTrue(createResponse(headers = mapOf("Content-Type" to listOf("image/png"))).isImage())
        assertTrue(createResponse(headers = mapOf("Content-Type" to listOf("image/jpeg"))).isImage())
    }

    @Test
    fun `body operations`() {
        val response = createResponse()
        assertFalse(response.hasBody())
        assertNull(response.body())
        assertNull(response.bodyString())

        val responseWithBody = response.withBody("Hello, World!".toByteArray())
        assertTrue(responseWithBody.hasBody())
        assertEquals("Hello, World!", responseWithBody.bodyString())
    }

    @Test
    fun `missing headers return null`() {
        val response = createResponse()

        assertNull(response.contentType)
        assertNull(response.contentLength)
        assertNull(response.cacheControl)
        assertNull(response.etag)
        assertNull(response.location)
    }
}
