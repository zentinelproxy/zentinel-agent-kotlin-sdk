package io.raskell.zentinel.agent.v2

import io.raskell.zentinel.agent.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentHandlerV2Test {

    private fun headersEvent(cid: String, uri: String = "/") = buildJsonObject {
        putJsonObject("metadata") {
            put("correlation_id", cid)
            put("request_id", "r-$cid")
            put("client_ip", "127.0.0.1")
            put("client_port", 4242)
            put("protocol", "HTTP/1.1")
            put("timestamp", "2026-10-22T00:00:00Z")
            put("traceparent", "00-abc-def-01")
        }
        put("method", "GET")
        put("uri", uri)
        putJsonObject("headers") { putJsonArray("host") { add("example.com") } }
    }

    private fun bodyChunk(cid: String, data: String, last: Boolean, index: Int) = buildJsonObject {
        put("correlation_id", cid)
        put("data", Base64.getEncoder().encodeToString(data.toByteArray()))
        put("is_last", last)
        put("chunk_index", index)
        put("bytes_received", data.length)
    }

    @Test
    fun `handler returns capabilities from agent`() {
        val agent = object : AgentV2 {
            override val name = "caps-agent"
            override fun capabilities() = AgentCapabilities.builder().handlesRequestBody(true).build()
        }
        assertTrue(AgentHandlerV2(agent).capabilities().handlesRequestBody)
    }

    @Test
    fun `plain agents get default capabilities named after them`() {
        val agent = object : Agent {
            override val name = "plain"
        }
        val response = AgentHandlerV2(agent).handleHandshake(HandshakeRequest(listOf(2), "zentinel", "0.6.50"))
        assertTrue(response.success)
        assertEquals("plain", response.capabilities.agentId)
        assertEquals("plain", response.capabilities.name)
    }

    @Test
    fun `handler returns health status and metrics from agent`() {
        val agent = object : AgentV2 {
            override val name = "health-agent"
            override fun healthStatus() = HealthStatus.Degraded("High load", 0.9f)
            override fun metrics() = MetricsReport(requestsProcessed = 7)
        }
        val handler = AgentHandlerV2(agent)
        assertTrue(handler.healthStatus().isDegraded())
        assertEquals(7, handler.metrics().requestsProcessed)
    }

    @Test
    fun `handshake is refused when v2 is not offered`() {
        val agent = object : AgentV2 {
            override val name = "a"
        }
        val response = AgentHandlerV2(agent).handleHandshake(HandshakeRequest(listOf(1), "zentinel", "0.1"))
        assertFalse(response.success)
        assertNotNull(response.error)
    }

    @Test
    fun `request headers reach onRequest and header ops come back`() = runBlocking {
        var seen: Request? = null
        val agent = object : AgentV2 {
            override val name = "hdr"
            override suspend fun onRequest(request: Request): Decision {
                seen = request
                return Decision.allow().addRequestHeader("X-Hello", "from-agent")
            }
        }
        val response = AgentHandlerV2(agent).dispatch(EventType.REQUEST_HEADERS, headersEvent("c1", "/x?y=1"))
        assertEquals(ProtocolDecision.Allow, response.decision)
        assertEquals(listOf<HeaderOp>(HeaderOp.Set("X-Hello", "from-agent")), response.requestHeaders)
        assertEquals("/x", seen!!.path)
        assertEquals("1", seen!!.query("y"))
        assertEquals("example.com", seen!!.header("host"))
        assertEquals("c1", seen!!.correlationId)
    }

    @Test
    fun `handler blocks requests`() = runBlocking {
        val agent = object : AgentV2 {
            override val name = "blk"
            override suspend fun onRequest(request: Request) =
                if (request.pathStartsWith("/admin")) Decision.deny().withBody("no") else Decision.allow()
        }
        val response = AgentHandlerV2(agent).dispatch(EventType.REQUEST_HEADERS, headersEvent("c2", "/admin"))
        assertEquals(ProtocolDecision.Block(403, "no", null), response.decision)
    }

    @Test
    fun `body chunks are collected until the last one`() = runBlocking {
        var body: String? = null
        val agent = object : AgentV2 {
            override val name = "body"
            override suspend fun onRequestBody(request: Request): Decision {
                body = request.bodyString()
                return if ("evil" in body!!) Decision.block(403).withBody("body-seen:$body") else Decision.allow()
            }
        }
        val handler = AgentHandlerV2(agent)
        handler.dispatch(EventType.REQUEST_HEADERS, headersEvent("c3"))
        val first = handler.dispatch(EventType.REQUEST_BODY_CHUNK, bodyChunk("c3", "hello ", false, 0))
        assertTrue(first.needsMore)
        assertEquals(null, body)
        val last = handler.dispatch(EventType.REQUEST_BODY_CHUNK, bodyChunk("c3", "evil world", true, 1))
        assertEquals("hello evil world", body)
        assertEquals(ProtocolDecision.Block(403, "body-seen:hello evil world", null), last.decision)
    }

    @Test
    fun `events for requests the agent did not see still reach it`() = runBlocking {
        var status = 0
        val agent = object : AgentV2 {
            override val name = "resp"
            override suspend fun onResponse(request: Request, response: Response): Decision {
                status = response.statusCode
                return Decision.allow().addResponseHeader("X-R", "1")
            }
        }
        val response = AgentHandlerV2(agent).dispatch(
            EventType.RESPONSE_HEADERS,
            buildJsonObject {
                put("correlation_id", "unknown")
                put("status", 201)
                putJsonObject("headers") {}
            }
        )
        assertEquals(201, status)
        assertEquals(listOf<HeaderOp>(HeaderOp.Set("X-R", "1")), response.responseHeaders)
    }

    @Test
    fun `configure reaches onConfigure and a rejection blocks`() = runBlocking {
        var received: JsonObject? = null
        val agent = object : AgentV2 {
            override val name = "cfg"
            override suspend fun onConfigure(config: JsonObject) {
                if (config["bad"] != null) throw IllegalArgumentException("bad config")
                received = config
            }
        }
        val handler = AgentHandlerV2(agent)
        val ok = handler.dispatch(
            EventType.CONFIGURE,
            Json.parseToJsonElement("""{"config":{"nested":{"value":"v"}},"config_version":null,"correlation_id":"x"}""").jsonObject
        )
        assertEquals(ProtocolDecision.Allow, ok.decision)
        assertEquals(JsonPrimitive("v"), received!!["nested"]!!.jsonObject["value"])

        val rejected = handler.dispatch(
            EventType.CONFIGURE,
            Json.parseToJsonElement("""{"config":{"bad":true}}""").jsonObject
        )
        assertTrue(rejected.decision is ProtocolDecision.Block)
    }

    @Test
    fun `cancellation reaches the agent by correlation id`() = runBlocking {
        var cancelled: String? = null
        var all: String? = null
        val agent = object : AgentV2 {
            override val name = "cancel"
            override suspend fun onRequestCancelled(correlationId: String, reason: String?) {
                cancelled = correlationId
            }
            override suspend fun onAllRequestsCancelled(reason: String?) {
                all = reason
            }
        }
        val handler = AgentHandlerV2(agent)
        handler.dispatch(EventType.REQUEST_HEADERS, headersEvent("c4"))
        handler.dispatch(EventType.REQUEST_HEADERS, headersEvent("c5"))
        assertEquals(2, handler.activeRequestCount())
        handler.handleCancel("c4", "client gone")
        assertEquals("c4", cancelled)
        assertEquals(1, handler.activeRequestCount())
        handler.shutdown()
        assertEquals("Agent shutdown", all)
        assertEquals(0, handler.activeRequestCount())
    }

    @Test
    fun `draining blocks new requests with 503`() = runBlocking {
        val agent = object : AgentV2 {
            override val name = "drain"
        }
        val handler = AgentHandlerV2(agent)
        handler.startDrain(1000)
        val response = handler.dispatch(EventType.REQUEST_HEADERS, headersEvent("c6"))
        assertEquals(503, (response.decision as ProtocolDecision.Block).status)
    }

    @Test
    fun `request complete forgets the request and calls the agent`() = runBlocking {
        var completed = 0
        val agent = object : AgentV2 {
            override val name = "done"
            override suspend fun onRequestComplete(request: Request, status: Int, durationMs: Long) {
                completed = status
            }
        }
        val handler = AgentHandlerV2(agent)
        handler.dispatch(EventType.REQUEST_HEADERS, headersEvent("c7"))
        handler.dispatch(
            EventType.REQUEST_COMPLETE,
            buildJsonObject {
                put("correlation_id", "c7")
                put("status", 204)
                put("duration_ms", 3)
                put("request_body_size", 0)
                put("response_body_size", 0)
                put("upstream_attempts", 1)
            }
        )
        assertEquals(204, completed)
        assertEquals(0, handler.activeRequestCount())
    }

    @Test
    fun `audit metadata and correlation id are carried`() = runBlocking {
        val agent = object : AgentV2 {
            override val name = "audit"
            override suspend fun onRequest(request: Request) =
                Decision.deny().withTag("t").withRuleId("R1").withConfidence(0.5f)
        }
        val payload = headersEvent("c8")
        val response = AgentHandlerV2(agent).dispatch(EventType.REQUEST_HEADERS, payload)
            .withCorrelationId(correlationIdOf(payload))
        assertEquals(listOf("t"), response.audit.tags)
        assertEquals(listOf("R1"), response.audit.ruleIds)
        assertEquals(JsonPrimitive("c8"), response.audit.custom["correlation_id"])
    }
}
