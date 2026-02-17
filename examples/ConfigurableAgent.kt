package io.raskell.zentinel.agent.examples

import io.raskell.zentinel.agent.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Configuration for the rate limiting agent.
 */
@Serializable
data class RateLimitConfig(
    @SerialName("requests_per_minute") val requestsPerMinute: Int = 60,
    val enabled: Boolean = true,
    @SerialName("blocked_paths") val blockedPaths: List<String> = emptyList()
)

/**
 * A configurable rate limiting agent.
 */
class RateLimitAgent : ConfigurableAgent<RateLimitConfig> {
    override val name = "rate-limit-agent"
    override var config = RateLimitConfig()

    // Simple in-memory rate limiting (per-IP counters)
    private val counters = ConcurrentHashMap<String, AtomicInteger>()
    private var lastResetTime = System.currentTimeMillis()

    override fun parseConfig(json: JsonObject): RateLimitConfig {
        return protocolJson.decodeFromJsonElement(RateLimitConfig.serializer(), json)
    }

    override suspend fun onConfigApplied(config: RateLimitConfig) {
        println("Rate limit configured: ${config.requestsPerMinute}/min, enabled=${config.enabled}")
        println("Blocked paths: ${config.blockedPaths}")
    }

    override suspend fun onRequest(request: Request): Decision {
        // Skip if disabled
        if (!config.enabled) {
            return Decision.allow()
        }

        // Check blocked paths
        for (blockedPath in config.blockedPaths) {
            if (request.pathStartsWith(blockedPath)) {
                return Decision.deny()
                    .withBody("Path blocked by policy")
                    .withTag("blocked-path")
                    .withRuleId("POLICY-001")
            }
        }

        // Rate limiting
        val clientIp = request.clientIp

        // Reset counters every minute
        val now = System.currentTimeMillis()
        if (now - lastResetTime > 60_000) {
            counters.clear()
            lastResetTime = now
        }

        val counter = counters.computeIfAbsent(clientIp) { AtomicInteger(0) }
        val count = counter.incrementAndGet()

        if (count > config.requestsPerMinute) {
            return Decision.rateLimited()
                .withBody("Rate limit exceeded. Try again later.")
                .withTag("rate-limited")
                .withRuleId("RATE-001")
                .withMetadata("client_ip", clientIp)
                .withMetadata("count", count)
                .withMetadata("limit", config.requestsPerMinute)
        }

        return Decision.allow()
            .addRequestHeader("X-RateLimit-Remaining", (config.requestsPerMinute - count).toString())
            .addRequestHeader("X-RateLimit-Limit", config.requestsPerMinute.toString())
    }
}

fun main(args: Array<String>) {
    runAgent(RateLimitAgent(), args)
}
