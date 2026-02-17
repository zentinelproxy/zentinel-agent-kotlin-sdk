package io.raskell.zentinel.agent

import kotlinx.serialization.json.JsonObject

/**
 * Base interface for Zentinel agents.
 *
 * Implement this interface to create a Zentinel agent. The SDK handles
 * protocol details, connection management, and error handling.
 *
 * Example:
 * ```kotlin
 * class MyAgent : Agent {
 *     override val name = "my-agent"
 *
 *     override suspend fun onRequest(request: Request): Decision {
 *         if (request.pathStartsWith("/admin")) {
 *             return Decision.deny().withBody("Access denied")
 *         }
 *         return Decision.allow()
 *     }
 * }
 * ```
 */
interface Agent {
    /**
     * Agent name for logging and identification.
     */
    val name: String

    /**
     * Called when the agent receives configuration from the proxy.
     *
     * Return successfully to accept the configuration, or throw an exception
     * to reject it (which will prevent the proxy from starting).
     *
     * @param config Agent-specific configuration as JSON object
     */
    suspend fun onConfigure(config: JsonObject) {
        // Default: accept any configuration
    }

    /**
     * Called for each incoming request (after headers received).
     *
     * This is the main entry point for request processing.
     * Return a decision to allow, block, or modify the request.
     *
     * @param request The incoming HTTP request
     * @return Decision on how to handle the request
     */
    suspend fun onRequest(request: Request): Decision = Decision.allow()

    /**
     * Called when the request body is available.
     *
     * Only called if body inspection is enabled for this agent.
     * The request includes the accumulated body.
     *
     * @param request The request with body data
     * @return Decision on how to handle the request
     */
    suspend fun onRequestBody(request: Request): Decision = Decision.allow()

    /**
     * Called when response headers are received from upstream.
     *
     * Allows modifying response headers before sending to client.
     *
     * @param request The original request
     * @param response The upstream response
     * @return Decision on how to handle the response
     */
    suspend fun onResponse(request: Request, response: Response): Decision = Decision.allow()

    /**
     * Called when the response body is available.
     *
     * Only called if response body inspection is enabled.
     *
     * @param request The original request
     * @param response The response with body data
     * @return Decision on how to handle the response
     */
    suspend fun onResponseBody(request: Request, response: Response): Decision = Decision.allow()

    /**
     * Called when request processing is complete.
     *
     * Use this for logging, metrics collection, or cleanup.
     * This is called after the response has been sent to the client.
     *
     * @param request The original request
     * @param status The final HTTP status code sent to the client
     * @param durationMs Total request processing time in milliseconds
     */
    suspend fun onRequestComplete(request: Request, status: Int, durationMs: Long) {
        // Default: no-op
    }

    /**
     * Inspect content for guardrail violations.
     *
     * Called when content needs to be analyzed for prompt injection
     * or PII detection. Override to implement custom guardrail logic.
     *
     * @param event The guardrail inspection event containing content and parameters
     * @return GuardrailResponse indicating detection results
     */
    suspend fun onGuardrailInspect(event: GuardrailInspectEvent): GuardrailResponse = GuardrailResponse.clean()
}

/**
 * Interface for agents with typed configuration.
 *
 * Example:
 * ```kotlin
 * data class RateLimitConfig(
 *     val requestsPerMinute: Int = 60,
 *     val enabled: Boolean = true
 * )
 *
 * class RateLimitAgent : ConfigurableAgent<RateLimitConfig> {
 *     override val name = "rate-limiter"
 *     override var config = RateLimitConfig()
 *         private set
 *
 *     override fun parseConfig(json: JsonObject): RateLimitConfig {
 *         return protocolJson.decodeFromJsonElement(json)
 *     }
 *
 *     override suspend fun onConfigApplied(config: RateLimitConfig) {
 *         println("Rate limit set to ${config.requestsPerMinute}/min")
 *     }
 *
 *     override suspend fun onRequest(request: Request): Decision {
 *         if (!config.enabled) return Decision.allow()
 *         // Rate limiting logic...
 *         return Decision.allow()
 *     }
 * }
 * ```
 */
interface ConfigurableAgent<T> : Agent {
    /**
     * Current configuration.
     */
    var config: T

    /**
     * Parse configuration from JSON.
     *
     * @param json Configuration as JSON object
     * @return Parsed configuration
     */
    fun parseConfig(json: JsonObject): T

    /**
     * Called after configuration is successfully applied.
     *
     * Override this to perform additional setup after config changes.
     *
     * @param config The new configuration
     */
    suspend fun onConfigApplied(config: T) {
        // Default: no-op
    }

    override suspend fun onConfigure(config: JsonObject) {
        val parsed = parseConfig(config)
        this.config = parsed
        onConfigApplied(parsed)
    }
}
