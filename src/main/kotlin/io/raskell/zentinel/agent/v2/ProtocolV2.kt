package io.raskell.zentinel.agent.v2

import io.raskell.zentinel.agent.EventType
import kotlinx.serialization.*
import kotlinx.serialization.json.*

/**
 * Agent protocol v2 version constant.
 */
const val PROTOCOL_V2_VERSION: Int = 2

/**
 * Maximum message size for UDS transport (16MB).
 */
const val MAX_MESSAGE_SIZE_UDS: Int = 16 * 1024 * 1024

/**
 * Maximum message size for gRPC transport (10MB).
 */
const val MAX_MESSAGE_SIZE_GRPC: Int = 10 * 1024 * 1024

/**
 * The type byte of a v2 UDS frame.
 *
 * Every frame is `length (4 bytes, big-endian) | type (1 byte) | JSON payload`,
 * where `length` counts the type byte and the payload. This matches
 * `zentinel_agent_protocol::v2::uds` in the proxy.
 */
object MessageType {
    const val HANDSHAKE_REQUEST: Byte = 0x01
    const val HANDSHAKE_RESPONSE: Byte = 0x02
    const val REQUEST_HEADERS: Byte = 0x10
    const val REQUEST_BODY_CHUNK: Byte = 0x11
    const val RESPONSE_HEADERS: Byte = 0x12
    const val RESPONSE_BODY_CHUNK: Byte = 0x13
    const val REQUEST_COMPLETE: Byte = 0x14
    const val WEBSOCKET_FRAME: Byte = 0x15
    const val GUARDRAIL_INSPECT: Byte = 0x16
    const val CONFIGURE: Byte = 0x17
    const val AGENT_RESPONSE: Byte = 0x20
    const val HEALTH_STATUS: Byte = 0x30
    const val METRICS_REPORT: Byte = 0x31
    const val CONFIG_UPDATE_REQUEST: Byte = 0x32
    const val FLOW_CONTROL: Byte = 0x33
    const val CANCEL: Byte = 0x40
    const val PING: Byte = 0x41
    const val PONG: Byte = 0x42

    /** The event a UDS message type carries, or null for control messages. */
    fun eventType(type: Byte): EventType? = when (type) {
        REQUEST_HEADERS -> EventType.REQUEST_HEADERS
        REQUEST_BODY_CHUNK -> EventType.REQUEST_BODY_CHUNK
        RESPONSE_HEADERS -> EventType.RESPONSE_HEADERS
        RESPONSE_BODY_CHUNK -> EventType.RESPONSE_BODY_CHUNK
        REQUEST_COMPLETE -> EventType.REQUEST_COMPLETE
        WEBSOCKET_FRAME -> EventType.WEBSOCKET_FRAME
        GUARDRAIL_INSPECT -> EventType.GUARDRAIL_INSPECT
        CONFIGURE -> EventType.CONFIGURE
        else -> null
    }
}

/** Event numbers as the UDS handshake reports them (`supported_events`). */
fun EventType.udsNumber(): Int = when (this) {
    EventType.CONFIGURE -> 0
    EventType.REQUEST_HEADERS -> 1
    EventType.REQUEST_BODY_CHUNK -> 2
    EventType.RESPONSE_HEADERS -> 3
    EventType.RESPONSE_BODY_CHUNK -> 4
    EventType.REQUEST_COMPLETE -> 5
    EventType.WEBSOCKET_FRAME -> 6
    EventType.GUARDRAIL_INSPECT -> 7
}

/** Event numbers of the gRPC `EventType` enum. */
fun EventType.grpcNumber(): Int = when (this) {
    EventType.REQUEST_HEADERS -> 1
    EventType.REQUEST_BODY_CHUNK -> 2
    EventType.RESPONSE_HEADERS -> 3
    EventType.RESPONSE_BODY_CHUNK -> 4
    EventType.REQUEST_COMPLETE -> 5
    EventType.WEBSOCKET_FRAME -> 6
    EventType.GUARDRAIL_INSPECT -> 7
    EventType.CONFIGURE -> 8
}

/**
 * Handshake request the proxy sends first on every UDS connection
 * (`UdsHandshakeRequest`).
 */
@Serializable
data class HandshakeRequest(
    @SerialName("supported_versions") val supportedVersions: List<Int> = listOf(PROTOCOL_V2_VERSION),
    @SerialName("proxy_id") val proxyId: String = "",
    @SerialName("proxy_version") val proxyVersion: String = "",
    val config: JsonElement? = null,
    @SerialName("supported_encodings") val supportedEncodings: List<String> = emptyList()
)

/**
 * Handshake response (`UdsHandshakeResponse`). Every field is required by
 * the proxy, `capabilities` included, even when the handshake is refused.
 */
@Serializable
data class HandshakeResponse(
    @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_V2_VERSION,
    val capabilities: UdsCapabilities,
    val success: Boolean,
    val error: String? = null,
    val encoding: String = "json"
)

