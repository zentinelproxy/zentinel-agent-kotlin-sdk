package io.raskell.zentinel.agent.examples

import io.raskell.zentinel.agent.*
import io.raskell.zentinel.agent.v2.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A full inspection v2 agent with body processing.
 *
 * Features:
 * - Request and response body inspection
 * - SQL injection detection
 * - XSS detection
 * - Sensitive data filtering
 * - Configurable rules
 * - Detailed metrics
 */
class FullInspectionAgentV2 : ConfigurableAgentV2<FullInspectionAgentV2.Config> {
    override val name = "full-inspection-v2"

    @Serializable
    data class Config(
        val sqlInjectionEnabled: Boolean = true,
        val xssEnabled: Boolean = true,
        val filterSensitiveData: Boolean = true,
        val maxBodySize: Long = 10 * 1024 * 1024 // 10MB
    )

    override var config = Config()

    // Metrics
    private val requestsProcessed = AtomicLong(0)
    private val bodiesInspected = AtomicLong(0)
    private val sqlInjectionBlocked = AtomicLong(0)
    private val xssBlocked = AtomicLong(0)
    private val activeRequests = AtomicInteger(0)
    private val startTime = System.currentTimeMillis()

    // Detection patterns
    private val sqlInjectionPatterns = listOf(
        Regex("(?i)union\\s+select"),
        Regex("(?i)or\\s+1\\s*=\\s*1"),
        Regex("(?i);\\s*drop\\s+table"),
        Regex("(?i)'\\s*or\\s+'"),
        Regex("(?i)--\\s*$")
    )

    private val xssPatterns = listOf(
        Regex("(?i)<script[^>]*>"),
        Regex("(?i)javascript:"),
        Regex("(?i)on\\w+\\s*="),
        Regex("(?i)<iframe[^>]*>")
    )

    override fun parseConfig(json: JsonObject): Config {
        return protocolV2Json.decodeFromJsonElement(Config.serializer(), json)
    }

    override suspend fun onConfigApplied(config: Config) {
        println("[${name}] Configuration applied: SQL=$${config.sqlInjectionEnabled}, XSS=${config.xssEnabled}")
    }

    override fun capabilities(): AgentCapabilities = AgentCapabilities.builder()
        .handlesRequestHeaders(true)
        .handlesRequestBody(true)
        .handlesResponseHeaders(true)
        .handlesResponseBody(true)
        .supportsStreaming(true)
        .supportsCancellation(true)
        .maxConcurrentRequests(50)
        .addFeatures("sql-injection-detection", "xss-detection", "sensitive-data-filter")
        .build()

    override fun healthStatus(): HealthStatus {
        val active = activeRequests.get()
        return when {
            active > 40 -> HealthStatus.Degraded(
                reason = "High load",
                load = active / 50f
            )
            else -> HealthStatus.Healthy
        }
    }

    override fun metrics(): MetricsReport = MetricsReport.builder()
        .requestsProcessed(requestsProcessed.get())
        .requestsBlocked(sqlInjectionBlocked.get() + xssBlocked.get())
        .requestsAllowed(requestsProcessed.get() - sqlInjectionBlocked.get() - xssBlocked.get())
        .activeRequests(activeRequests.get())
        .uptimeSeconds((System.currentTimeMillis() - startTime) / 1000)
        .customMetric("bodies_inspected", bodiesInspected.get())
        .customMetric("sql_injection_blocked", sqlInjectionBlocked.get())
        .customMetric("xss_blocked", xssBlocked.get())
        .build()

    override suspend fun onRequest(request: Request): Decision {
        requestsProcessed.incrementAndGet()
        activeRequests.incrementAndGet()

        try {
            // Check query string for injection
            val queryString = request.queryString ?: ""

            if (config.sqlInjectionEnabled) {
                val sqliMatch = checkSqlInjection(queryString)
                if (sqliMatch != null) {
                    sqlInjectionBlocked.incrementAndGet()
                    return Decision.deny()
                        .withBody("Potential SQL injection detected")
                        .withTag("sql-injection")
                        .withRuleId("SQLI-001")
                        .withConfidence(0.9f)
                        .withMetadata("pattern", sqliMatch)
                }
            }

            // For POST/PUT requests, request body inspection
            if (request.method in listOf("POST", "PUT", "PATCH")) {
                return Decision.allow().needsMoreData()
            }

            return Decision.allow()
        } finally {
            activeRequests.decrementAndGet()
        }
    }

