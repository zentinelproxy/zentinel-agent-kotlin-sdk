package io.raskell.zentinel.agent.v2

import io.raskell.zentinel.agent.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentHandlerV2Test {

    @Test
    fun `handler returns capabilities from agent`() {
        val agent = object : AgentV2 {
            override val name = "test-agent"
            override fun capabilities() = AgentCapabilities.builder()
                .handlesRequestHeaders(true)
                .handlesRequestBody(true)
                .maxConcurrentRequests(50)
                .build()
        }

        val handler = AgentHandlerV2(agent)
        val capabilities = handler.capabilities()

        assertTrue(capabilities.handlesRequestHeaders)
        assertTrue(capabilities.handlesRequestBody)
        assertEquals(50, capabilities.maxConcurrentRequests)
    }

    @Test
    fun `handler returns health status from agent`() {
        val agent = object : AgentV2 {
            override val name = "test-agent"
            override fun healthStatus() = HealthStatus.Degraded("High load", 0.8f)
        }

        val handler = AgentHandlerV2(agent)
        val status = handler.healthStatus()

        assertTrue(status.isDegraded())
        assertEquals("High load", (status as HealthStatus.Degraded).reason)
    }

    @Test
    fun `handler returns metrics from agent`() {
        val agent = object : AgentV2 {
            override val name = "test-agent"
            override fun metrics() = MetricsReport.builder()
                .requestsProcessed(100)
                .requestsBlocked(10)
                .build()
        }

        val handler = AgentHandlerV2(agent)
        val metrics = handler.metrics()

        assertEquals(100, metrics.requestsProcessed)
        assertEquals(10, metrics.requestsBlocked)
    }

    @Test
    fun `handler performs handshake`() {
        val agent = object : AgentV2 {
            override val name = "handshake-agent"
            override fun capabilities() = AgentCapabilities.fullInspection()
        }

        val handler = AgentHandlerV2(agent)
        val request = HandshakeRequest(
            protocolVersion = 2,
            clientName = "zentinel-proxy",
            supportedFeatures = listOf("streaming")
        )

        val response = handler.handleHandshake(request)

        assertEquals(PROTOCOL_V2_VERSION, response.protocolVersion)
        assertEquals("handshake-agent", response.agentName)
        assertTrue(response.capabilities.handlesRequestHeaders)
        assertTrue(response.capabilities.handlesRequestBody)
    }

    @Test
    fun `handler processes request headers`() = runBlocking {
        var capturedRequest: Request? = null

        val agent = object : AgentV2 {
            override val name = "request-agent"

            override suspend fun onRequest(request: Request): Decision {
                capturedRequest = request
                return Decision.allow()
                    .addRequestHeader("X-Processed", "true")
                    .withTag("processed")
            }
        }

        val handler = AgentHandlerV2(agent)
        val msg = RequestHeadersV2(
            requestId = 123,
            metadata = RequestMetadataV2(
                correlationId = "corr-001",
                clientIp = "192.168.1.1",
                clientPort = 5000
            ),
            method = "GET",
            uri = "/api/test",
            headers = mapOf("Accept" to listOf("application/json"))
        )

        val decision = handler.handleRequestHeaders(msg)

        assertEquals(123, decision.requestId)
        assertTrue(decision.decision is DecisionV2.Allow)
        assertTrue(decision.requestHeaders.any { it is HeaderOpV2.Set && it.name == "X-Processed" })
        assertEquals("corr-001", capturedRequest?.correlationId)
        assertEquals("GET", capturedRequest?.method)
        assertEquals("/api/test", capturedRequest?.path)
    }

    @Test
    fun `handler blocks requests`() = runBlocking {
        val agent = object : AgentV2 {
            override val name = "blocking-agent"

            override suspend fun onRequest(request: Request): Decision {
                return Decision.deny()
                    .withBody("Access denied")
                    .withTag("blocked")
            }
        }

        val handler = AgentHandlerV2(agent)
        val msg = RequestHeadersV2(
            requestId = 456,
            metadata = RequestMetadataV2(
                correlationId = "corr-002",
                clientIp = "10.0.0.1",
                clientPort = 6000
            ),
            method = "POST",
            uri = "/admin"
        )

        val decision = handler.handleRequestHeaders(msg)

        assertEquals(456, decision.requestId)
        assertTrue(decision.decision is DecisionV2.Block)
        assertEquals(403, (decision.decision as DecisionV2.Block).status)
        assertEquals("Access denied", (decision.decision as DecisionV2.Block).body)
    }

    @Test
    fun `handler processes request body`() = runBlocking {
        var capturedBody: String? = null

        val agent = object : AgentV2 {
            override val name = "body-agent"
            override fun capabilities() = AgentCapabilities.builder()
                .handlesRequestBody(true)
                .build()

            override suspend fun onRequestBody(request: Request): Decision {
                capturedBody = request.bodyString()
                return Decision.allow()
            }
        }

        val handler = AgentHandlerV2(agent)

        // First, send request headers
        val headersMsg = RequestHeadersV2(
            requestId = 789,
            metadata = RequestMetadataV2(
                correlationId = "corr-003",
                clientIp = "127.0.0.1",
                clientPort = 7000
            ),
            method = "POST",
            uri = "/api/data",
            hasBody = true
        )
        handler.handleRequestHeaders(headersMsg)

        // Then send body chunk
        val bodyData = """{"name":"test"}"""
        val encodedBody = Base64.getEncoder().encodeToString(bodyData.toByteArray())
        val bodyMsg = RequestBodyChunkV2(
            requestId = 789,
            chunkIndex = 0,
            data = encodedBody,
            isLast = true
        )

        val decision = handler.handleRequestBodyChunk(bodyMsg)

        assertEquals(789, decision.requestId)
        assertTrue(decision.decision is DecisionV2.Allow)
        assertEquals(bodyData, capturedBody)
    }

    @Test
    fun `handler handles request cancellation`() = runBlocking {
        var cancelledId: Long? = null
        var cancelledReason: String? = null

        val agent = object : AgentV2 {
            override val name = "cancel-agent"

            override suspend fun onRequestCancelled(requestId: Long, reason: String?) {
                cancelledId = requestId
                cancelledReason = reason
            }
        }

        val handler = AgentHandlerV2(agent)

        // First create a request
        val headersMsg = RequestHeadersV2(
            requestId = 999,
            metadata = RequestMetadataV2(
                correlationId = "corr-cancel",
                clientIp = "127.0.0.1",
                clientPort = 8000
            ),
            method = "GET",
            uri = "/slow"
        )
        handler.handleRequestHeaders(headersMsg)

        assertEquals(1, handler.activeRequestCount())

        // Cancel it
        val cancelMsg = CancelRequestMessage(
            requestId = 999,
            reason = "Client disconnected"
        )
        handler.handleCancelRequest(cancelMsg)

        assertEquals(0, handler.activeRequestCount())
        assertEquals(999L, cancelledId)
        assertEquals("Client disconnected", cancelledReason)
    }

    @Test
    fun `handler handles cancel all`() = runBlocking {
        var allCancelledReason: String? = null

        val agent = object : AgentV2 {
            override val name = "cancel-all-agent"

            override suspend fun onAllRequestsCancelled(reason: String?) {
                allCancelledReason = reason
            }
        }

        val handler = AgentHandlerV2(agent)

        // Create multiple requests
        for (i in 1..3) {
            val headersMsg = RequestHeadersV2(
                requestId = i.toLong(),
                metadata = RequestMetadataV2(
                    correlationId = "corr-$i",
                    clientIp = "127.0.0.1",
                    clientPort = 8000 + i
                ),
                method = "GET",
                uri = "/test/$i"
            )
            handler.handleRequestHeaders(headersMsg)
        }

        assertEquals(3, handler.activeRequestCount())

        // Cancel all
        val cancelAllMsg = CancelAllMessage(reason = "Agent shutdown")
        handler.handleCancelAll(cancelAllMsg)

        assertEquals(0, handler.activeRequestCount())
        assertEquals("Agent shutdown", allCancelledReason)
    }

    @Test
    fun `handler calls shutdown on agent`() = runBlocking {
        var shutdownCalled = false

        val agent = object : AgentV2 {
            override val name = "shutdown-agent"

            override suspend fun onShutdown() {
                shutdownCalled = true
            }
        }

        val handler = AgentHandlerV2(agent)
        handler.shutdown()

        assertTrue(shutdownCalled)
    }

    @Test
    fun `handler converts response headers to decision`() = runBlocking {
        val agent = object : AgentV2 {
            override val name = "response-agent"
            override fun capabilities() = AgentCapabilities.builder()
                .handlesResponseHeaders(true)
                .build()

            override suspend fun onResponse(request: Request, response: Response): Decision {
                return Decision.allow()
                    .addResponseHeader("X-Served-By", "zentinel")
                    .removeResponseHeader("Server")
            }
        }

        val handler = AgentHandlerV2(agent)

        // First create a request
        val headersMsg = RequestHeadersV2(
            requestId = 100,
            metadata = RequestMetadataV2(
                correlationId = "corr-resp",
                clientIp = "127.0.0.1",
                clientPort = 9000
            ),
            method = "GET",
            uri = "/test"
        )
        handler.handleRequestHeaders(headersMsg)

        // Then send response headers
        val responseMsg = ResponseHeadersV2(
            requestId = 100,
            statusCode = 200,
            headers = mapOf("Content-Type" to listOf("text/html"))
        )

        val decision = handler.handleResponseHeaders(responseMsg)

        assertEquals(100, decision.requestId)
        assertTrue(decision.decision is DecisionV2.Allow)
        assertTrue(decision.responseHeaders.any { it is HeaderOpV2.Set && it.name == "X-Served-By" })
        assertTrue(decision.responseHeaders.any { it is HeaderOpV2.Remove && it.name == "Server" })
    }

    @Test
    fun `handler includes audit metadata in decision`() = runBlocking {
        val agent = object : AgentV2 {
            override val name = "audit-agent"

            override suspend fun onRequest(request: Request): Decision {
                return Decision.deny()
                    .withTag("blocked")
                    .withRuleId("SEC-001")
                    .withConfidence(0.95f)
                    .withReasonCode("MALICIOUS")
            }
        }

        val handler = AgentHandlerV2(agent)
        val msg = RequestHeadersV2(
            requestId = 200,
            metadata = RequestMetadataV2(
                correlationId = "corr-audit",
                clientIp = "10.0.0.1",
                clientPort = 10000
            ),
            method = "POST",
            uri = "/attack"
        )

        val decision = handler.handleRequestHeaders(msg)

        assertTrue(decision.audit != null)
        assertTrue("blocked" in decision.audit!!.tags)
        assertTrue("SEC-001" in decision.audit!!.ruleIds)
        assertEquals(0.95f, decision.audit!!.confidence)
        assertTrue("MALICIOUS" in decision.audit!!.reasonCodes)
    }
}
