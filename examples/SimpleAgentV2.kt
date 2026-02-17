package io.raskell.zentinel.agent.examples

import io.raskell.zentinel.agent.*
import io.raskell.zentinel.agent.v2.*

/**
 * A simple v2 agent demonstrating basic capabilities.
 *
 * Features:
 * - Path-based access control
 * - Header injection
 * - Health reporting
 * - Graceful shutdown
 */
class SimpleAgentV2 : AgentV2 {
    override val name = "simple-agent-v2"

    private var requestCount = 0L
    private var blockedCount = 0L
    private val startTime = System.currentTimeMillis()

    /**
     * Advertise capabilities - this agent only handles request headers.
     */
    override fun capabilities(): AgentCapabilities = AgentCapabilities.builder()
        .handlesRequestHeaders(true)
        .handlesRequestBody(false)
        .handlesResponseHeaders(true)
        .handlesResponseBody(false)
        .supportsCancellation(true)
        .maxConcurrentRequests(100)
        .build()

    /**
     * Report health status based on internal state.
     */
    override fun healthStatus(): HealthStatus = HealthStatus.Healthy

    /**
     * Report metrics about processed requests.
     */
    override fun metrics(): MetricsReport = MetricsReport.builder()
        .requestsProcessed(requestCount)
        .requestsBlocked(blockedCount)
        .requestsAllowed(requestCount - blockedCount)
        .uptimeSeconds((System.currentTimeMillis() - startTime) / 1000)
        .build()

    override suspend fun onRequest(request: Request): Decision {
        requestCount++

        // Block admin paths without authorization
        if (request.pathStartsWith("/admin") && request.authorization == null) {
            blockedCount++
            return Decision.deny()
                .withBody("Access denied: Authorization required")
                .withTag("unauthorized-admin-access")
                .withRuleId("ADMIN-001")
        }

        // Block suspicious paths
        if (request.path.contains("..") || request.path.contains("etc/passwd")) {
            blockedCount++
            return Decision.deny()
                .withBody("Forbidden")
                .withTag("path-traversal")
                .withRuleId("SEC-001")
                .withConfidence(0.95f)
        }

        // Allow with request ID header
        return Decision.allow()
            .addRequestHeader("X-Processed-By", name)
            .addRequestHeader("X-Client-IP", request.clientIp)
    }

    override suspend fun onResponse(request: Request, response: Response): Decision {
        // Add security headers to HTML responses
        if (response.isHtml()) {
            return Decision.allow()
                .addResponseHeader("X-Frame-Options", "DENY")
                .addResponseHeader("X-Content-Type-Options", "nosniff")
                .removeResponseHeader("Server")
        }

        return Decision.allow()
    }

    override suspend fun onRequestComplete(request: Request, status: Int, durationMs: Long) {
        println("[${name}] ${request.method} ${request.path} -> $status (${durationMs}ms)")
    }

    override suspend fun onShutdown() {
        println("[${name}] Shutting down. Processed $requestCount requests, blocked $blockedCount")
    }

    override suspend fun onDrain(timeoutMs: Long) {
        println("[${name}] Draining with timeout ${timeoutMs}ms")
    }
}

fun main(args: Array<String>) {
    runAgentV2(SimpleAgentV2(), args)
}
