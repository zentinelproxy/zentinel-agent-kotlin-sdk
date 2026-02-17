package io.raskell.zentinel.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentTest {

    @Test
    fun `default agent allows all requests`() = runBlocking {
        val agent = object : Agent {
            override val name = "test-agent"
        }

        val request = createTestRequest("/test")
        val decision = agent.onRequest(request)
        val response = decision.build()

        assertEquals(ProtocolDecision.Allow, response.decision)
    }

    @Test
    fun `agent can block requests`() = runBlocking {
        val agent = object : Agent {
            override val name = "blocking-agent"

            override suspend fun onRequest(request: Request): Decision {
                if (request.pathStartsWith("/admin")) {
                    return Decision.deny().withBody("Access denied")
                }
                return Decision.allow()
            }
        }

        val allowedRequest = createTestRequest("/api/users")
        val allowedResponse = agent.onRequest(allowedRequest).build()
        assertEquals(ProtocolDecision.Allow, allowedResponse.decision)

        val blockedRequest = createTestRequest("/admin/settings")
        val blockedResponse = agent.onRequest(blockedRequest).build()
        assertTrue(blockedResponse.decision is ProtocolDecision.Block)
        assertEquals(403, (blockedResponse.decision as ProtocolDecision.Block).status)
    }

    @Test
    fun `agent can modify headers`() = runBlocking {
        val agent = object : Agent {
            override val name = "header-agent"

            override suspend fun onRequest(request: Request): Decision {
                return Decision.allow()
                    .addRequestHeader("X-User-ID", "123")
                    .removeRequestHeader("Cookie")
            }
        }

        val request = createTestRequest("/test")
        val response = agent.onRequest(request).build()

        assertEquals(2, response.requestHeaders.size)
        assertTrue(response.requestHeaders.any { it is HeaderOp.Set && it.name == "X-User-ID" })
        assertTrue(response.requestHeaders.any { it is HeaderOp.Remove && it.name == "Cookie" })
    }

    @Test
    fun `configurable agent handles configuration`() = runBlocking {
        data class TestConfig(
            val enabled: Boolean = true,
            val threshold: Int = 100
        )

        var configApplied = false

        val agent = object : ConfigurableAgent<TestConfig> {
            override val name = "configurable-agent"
            override var config = TestConfig()

            override fun parseConfig(json: JsonObject): TestConfig {
                val enabled = json["enabled"]?.toString()?.toBoolean() ?: true
                val threshold = json["threshold"]?.toString()?.toIntOrNull() ?: 100
                return TestConfig(enabled, threshold)
            }

            override suspend fun onConfigApplied(config: TestConfig) {
                configApplied = true
            }

            override suspend fun onRequest(request: Request): Decision {
                if (!config.enabled) return Decision.allow()
                return Decision.allow().withMetadata("threshold", config.threshold)
            }
        }

        val configJson = buildJsonObject {
            put("enabled", false)
            put("threshold", 50)
        }

        agent.onConfigure(configJson)

        assertTrue(configApplied)
        assertEquals(false, agent.config.enabled)
        assertEquals(50, agent.config.threshold)
    }

    @Test
    fun `agent on_request_complete is called`() = runBlocking {
        var completeCalled = false
        var capturedStatus = 0
        var capturedDuration = 0L

        val agent = object : Agent {
            override val name = "complete-agent"

            override suspend fun onRequestComplete(request: Request, status: Int, durationMs: Long) {
                completeCalled = true
                capturedStatus = status
                capturedDuration = durationMs
            }
        }

        val request = createTestRequest("/test")
        agent.onRequestComplete(request, 200, 42)

        assertTrue(completeCalled)
        assertEquals(200, capturedStatus)
        assertEquals(42L, capturedDuration)
    }

    @Test
    fun `agent can add audit metadata`() = runBlocking {
        val agent = object : Agent {
            override val name = "audit-agent"

            override suspend fun onRequest(request: Request): Decision {
                return Decision.deny()
                    .withTag("blocked")
                    .withRuleId("SEC-001")
                    .withConfidence(0.95f)
                    .withReasonCode("MALICIOUS")
            }
        }

        val request = createTestRequest("/malicious")
        val response = agent.onRequest(request).build()

        assertTrue("blocked" in response.audit.tags)
        assertTrue("SEC-001" in response.audit.ruleIds)
        assertEquals(0.95f, response.audit.confidence)
        assertTrue("MALICIOUS" in response.audit.reasonCodes)
    }

    private fun createTestRequest(
        path: String,
        method: String = "GET",
        headers: Map<String, List<String>> = emptyMap()
    ): Request {
        val event = RequestHeadersEvent(
            metadata = RequestMetadata(
                correlationId = "test-123",
                requestId = "req-456",
                clientIp = "127.0.0.1",
                clientPort = 12345
            ),
            method = method,
            uri = path,
            headers = headers
        )
        return Request.fromEvent(event)
    }
}
