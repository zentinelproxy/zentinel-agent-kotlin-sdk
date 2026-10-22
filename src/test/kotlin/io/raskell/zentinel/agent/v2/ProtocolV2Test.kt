package io.raskell.zentinel.agent.v2

import io.raskell.zentinel.agent.EventType
import kotlinx.serialization.json.*
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
            .build()

        assertTrue(capabilities.handlesRequestHeaders)
        assertTrue(capabilities.handlesRequestBody)
        assertFalse(capabilities.handlesResponseHeaders)
        assertFalse(capabilities.handlesResponseBody)
        assertTrue(capabilities.supportsStreaming)
        assertTrue(capabilities.supportsCancellation)
        assertEquals(100, capabilities.maxConcurrentRequests)
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
    fun `message type codes match the proxy`() {
        // zentinel_agent_protocol::v2::uds::MessageType
        assertEquals(0x01, MessageType.HANDSHAKE_REQUEST.toInt())
        assertEquals(0x02, MessageType.HANDSHAKE_RESPONSE.toInt())
        assertEquals(0x10, MessageType.REQUEST_HEADERS.toInt())
        assertEquals(0x11, MessageType.REQUEST_BODY_CHUNK.toInt())
        assertEquals(0x12, MessageType.RESPONSE_HEADERS.toInt())
        assertEquals(0x13, MessageType.RESPONSE_BODY_CHUNK.toInt())
        assertEquals(0x14, MessageType.REQUEST_COMPLETE.toInt())
        assertEquals(0x15, MessageType.WEBSOCKET_FRAME.toInt())
        assertEquals(0x16, MessageType.GUARDRAIL_INSPECT.toInt())
        assertEquals(0x17, MessageType.CONFIGURE.toInt())
        assertEquals(0x20, MessageType.AGENT_RESPONSE.toInt())
        assertEquals(0x40, MessageType.CANCEL.toInt())
        assertEquals(0x41, MessageType.PING.toInt())
        assertEquals(0x42, MessageType.PONG.toInt())
        assertEquals(EventType.CONFIGURE, MessageType.eventType(MessageType.CONFIGURE))
        assertNull(MessageType.eventType(MessageType.PING))
    }

    @Test
    fun `parses the handshake request the proxy sends`() {
        // What zentinel's UDS client writes (UdsHandshakeRequest).
        val json = """{"supported_versions":[2],"proxy_id":"zentinel-proxy","proxy_version":"0.6.50",""" +
            """"config":{"greeting":"hi"},"supported_encodings":["msgpack","json"]}"""
        val request = protocolV2Json.decodeFromString(HandshakeRequest.serializer(), json)
        assertEquals(listOf(2), request.supportedVersions)
        assertEquals("zentinel-proxy", request.proxyId)
        assertEquals("0.6.50", request.proxyVersion)
        assertEquals(JsonPrimitive("hi"), request.config!!.jsonObject["greeting"])
    }

    @Test
    fun `handshake response has every field UdsHandshakeResponse requires`() {
        val caps = AgentCapabilities.builder().version("1.2.3").handlesRequestBody(true).build()
        val response = HandshakeResponse(capabilities = caps.toUds("my-agent"), success = true)
        val json = protocolV2Json.encodeToJsonElement(HandshakeResponse.serializer(), response).jsonObject

        assertEquals(setOf("protocol_version", "capabilities", "success", "error", "encoding"), json.keys)
        assertEquals(JsonPrimitive(2), json["protocol_version"])
        assertEquals(JsonPrimitive(true), json["success"])
        assertEquals(JsonNull, json["error"])
        assertEquals(JsonPrimitive("json"), json["encoding"])

        val c = json.getValue("capabilities").jsonObject
        assertEquals(setOf("agent_id", "name", "version", "supported_events", "features", "limits"), c.keys)
        assertEquals(JsonPrimitive("my-agent"), c["agent_id"])
        assertEquals(JsonPrimitive("my-agent"), c["name"])
        assertEquals(JsonPrimitive("1.2.3"), c["version"])
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))), c["supported_events"])
        assertEquals(
            setOf(
                "streaming_body", "websocket", "guardrails", "config_push", "metrics_export",
                "concurrent_requests", "cancellation", "flow_control", "health_reporting"
            ),
            c.getValue("features").jsonObject.keys
        )
        assertEquals(
            setOf("max_body_size", "max_concurrency", "preferred_chunk_size"),
            c.getValue("limits").jsonObject.keys
        )
    }

    @Test
    fun `event numbers per transport`() {
        assertEquals(0, EventType.CONFIGURE.udsNumber())
        assertEquals(8, EventType.CONFIGURE.grpcNumber())
        assertEquals(1, EventType.REQUEST_HEADERS.udsNumber())
        assertEquals(1, EventType.REQUEST_HEADERS.grpcNumber())
        assertEquals(7, EventType.GUARDRAIL_INSPECT.udsNumber())
    }

    @Test
    fun `frame length counts the type byte`() {
        val buffer = AgentRunnerV2.encodeFrame(MessageType.PONG, "{}".toByteArray())
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        assertEquals(listOf<Byte>(0, 0, 0, 3, 0x42, '{'.code.toByte(), '}'.code.toByte()), bytes.toList())
    }
}