    override suspend fun onRequestBody(request: Request): Decision {
        bodiesInspected.incrementAndGet()
        val body = request.bodyString() ?: return Decision.allow()

        // Check body size
        if (body.length > config.maxBodySize) {
            return Decision.badRequest()
                .withBody("Request body too large")
                .withTag("body-too-large")
        }

        // SQL injection check
        if (config.sqlInjectionEnabled) {
            val sqliMatch = checkSqlInjection(body)
            if (sqliMatch != null) {
                sqlInjectionBlocked.incrementAndGet()
                return Decision.deny()
                    .withBody("Potential SQL injection detected in request body")
                    .withTag("sql-injection")
                    .withRuleId("SQLI-002")
                    .withConfidence(0.95f)
                    .withMetadata("pattern", sqliMatch)
            }
        }

        // XSS check
        if (config.xssEnabled) {
            val xssMatch = checkXss(body)
            if (xssMatch != null) {
                xssBlocked.incrementAndGet()
                return Decision.deny()
                    .withBody("Potential XSS detected in request body")
                    .withTag("xss")
                    .withRuleId("XSS-001")
                    .withConfidence(0.9f)
                    .withMetadata("pattern", xssMatch)
            }
        }

        return Decision.allow()
    }

    override suspend fun onResponse(request: Request, response: Response): Decision {
        // Filter error responses for sensitive data
        if (response.isServerError() && config.filterSensitiveData) {
            return Decision.allow().needsMoreData()
        }
        return Decision.allow()
    }

    override suspend fun onResponseBody(request: Request, response: Response): Decision {
        val body = response.bodyString() ?: return Decision.allow()

        if (response.isServerError() && config.filterSensitiveData) {
            val sensitivePatterns = listOf(
                "stack trace" to Regex("(?i)at\\s+[\\w.]+\\([^)]+:\\d+\\)"),
                "sql error" to Regex("(?i)(sql|mysql|postgresql|oracle).*error"),
                "file path" to Regex("(?i)/[a-z]+/[a-z]+/[^\\s]+\\.(java|kt|py|rb|php)")
            )

            for ((name, pattern) in sensitivePatterns) {
                if (pattern.containsMatchIn(body)) {
                    return Decision.allow()
                        .withResponseBodyMutation(
                            BodyMutation.replace(0, "Internal server error")
                        )
                        .withTag("sensitive-data-filtered")
                        .withMetadata("filtered_type", name)
                }
            }
        }

        return Decision.allow()
    }

    override suspend fun onRequestCancelled(requestId: Long, reason: String?) {
        activeRequests.decrementAndGet()
        println("[${name}] Request $requestId cancelled: $reason")
    }

    override suspend fun onAllRequestsCancelled(reason: String?) {
        activeRequests.set(0)
        println("[${name}] All requests cancelled: $reason")
    }

    override suspend fun onShutdown() {
        println("[${name}] Shutting down. Stats:")
        println("  - Requests processed: ${requestsProcessed.get()}")
        println("  - Bodies inspected: ${bodiesInspected.get()}")
        println("  - SQL injections blocked: ${sqlInjectionBlocked.get()}")
        println("  - XSS blocked: ${xssBlocked.get()}")
    }

    override suspend fun onDrain(timeoutMs: Long) {
        println("[${name}] Draining, active requests: ${activeRequests.get()}")
    }

    override suspend fun onStreamClosed(streamId: String, error: Throwable?) {
        if (error != null) {
            println("[${name}] Stream $streamId closed with error: ${error.message}")
        }
    }

    private fun checkSqlInjection(input: String): String? {
        for (pattern in sqlInjectionPatterns) {
            if (pattern.containsMatchIn(input)) {
                return pattern.pattern
            }
        }
        return null
    }

    private fun checkXss(input: String): String? {
        for (pattern in xssPatterns) {
            if (pattern.containsMatchIn(input)) {
                return pattern.pattern
            }
        }
        return null
    }
}

fun main(args: Array<String>) {
    runAgentV2(FullInspectionAgentV2(), args)
}
