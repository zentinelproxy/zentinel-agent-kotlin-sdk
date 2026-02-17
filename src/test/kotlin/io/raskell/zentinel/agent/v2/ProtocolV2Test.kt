package io.raskell.zentinel.agent.v2

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolV2Test {

    @Test
    fun `AgentCapabilities builder creates correct capabilities`() {
        val capabilities = AgentCapabilities.builder()
            .handlesRequestHeaders(true)
            .handlesRequestBody(true)
            .handlesResponseHeaders(false)
            .handlesResponseBody(false)
            .supportsStreaming(true)
            .supportsCancellation(true)
            .maxConcurrentRequests(100)
            .addFeature("sql-detection")
            .build()

        assertTrue(capabilities.handlesRequestHeaders)
        assertTrue(capabilities.handlesRequestBody)
        assertFalse(capabilities.handlesResponseHeaders)
        assertFalse(capabilities.handlesResponseBody)
        assertTrue(capabilities.supportsStreaming)
        assertTrue(capabilities.supportsCancellation)
        assertEquals(100, capabilities.maxConcurrentRequests)
        assertTrue("sql-detection" in capabilities.supportedFeatures)
    }

    @Test
    fun `AgentCapabilities requestOnly creates minimal capabilities`() {
        val capabilities = AgentCapabilities.requestOnly()

        assertTrue(capabilities.handlesRequestHeaders)
        assertFalse(capabilities.handlesRequestBody)
        assertFalse(capabilities.handlesResponseHeaders)
        assertFalse(capabilities.handlesResponseBody)
    }

    @Test
    fun `AgentCapabilities fullInspection creates full capabilities`() {
        val capabilities = AgentCapabilities.fullInspection()

        assertTrue(capabilities.handlesRequestHeaders)
        assertTrue(capabilities.handlesRequestBody)
        assertTrue(capabilities.handlesResponseHeaders)
        assertTrue(capabilities.handlesResponseBody)
        assertTrue(capabilities.supportsStreaming)
        assertTrue(capabilities.supportsCancellation)
    }

    @Test
    fun `HealthStatus sealed class variants`() {
        val healthy = HealthStatus.Healthy
        assertTrue(healthy.isHealthy())
        assertFalse(healthy.isDegraded())
        assertFalse(healthy.isUnhealthy())

        val degraded = HealthStatus.Degraded("High load", 0.8f)
        assertFalse(degraded.isHealthy())
        assertTrue(degraded.isDegraded())
        assertFalse(degraded.isUnhealthy())
        assertEquals("High load", degraded.reason)
        assertEquals(0.8f, degraded.load)

        val unhealthy = HealthStatus.Unhealthy("Connection failed", 5000)
        assertFalse(unhealthy.isHealthy())
        assertFalse(unhealthy.isDegraded())
        assertTrue(unhealthy.isUnhealthy())
        assertEquals("Connection failed", unhealthy.reason)
        assertEquals(5000L, unhealthy.retryAfterMs)
    }

    @Test
    fun `MetricsReport builder creates correct report`() {
        val metrics = MetricsReport.builder()
            .requestsProcessed(1000)
            .requestsBlocked(50)
            .requestsAllowed(950)
            .averageLatencyMs(5.2)
            .p99LatencyMs(25.0)
            .activeRequests(10)
            .errorCount(5)
            .uptimeSeconds(3600)
            .customMetric("custom_counter", 42)
            .customMetric("custom_string", "value")
            .customMetric("custom_bool", true)
            .build()

        assertEquals(1000, metrics.requestsProcessed)
        assertEquals(50, metrics.requestsBlocked)
        assertEquals(950, metrics.requestsAllowed)
        assertEquals(5.2, metrics.averageLatencyMs)
        assertEquals(25.0, metrics.p99LatencyMs)
        assertEquals(10, metrics.activeRequests)
        assertEquals(5, metrics.errorCount)
        assertEquals(3600, metrics.uptimeSeconds)
        assertEquals(JsonPrimitive(42), metrics.custom["custom_counter"])
        assertEquals(JsonPrimitive("value"), metrics.custom["custom_string"])
        assertEquals(JsonPrimitive(true), metrics.custom["custom_bool"])
    }

    @Test
    fun `HandshakeRequest serialization`() {
        val request = HandshakeRequest(
            protocolVersion = 2,
            clientName = "zentinel-proxy",
            supportedFeatures = listOf("streaming", "cancellation"),
            supportedEncodings = listOf("json", "msgpack")
        )

        val json = protocolV2Json.encodeToString(request)

        assertTrue(json.contains("\"protocol_version\":2"))
        assertTrue(json.contains("\"client_name\":\"zentinel-proxy\""))
        assertTrue(json.contains("\"supported_features\""))
    }

    @Test
    fun `HandshakeResponse serialization`() {
        val response = HandshakeResponse(
            protocolVersion = 2,
            agentName = "test-agent",
            capabilities = AgentCapabilities.requestOnly(),
            encoding = "json"
        )

        val json = protocolV2Json.encodeToString(response)

        assertTrue(json.contains("\"protocol_version\":2"))
        assertTrue(json.contains("\"agent_name\":\"test-agent\""))
        assertTrue(json.contains("\"capabilities\""))
        assertTrue(json.contains("\"encoding\":\"json\""))
    }

    @Test
    fun `DecisionV2 Allow serialization`() {
        val decision = DecisionMessageV2(
            requestId = 123,
            decision = DecisionV2.Allow
        )

        val json = protocolV2Json.encodeToString(decision)

        assertTrue(json.contains("\"request_id\":123"))
        assertTrue(json.contains("\"allow\""))
    }

    @Test
    fun `DecisionV2 Block serialization`() {
        val decision = DecisionMessageV2(
            requestId = 456,
            decision = DecisionV2.Block(
                status = 403,
                body = "Access denied",
                headers = mapOf("X-Error" to "true")
            )
        )

        val json = protocolV2Json.encodeToString(decision)

        assertTrue(json.contains("\"request_id\":456"))
        assertTrue(json.contains("\"block\""))
        assertTrue(json.contains("\"status\":403"))
        assertTrue(json.contains("\"body\":\"Access denied\""))
    }

    @Test
    fun `DecisionV2 Redirect serialization`() {
        val decision = DecisionMessageV2(
            requestId = 789,
            decision = DecisionV2.Redirect(
                url = "/login",
                status = 302
            )
        )

        val json = protocolV2Json.encodeToString(decision)

        assertTrue(json.contains("\"request_id\":789"))
        assertTrue(json.contains("\"redirect\""))
        assertTrue(json.contains("\"url\":\"/login\""))
        assertTrue(json.contains("\"status\":302"))
    }

    @Test
    fun `RequestHeadersV2 deserialization`() {
        val json = """
            {
                "request_id": 100,
                "metadata": {
                    "correlation_id": "corr-123",
                    "client_ip": "192.168.1.1",
                    "client_port": 12345,
                    "protocol": "HTTP/2"
                },
                "method": "POST",
                "uri": "/api/users",
                "headers": {
                    "Content-Type": ["application/json"]
                },
                "has_body": true
            }
        """.trimIndent()

        val msg = protocolV2Json.decodeFromString<RequestHeadersV2>(json)

        assertEquals(100, msg.requestId)
        assertEquals("corr-123", msg.metadata.correlationId)
        assertEquals("192.168.1.1", msg.metadata.clientIp)
        assertEquals(12345, msg.metadata.clientPort)
        assertEquals("HTTP/2", msg.metadata.protocol)
        assertEquals("POST", msg.method)
        assertEquals("/api/users", msg.uri)
        assertTrue(msg.hasBody)
    }

    @Test
    fun `CancelRequestMessage serialization`() {
        val msg = CancelRequestMessage(
            requestId = 999,
            reason = "Client disconnected"
        )

        val json = protocolV2Json.encodeToString(msg)

        assertTrue(json.contains("\"request_id\":999"))
        assertTrue(json.contains("\"reason\":\"Client disconnected\""))
    }

    @Test
    fun `RegistrationRequest serialization`() {
        val request = RegistrationRequest(
            protocolVersion = 2,
            agentId = "waf-worker-1",
            capabilities = AgentCapabilities.fullInspection(),
            authToken = "secret-token"
        )

        val json = protocolV2Json.encodeToString(request)

        assertTrue(json.contains("\"protocol_version\":2"))
        assertTrue(json.contains("\"agent_id\":\"waf-worker-1\""))
        assertTrue(json.contains("\"auth_token\":\"secret-token\""))
    }

    @Test
    fun `RegistrationResponse deserialization`() {
        val json = """
            {
                "accepted": true,
                "assigned_id": "conn-001"
            }
        """.trimIndent()

        val response = protocolV2Json.decodeFromString<RegistrationResponse>(json)

        assertTrue(response.accepted)
        assertNull(response.error)
        assertEquals("conn-001", response.assignedId)
    }

    @Test
    fun `HeaderOpV2 serialization`() {
        val set = HeaderOpV2.Set("X-User-ID", "123")
        val add = HeaderOpV2.Add("X-Tag", "important")
        val remove = HeaderOpV2.Remove("Cookie")

        val setJson = protocolV2Json.encodeToString<HeaderOpV2>(set)
        val addJson = protocolV2Json.encodeToString<HeaderOpV2>(add)
        val removeJson = protocolV2Json.encodeToString<HeaderOpV2>(remove)

        assertTrue(setJson.contains("\"set\""))
        assertTrue(setJson.contains("\"X-User-ID\""))
        assertTrue(addJson.contains("\"add\""))
        assertTrue(addJson.contains("\"X-Tag\""))
        assertTrue(removeJson.contains("\"remove\""))
        assertTrue(removeJson.contains("\"Cookie\""))
    }

    @Test
    fun `AuditMetadataV2 serialization`() {
        val audit = AuditMetadataV2(
            tags = listOf("blocked", "security"),
            ruleIds = listOf("SQLI-001"),
            confidence = 0.95f,
            reasonCodes = listOf("MALICIOUS_PAYLOAD"),
            custom = mapOf("pattern" to JsonPrimitive("SELECT.*"))
        )

        val json = protocolV2Json.encodeToString(audit)

        assertTrue(json.contains("\"tags\":[\"blocked\",\"security\"]"))
        assertTrue(json.contains("\"rule_ids\":[\"SQLI-001\"]"))
        assertTrue(json.contains("\"confidence\":0.95"))
        assertTrue(json.contains("\"reason_codes\":[\"MALICIOUS_PAYLOAD\"]"))
        assertTrue(json.contains("\"pattern\":\"SELECT.*\""))
    }
}
