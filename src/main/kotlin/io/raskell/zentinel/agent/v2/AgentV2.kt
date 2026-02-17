package io.raskell.zentinel.agent.v2

import io.raskell.zentinel.agent.Agent
import io.raskell.zentinel.agent.Decision
import io.raskell.zentinel.agent.Request
import io.raskell.zentinel.agent.Response
import kotlinx.serialization.json.JsonObject

/**
 * Extended agent interface for v2 protocol support.
 *
 * V2 agents support enhanced features including:
 * - Capability advertisement
 * - Health reporting
 * - Metrics collection
 * - Request cancellation
 * - Lifecycle events (shutdown, drain, stream close)
 *
 * Example:
 * ```kotlin
 * class MyAgentV2 : AgentV2 {
 *     override val name = "my-agent-v2"
 *
 *     override fun capabilities(): AgentCapabilities = AgentCapabilities.builder()
 *         .handlesRequestHeaders(true)
 *         .handlesRequestBody(true)
 *         .supportsCancellation(true)
 *         .maxConcurrentRequests(100)
 *         .build()
 *
 *     override suspend fun onRequest(request: Request): Decision {
 *         // Process request
 *         return Decision.allow()
 *     }
 *
 *     override suspend fun onShutdown() {
 *         // Cleanup resources
 *     }
 * }
 * ```
 */
interface AgentV2 : Agent {

    /**
     * Returns the capabilities of this agent.
     *
     * Called during handshake to advertise what the agent can process.
     * Override to customize capabilities beyond the defaults.
     *
     * @return AgentCapabilities describing what this agent handles
     */
    fun capabilities(): AgentCapabilities = AgentCapabilities.requestOnly()

    /**
     * Returns the current health status of the agent.
     *
     * Called periodically by the proxy for health checking.
     * Override to provide custom health status based on agent state.
     *
     * @return HealthStatus indicating current health
     */
    fun healthStatus(): HealthStatus = HealthStatus.Healthy

    /**
     * Returns metrics about the agent's operation.
     *
     * Called periodically for metrics collection.
     * Override to provide custom metrics about agent performance.
     *
     * @return MetricsReport with current metrics
     */
    fun metrics(): MetricsReport = MetricsReport()

    /**
     * Called when a specific request is cancelled.
     *
     * The proxy may cancel a request due to client disconnect, timeout,
     * or explicit cancellation. Override to clean up any resources
     * associated with the request.
     *
     * @param requestId The ID of the cancelled request
     * @param reason Optional reason for cancellation
     */
    suspend fun onRequestCancelled(requestId: Long, reason: String?) {
        // Default: no-op
    }

    /**
     * Called when all requests are cancelled.
     *
     * This typically happens during shutdown or when the agent
     * is being removed from the pool.
     *
     * @param reason Optional reason for cancellation
     */
    suspend fun onAllRequestsCancelled(reason: String?) {
        // Default: no-op
    }

    /**
     * Called when the agent is being shut down gracefully.
     *
     * Use this to clean up resources, close connections, save state, etc.
     * This is called before the agent process exits.
     */
    suspend fun onShutdown() {
        // Default: no-op
    }

    /**
     * Called when the agent should drain - stop accepting new requests
     * but complete existing ones.
     *
     * This is used for graceful shutdown and rolling updates.
     * After drain completes, onShutdown will be called.
     *
     * @param timeoutMs Maximum time to complete draining
     */
    suspend fun onDrain(timeoutMs: Long) {
        // Default: no-op
    }

    /**
     * Called when a bidirectional stream is closed.
     *
     * For multiplexed connections, this indicates the stream for a
     * particular connection has ended. Use this to clean up any
     * stream-specific state.
     *
     * @param streamId The ID of the closed stream
     * @param error Optional error that caused the closure
     */
    suspend fun onStreamClosed(streamId: String, error: Throwable?) {
        // Default: no-op
    }
}

/**
 * Extended configurable agent interface for v2 protocol support.
 *
 * Combines ConfigurableAgent functionality with v2 capabilities.
 *
 * Example:
 * ```kotlin
 * data class MyConfig(
 *     val threshold: Int = 100,
 *     val enabled: Boolean = true
 * )
 *
 * class MyConfigurableAgentV2 : ConfigurableAgentV2<MyConfig> {
 *     override val name = "configurable-v2"
 *     override var config = MyConfig()
 *
 *     override fun parseConfig(json: JsonObject): MyConfig {
 *         return protocolV2Json.decodeFromJsonElement(json)
 *     }
 *
 *     override fun capabilities() = AgentCapabilities.builder()
 *         .handlesRequestHeaders(true)
 *         .build()
 *
 *     override suspend fun onRequest(request: Request): Decision {
 *         if (!config.enabled) return Decision.allow()
 *         // Process with config.threshold
 *         return Decision.allow()
 *     }
 * }
 * ```
 */
interface ConfigurableAgentV2<T> : AgentV2 {
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

/**
 * V2-specific request wrapper with request ID for multiplexing.
 */
data class RequestV2(
    /** The underlying v1 request */
    val request: Request,
    /** Unique request ID for multiplexing */
    val requestId: Long
) {
    // Delegate common properties to underlying request
    val correlationId: String get() = request.correlationId
    val method: String get() = request.method
    val path: String get() = request.path
    val uri: String get() = request.uri
    val headers: Map<String, List<String>> get() = request.headers
    val clientIp: String get() = request.clientIp

    fun header(name: String): String? = request.header(name)
    fun hasHeader(name: String): Boolean = request.hasHeader(name)
    fun pathStartsWith(prefix: String): Boolean = request.pathStartsWith(prefix)
    fun body(): ByteArray? = request.body()
    fun bodyString(): String? = request.bodyString()
}

/**
 * V2-specific response wrapper with request ID for multiplexing.
 */
data class ResponseV2(
    /** The underlying v1 response */
    val response: Response,
    /** Unique request ID for multiplexing */
    val requestId: Long
) {
    // Delegate common properties to underlying response
    val correlationId: String get() = response.correlationId
    val statusCode: Int get() = response.statusCode
    val headers: Map<String, List<String>> get() = response.headers

    fun header(name: String): String? = response.header(name)
    fun hasHeader(name: String): Boolean = response.hasHeader(name)
    fun isSuccess(): Boolean = response.isSuccess()
    fun isError(): Boolean = response.isError()
    fun body(): ByteArray? = response.body()
    fun bodyString(): String? = response.bodyString()
}