/** `capabilities` of the UDS handshake response (`UdsCapabilities`). */
@Serializable
data class UdsCapabilities(
    @SerialName("agent_id") val agentId: String,
    val name: String,
    val version: String,
    @SerialName("supported_events") val supportedEvents: List<Int>,
    val features: AgentFeatures,
    val limits: UdsLimits
)

/** `limits` of the UDS handshake response (`UdsLimits`). */
@Serializable
data class UdsLimits(
    @SerialName("max_body_size") val maxBodySize: Long,
    @SerialName("max_concurrency") val maxConcurrency: Int,
    @SerialName("preferred_chunk_size") val preferredChunkSize: Long
)

/** Features the agent supports (all nine are sent in the handshake). */
@Serializable
data class AgentFeatures(
    @SerialName("streaming_body") val streamingBody: Boolean = false,
    val websocket: Boolean = false,
    val guardrails: Boolean = false,
    @SerialName("config_push") val configPush: Boolean = false,
    @SerialName("metrics_export") val metricsExport: Boolean = false,
    @SerialName("concurrent_requests") val concurrentRequests: Int = 10,
    val cancellation: Boolean = false,
    @SerialName("flow_control") val flowControl: Boolean = false,
    @SerialName("health_reporting") val healthReporting: Boolean = false
)

/** Resource limits the agent advertises. */
data class AgentLimits(
    val maxBodySize: Long = 10L * 1024 * 1024,
    val maxConcurrency: Int = 100,
    val preferredChunkSize: Long = 64L * 1024
)

/**
 * Agent capabilities advertised during the handshake.
 *
 * `agentId` and `name` default to the agent's [io.raskell.zentinel.agent.Agent.name].
 * `supportedEvents` is informational: which events reach the agent is decided
 * by the `events` list of the agent's block in the proxy configuration.
 */
data class AgentCapabilities(
    val agentId: String? = null,
    val name: String? = null,
    val version: String = "0.0.0",
    val supportedEvents: List<EventType> = DEFAULT_EVENTS,
    val features: AgentFeatures = AgentFeatures(),
    val limits: AgentLimits = AgentLimits(),
    val healthReportIntervalMs: Int = 30_000
) {
    val handlesRequestHeaders: Boolean get() = EventType.REQUEST_HEADERS in supportedEvents
    val handlesRequestBody: Boolean get() = EventType.REQUEST_BODY_CHUNK in supportedEvents
    val handlesResponseHeaders: Boolean get() = EventType.RESPONSE_HEADERS in supportedEvents
    val handlesResponseBody: Boolean get() = EventType.RESPONSE_BODY_CHUNK in supportedEvents
    val supportsStreaming: Boolean get() = features.streamingBody
    val supportsCancellation: Boolean get() = features.cancellation
    val maxConcurrentRequests: Int get() = features.concurrentRequests

    /** The UDS handshake form, with [defaultName] filling a missing id or name. */
    fun toUds(defaultName: String): UdsCapabilities = UdsCapabilities(
        agentId = agentId ?: defaultName,
        name = name ?: defaultName,
        version = version,
        supportedEvents = supportedEvents.map { it.udsNumber() }.distinct(),
        features = features,
        limits = UdsLimits(limits.maxBodySize, limits.maxConcurrency, limits.preferredChunkSize)
    )

    /**
     * Builder for creating AgentCapabilities with a fluent API.
     */
    class Builder {
        private var agentId: String? = null
        private var name: String? = null
        private var version: String = "0.0.0"
        private val events = linkedSetOf(EventType.REQUEST_HEADERS)
        private var features = AgentFeatures()
        private var limits = AgentLimits()
        private var healthReportIntervalMs = 30_000

        fun agentId(value: String) = apply { agentId = value }
        fun name(value: String) = apply { name = value }
        fun version(value: String) = apply { version = value }
        fun event(type: EventType, enabled: Boolean = true) = apply { if (enabled) events.add(type) else events.remove(type) }
        fun handlesRequestHeaders(value: Boolean) = event(EventType.REQUEST_HEADERS, value)
        fun handlesRequestBody(value: Boolean) = event(EventType.REQUEST_BODY_CHUNK, value)
        fun handlesResponseHeaders(value: Boolean) = event(EventType.RESPONSE_HEADERS, value)
        fun handlesResponseBody(value: Boolean) = event(EventType.RESPONSE_BODY_CHUNK, value)
        fun handlesRequestComplete(value: Boolean) = event(EventType.REQUEST_COMPLETE, value)
        fun handlesGuardrails(value: Boolean) = apply {
            event(EventType.GUARDRAIL_INSPECT, value)
            features = features.copy(guardrails = value)
        }
        fun supportsStreaming(value: Boolean) = apply { features = features.copy(streamingBody = value) }
        fun supportsCancellation(value: Boolean) = apply { features = features.copy(cancellation = value) }
        fun maxConcurrentRequests(value: Int?) = apply {
            features = features.copy(concurrentRequests = value ?: AgentFeatures().concurrentRequests)
        }
        fun features(value: AgentFeatures) = apply { features = value }
        fun limits(value: AgentLimits) = apply { limits = value }
        fun maxBodySize(value: Long) = apply { limits = limits.copy(maxBodySize = value) }
        fun healthReportIntervalMs(value: Int) = apply { healthReportIntervalMs = value }

        fun build() = AgentCapabilities(
            agentId = agentId,
            name = name,
            version = version,
            supportedEvents = events.toList(),
            features = features,
            limits = limits,
            healthReportIntervalMs = healthReportIntervalMs
        )
    }

    companion object {
        /** The events a default agent declares. */
        val DEFAULT_EVENTS = listOf(EventType.REQUEST_HEADERS)

        /** Create a builder for AgentCapabilities. */
        fun builder() = Builder()

        /** Default capabilities for a simple request-only agent. */
        fun requestOnly() = AgentCapabilities()

        /** Capabilities for a full inspection agent. */
        fun fullInspection() = AgentCapabilities(
            supportedEvents = listOf(
                EventType.REQUEST_HEADERS,
                EventType.REQUEST_BODY_CHUNK,
                EventType.RESPONSE_HEADERS,
                EventType.RESPONSE_BODY_CHUNK,
                EventType.REQUEST_COMPLETE
            ),
            features = AgentFeatures(streamingBody = true, cancellation = true)
        )
    }
}

