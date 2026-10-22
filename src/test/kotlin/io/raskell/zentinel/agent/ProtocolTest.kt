package io.raskell.zentinel.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {

    @Test
    fun `serialize allow decision`() {
        val response = AgentResponse(decision = ProtocolDecision.Allow)
        val json = protocolJson.encodeToString(response)

        // kotlinx.serialization serializes sealed class with type discriminator
        assertTrue(json.contains("\"decision\""))
        assertTrue(json.contains("allow"))
    }

    @Test
    fun `serialize block decision`() {
        val response = AgentResponse(
            decision = ProtocolDecision.Block(
                status = 403,
                body = "Access denied"
            )
        )
        val json = protocolJson.encodeToString(response)

        assertTrue(json.contains("\"block\""))
        assertTrue(json.contains("\"status\":403"))
        assertTrue(json.contains("\"body\":\"Access denied\""))
    }

    @Test
    fun `serialize redirect decision`() {
        val response = AgentResponse(
            decision = ProtocolDecision.Redirect(
                url = "/login",
                status = 302
            )
        )
        val json = protocolJson.encodeToString(response)

        assertTrue(json.contains("\"redirect\""))
        assertTrue(json.contains("\"url\":\"/login\""))
        assertTrue(json.contains("\"status\":302"))
    }

    @Test
    fun `serialize header operations`() {
        val response = AgentResponse(
            requestHeaders = listOf(
                HeaderOp.Set("X-User", "123"),
                HeaderOp.Remove("Cookie")
            )
        )
        val json = protocolJson.encodeToString(response)

        assertTrue(json.contains("\"set\""))
        assertTrue(json.contains("\"X-User\""))
        assertTrue(json.contains("\"remove\""))
        assertTrue(json.contains("\"Cookie\""))
    }

    @Test
    fun `serialize audit metadata`() {
        val response = AgentResponse(
            audit = AuditMetadata(
                tags = listOf("blocked", "security"),
                ruleIds = listOf("SQLI-001"),
                confidence = 0.95f,
                reasonCodes = listOf("MALICIOUS_PAYLOAD"),
                custom = mapOf("pattern" to JsonPrimitive("SELECT.*"))
            )
        )
        val json = protocolJson.encodeToString(response)

        assertTrue(json.contains("\"tags\":[\"blocked\",\"security\"]"))
        assertTrue(json.contains("\"rule_ids\":[\"SQLI-001\"]"))
        assertTrue(json.contains("\"confidence\":0.95"))
        assertTrue(json.contains("\"reason_codes\":[\"MALICIOUS_PAYLOAD\"]"))
        assertTrue(json.contains("\"pattern\":\"SELECT.*\""))
    }

    @Test
    fun `serialize body mutation`() {
        val response = AgentResponse(
            requestBodyMutation = BodyMutation.replace(0, "modified")
        )
        val json = protocolJson.encodeToString(response)

        assertTrue(json.contains("\"request_body_mutation\""))
        assertTrue(json.contains("\"data\":\"modified\""))
        assertTrue(json.contains("\"chunk_index\":0"))
    }

    @Test
    fun `deserialize request headers event`() {
        val json = """
            {
                "metadata": {
                    "correlation_id": "test-123",
                    "request_id": "req-456",
                    "client_ip": "192.168.1.1",
                    "client_port": 12345,
                    "protocol": "HTTP/1.1",
                    "timestamp": "2024-01-01T00:00:00Z"
                },
                "method": "POST",
                "uri": "/api/users",
                "headers": {
                    "Content-Type": ["application/json"],
                    "Authorization": ["Bearer token123"]
                }
            }
        """.trimIndent()

        val event = protocolJson.decodeFromString<RequestHeadersEvent>(json)

        assertEquals("test-123", event.metadata.correlationId)
        assertEquals("POST", event.method)
        assertEquals("/api/users", event.uri)
        assertEquals(listOf("application/json"), event.headers["Content-Type"])
    }

    @Test
    fun `deserialize response headers event`() {
        val json = """
            {
                "correlation_id": "test-123",
                "status": 200,
                "headers": {
                    "Content-Type": ["application/json"],
                    "Cache-Control": ["no-cache"]
                }
            }
        """.trimIndent()

        val event = protocolJson.decodeFromString<ResponseHeadersEvent>(json)

        assertEquals("test-123", event.correlationId)
        assertEquals(200, event.status)
        assertEquals(listOf("application/json"), event.headers["Content-Type"])
    }

    @Test
    fun `deserialize request complete event`() {
        val json = """
            {
                "correlation_id": "test-123",
                "status": 200,
                "duration_ms": 42,
                "request_body_size": 1024,
                "response_body_size": 2048,
                "upstream_attempts": 1
            }
        """.trimIndent()

        val event = protocolJson.decodeFromString<RequestCompleteEvent>(json)

        assertEquals("test-123", event.correlationId)
        assertEquals(200, event.status)
        assertEquals(42L, event.durationMs)
        assertEquals(1024L, event.requestBodySize)
        assertEquals(2048L, event.responseBodySize)
        assertEquals(1, event.upstreamAttempts)
        assertNull(event.error)
    }

    @Test
    fun `body mutation helpers`() {
        val passThrough = BodyMutation.passThrough(0)
        assertTrue(passThrough.isPassThrough())

        val drop = BodyMutation.dropChunk(1)
        assertTrue(drop.isDrop())
        assertEquals("", drop.data)

        val replace = BodyMutation.replace(2, "new content")
        assertEquals("new content", replace.data)
        assertEquals(2, replace.chunkIndex)
    }

    @Test
    fun `default agent response`() {
        val response = AgentResponse.defaultAllow()

        assertEquals(PROTOCOL_VERSION, response.version)
        assertEquals(ProtocolDecision.Allow, response.decision)
        assertTrue(response.requestHeaders.isEmpty())
        assertTrue(response.responseHeaders.isEmpty())
        assertTrue(response.routingMetadata.isEmpty())
    }

    // The proxy deserializes AgentResponse with serde: enums are externally
    // tagged and a unit variant is a bare string. A "type" discriminator
    // (kotlinx.serialization's default for sealed classes) is rejected.

    @Test
    fun `protocol version is 2`() {
        assertEquals(2, PROTOCOL_VERSION)
        assertEquals(2, Decision.allow().build().version)
    }

    @Test
    fun `allow is a bare string as serde expects`() {
        val json = protocolJson.encodeToJsonElement(AgentResponse.serializer(), AgentResponse()).jsonObject
        assertEquals(JsonPrimitive("allow"), json["decision"])
    }

    @Test
    fun `block is externally tagged as serde expects`() {
        val json = protocolJson.encodeToJsonElement(
            AgentResponse.serializer(),
            Decision.block(403).withBody("nope").build()
        ).jsonObject
        assertEquals(
            Json.parseToJsonElement("""{"block":{"status":403,"body":"nope","headers":null}}"""),
            json["decision"]
        )
    }

    @Test
    fun `header ops are externally tagged as serde expects`() {
        val json = protocolJson.encodeToJsonElement(
            AgentResponse.serializer(),
            Decision.allow().addRequestHeader("X-Hello", "from-agent").removeResponseHeader("Server").build()
        ).jsonObject
        assertEquals(
            Json.parseToJsonElement("""[{"set":{"name":"X-Hello","value":"from-agent"}}]"""),
            json["request_headers"]
        )
        assertEquals(
            Json.parseToJsonElement("""[{"remove":{"name":"Server"}}]"""),
            json["response_headers"]
        )
    }

    @Test
    fun `decisions and header ops round-trip in serde form`() {
        val serde = """{"version":2,"decision":{"redirect":{"url":"/login","status":302}},""" +
            """"request_headers":[{"add":{"name":"A","value":"b"}}],"response_headers":[],""" +
            """"routing_metadata":{},"audit":{"tags":[],"rule_ids":[],"reason_codes":[],"custom":{}},"needs_more":false}"""
        val response = protocolJson.decodeFromString(AgentResponse.serializer(), serde)
        assertEquals(ProtocolDecision.Redirect("/login", 302), response.decision)
        assertEquals(listOf<HeaderOp>(HeaderOp.Add("A", "b")), response.requestHeaders)
        assertEquals(
            ProtocolDecision.Allow,
            protocolJson.decodeFromString(AgentResponse.serializer(), """{"version":2,"decision":"allow"}""").decision
        )
    }

    @Test
    fun `configure event is the proxy's envelope`() {
        val event = protocolJson.decodeFromString(
            ConfigureEvent.serializer(),
            """{"config":{"nested":{"value":"x"}},"config_version":null,"correlation_id":"zentinel-configure-a-1"}"""
        )
        assertEquals(
            JsonPrimitive("x"),
            event.configObject["nested"]!!.jsonObject["value"]
        )
    }
}
