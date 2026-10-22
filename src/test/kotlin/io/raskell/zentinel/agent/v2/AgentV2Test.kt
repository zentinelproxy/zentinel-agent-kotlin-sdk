package io.raskell.zentinel.agent.v2

import io.raskell.zentinel.agent.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentV2Test {

    @Test
    fun `AgentV2 provides default capabilities`() {
        val agent = object : AgentV2 {
            override val name = "test-agent"
        }

        val capabilities = agent.capabilities()
        assertTrue(capabilities.handlesRequestHeaders)
        assertFalse(capabilities.handlesRequestBody)
    }

    @Test
    fun `AgentV2 provides default healthy status`() {
        val agent = object : AgentV2 {
            override val name = "test-agent"
        }

        val status = agent.healthStatus()
        assertTrue(status.isHealthy())
    }

    @Test
    fun `AgentV2 provides default empty metrics`() {
        val agent = object : AgentV2 {
            override val name = "test-agent"
        }

        val metrics = agent.metrics()
        assertEquals(0, metrics.requestsProcessed)
        assertEquals(0, metrics.requestsBlocked)
    }

    @Test
    fun `AgentV2 can override capabilities`() {
        val agent = object : AgentV2 {
            override val name = "full-agent"

            override fun capabilities() = AgentCapabilities.builder()
                .handlesRequestHeaders(true)
                .handlesRequestBody(true)
                .handlesResponseHeaders(true)
                .handlesResponseBody(true)
                .maxConcurrentRequests(50)
                .build()
        }

        val capabilities = agent.capabilities()
        assertTrue(capabilities.handlesRequestHeaders)
        assertTrue(capabilities.handlesRequestBody)
        assertTrue(capabilities.handlesResponseHeaders)
        assertTrue(capabilities.handlesResponseBody)
        assertEquals(50, capabilities.maxConcurrentRequests)
    }

    @Test
    fun `AgentV2 lifecycle methods are called`() = runBlocking {
        var shutdownCalled = false
        var drainCalled = false
        var drainTimeout = 0L
        var streamClosedId: String? = null
        var streamClosedError: Throwable? = null

        val agent = object : AgentV2 {
            override val name = "lifecycle-agent"

            override suspend fun onShutdown() {
                shutdownCalled = true
            }

            override suspend fun onDrain(timeoutMs: Long) {
                drainCalled = true
                drainTimeout = timeoutMs
            }

            override suspend fun onStreamClosed(streamId: String, error: Throwable?) {
                streamClosedId = streamId
                streamClosedError = error
            }
        }

        agent.onShutdown()
        assertTrue(shutdownCalled)

        agent.onDrain(5000)
        assertTrue(drainCalled)
        assertEquals(5000L, drainTimeout)

        val testError = RuntimeException("Test error")
        agent.onStreamClosed("stream-123", testError)
        assertEquals("stream-123", streamClosedId)
        assertEquals(testError, streamClosedError)
    }

    @Test
    fun `AgentV2 request cancellation callbacks`() = runBlocking {
        var cancelledRequestId: String? = null
        var cancelledReason: String? = null
        var allCancelledReason: String? = null

        val agent = object : AgentV2 {
            override val name = "cancellation-agent"

            override suspend fun onRequestCancelled(correlationId: String, reason: String?) {
                cancelledRequestId = correlationId
                cancelledReason = reason
            }

            override suspend fun onAllRequestsCancelled(reason: String?) {
                allCancelledReason = reason
            }
        }

        agent.onRequestCancelled("req-123", "Client disconnected")
        assertEquals("req-123", cancelledRequestId)
        assertEquals("Client disconnected", cancelledReason)

        agent.onAllRequestsCancelled("Agent shutdown")
        assertEquals("Agent shutdown", allCancelledReason)
    }

    @Test
    fun `ConfigurableAgentV2 handles configuration`() = runBlocking {
        data class TestConfig(
            val enabled: Boolean = true,
            val threshold: Int = 100
        )

        var configApplied = false

        val agent = object : ConfigurableAgentV2<TestConfig> {
            override val name = "configurable-v2"
            override var config = TestConfig()

            override fun parseConfig(json: JsonObject): TestConfig {
                val enabled = json["enabled"]?.toString()?.toBoolean() ?: true
                val threshold = json["threshold"]?.toString()?.toIntOrNull() ?: 100
                return TestConfig(enabled, threshold)
            }

            override suspend fun onConfigApplied(config: TestConfig) {
                configApplied = true
            }

            override fun capabilities() = AgentCapabilities.requestOnly()
        }

        val configJson = buildJsonObject {
            put("enabled", false)
            put("threshold", 50)
        }

        agent.onConfigure(configJson)

        assertTrue(configApplied)
        assertFalse(agent.config.enabled)
        assertEquals(50, agent.config.threshold)
    }
}