/**
 * Health status of an agent.
 */
@Serializable
sealed class HealthStatus {
    /**
     * Agent is healthy and ready to process requests.
     */
    @Serializable
    @SerialName("healthy")
    data object Healthy : HealthStatus()

    /**
     * Agent is operational but degraded (e.g., high load, partial failures).
     */
    @Serializable
    @SerialName("degraded")
    data class Degraded(
        val reason: String,
        val load: Float? = null
    ) : HealthStatus()

    /**
     * Agent is unhealthy and should not receive requests.
     */
    @Serializable
    @SerialName("unhealthy")
    data class Unhealthy(
        val reason: String,
        @SerialName("retry_after_ms") val retryAfterMs: Long? = null
    ) : HealthStatus()

    fun isHealthy(): Boolean = this is Healthy
    fun isDegraded(): Boolean = this is Degraded
    fun isUnhealthy(): Boolean = this is Unhealthy
}

/**
 * Metrics report from an agent.
 */
@Serializable
data class MetricsReport(
    @SerialName("requests_processed") val requestsProcessed: Long = 0,
    @SerialName("requests_blocked") val requestsBlocked: Long = 0,
    @SerialName("requests_allowed") val requestsAllowed: Long = 0,
    @SerialName("average_latency_ms") val averageLatencyMs: Double = 0.0,
    @SerialName("p99_latency_ms") val p99LatencyMs: Double = 0.0,
    @SerialName("active_requests") val activeRequests: Int = 0,
    @SerialName("error_count") val errorCount: Long = 0,
    @SerialName("uptime_seconds") val uptimeSeconds: Long = 0,
    val custom: Map<String, JsonElement> = emptyMap()
) {
    /**
     * Builder for creating MetricsReport with a fluent API.
     */
    class Builder {
        private var requestsProcessed: Long = 0
        private var requestsBlocked: Long = 0
        private var requestsAllowed: Long = 0
        private var averageLatencyMs: Double = 0.0
        private var p99LatencyMs: Double = 0.0
        private var activeRequests: Int = 0
        private var errorCount: Long = 0
        private var uptimeSeconds: Long = 0
        private val custom: MutableMap<String, JsonElement> = mutableMapOf()

        fun requestsProcessed(value: Long) = apply { requestsProcessed = value }
        fun requestsBlocked(value: Long) = apply { requestsBlocked = value }
        fun requestsAllowed(value: Long) = apply { requestsAllowed = value }
        fun averageLatencyMs(value: Double) = apply { averageLatencyMs = value }
        fun p99LatencyMs(value: Double) = apply { p99LatencyMs = value }
        fun activeRequests(value: Int) = apply { activeRequests = value }
        fun errorCount(value: Long) = apply { errorCount = value }
        fun uptimeSeconds(value: Long) = apply { uptimeSeconds = value }
        fun customMetric(key: String, value: JsonElement) = apply { custom[key] = value }
        fun customMetric(key: String, value: Number) = apply { custom[key] = JsonPrimitive(value) }
        fun customMetric(key: String, value: String) = apply { custom[key] = JsonPrimitive(value) }
        fun customMetric(key: String, value: Boolean) = apply { custom[key] = JsonPrimitive(value) }

        fun build() = MetricsReport(
            requestsProcessed = requestsProcessed,
            requestsBlocked = requestsBlocked,
            requestsAllowed = requestsAllowed,
            averageLatencyMs = averageLatencyMs,
            p99LatencyMs = p99LatencyMs,
            activeRequests = activeRequests,
            errorCount = errorCount,
            uptimeSeconds = uptimeSeconds,
            custom = custom.toMap()
        )
    }

    companion object {
        fun builder() = Builder()
    }
}

/**
 * JSON serializer for v2 protocol.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
val protocolV2Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}
