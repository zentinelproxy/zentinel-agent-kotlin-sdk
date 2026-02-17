package io.raskell.zentinel.agent.v2

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
 * V2 message types for UDS binary protocol.
 */
object MessageType {
    const val HANDSHAKE_REQUEST: Byte = 0x01
    const val HANDSHAKE_RESPONSE: Byte = 0x02
    const val REQUEST_HEADERS: Byte = 0x10
    const val REQUEST_BODY_CHUNK: Byte = 0x11
    const val RESPONSE_HEADERS: Byte = 0x12
    const val RESPONSE_BODY_CHUNK: Byte = 0x13
    const val DECISION: Byte = 0x20
    const val BODY_MUTATION: Byte = 0x21
    const val CANCEL_REQUEST: Byte = 0x30
    const val CANCEL_ALL: Byte = 0x31
    const val PING: Byte = 0xF0.toByte()
    const val PONG: Byte = 0xF1.toByte()
}

/**
 * Handshake request sent by proxy to agent.
 */
@Serializable
data class HandshakeRequest(
    @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_V2_VERSION,
    @SerialName("client_name") val clientName: String,
    @SerialName("supported_features") val supportedFeatures: List<String> = emptyList(),
    @SerialName("supported_encodings") val supportedEncodings: List<String> = listOf("json")
)

/**
 * Handshake response sent by agent to proxy.
 */
@Serializable
data class HandshakeResponse(
    @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_V2_VERSION,
    @SerialName("agent_name") val agentName: String,
    val capabilities: AgentCapabilities,
    val encoding: String = "json"
)

/**
 * Agent capabilities advertised during handshake.
 */
@Serializable
data class AgentCapabilities(
    @SerialName("handles_request_headers") val handlesRequestHeaders: Boolean = true,
    @SerialName("handles_request_body") val handlesRequestBody: Boolean = false,
    @SerialName("handles_response_headers") val handlesResponseHeaders: Boolean = false,
    @SerialName("handles_response_body") val handlesResponseBody: Boolean = false,
    @SerialName("supports_streaming") val supportsStreaming: Boolean = false,
    @SerialName("supports_cancellation") val supportsCancellation: Boolean = true,
    @SerialName("max_concurrent_requests") val maxConcurrentRequests: Int? = null,
    @SerialName("supported_features") val supportedFeatures: List<String> = emptyList()
) {
    /**
     * Builder for creating AgentCapabilities with a fluent API.
     */
    class Builder {
        private var handlesRequestHeaders: Boolean = true
        private var handlesRequestBody: Boolean = false
        private var handlesResponseHeaders: Boolean = false
        private var handlesResponseBody: Boolean = false
        private var supportsStreaming: Boolean = false
        private var supportsCancellation: Boolean = true
        private var maxConcurrentRequests: Int? = null
        private val supportedFeatures: MutableList<String> = mutableListOf()

        fun handlesRequestHeaders(value: Boolean) = apply { handlesRequestHeaders = value }
        fun handlesRequestBody(value: Boolean) = apply { handlesRequestBody = value }
        fun handlesResponseHeaders(value: Boolean) = apply { handlesResponseHeaders = value }
        fun handlesResponseBody(value: Boolean) = apply { handlesResponseBody = value }
        fun supportsStreaming(value: Boolean) = apply { supportsStreaming = value }
        fun supportsCancellation(value: Boolean) = apply { supportsCancellation = value }
        fun maxConcurrentRequests(value: Int?) = apply { maxConcurrentRequests = value }
        fun addFeature(feature: String) = apply { supportedFeatures.add(feature) }
        fun addFeatures(vararg features: String) = apply { supportedFeatures.addAll(features) }

        fun build() = AgentCapabilities(
            handlesRequestHeaders = handlesRequestHeaders,
            handlesRequestBody = handlesRequestBody,
            handlesResponseHeaders = handlesResponseHeaders,
            handlesResponseBody = handlesResponseBody,
            supportsStreaming = supportsStreaming,
            supportsCancellation = supportsCancellation,
            maxConcurrentRequests = maxConcurrentRequests,
            supportedFeatures = supportedFeatures.toList()
        )
    }

    companion object {
        /** Create a builder for AgentCapabilities. */
        fun builder() = Builder()

        /** Default capabilities for a simple request-only agent. */
        fun requestOnly() = AgentCapabilities()

        /** Capabilities for a full inspection agent. */
        fun fullInspection() = AgentCapabilities(
            handlesRequestHeaders = true,
            handlesRequestBody = true,
            handlesResponseHeaders = true,
            handlesResponseBody = true,
            supportsStreaming = true,
            supportsCancellation = true
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
 * V2 request headers message with request ID for multiplexing.
 */
@Serializable
data class RequestHeadersV2(
    @SerialName("request_id") val requestId: Long,
    val metadata: RequestMetadataV2,
    val method: String,
    val uri: String,
    val headers: Map<String, List<String>> = emptyMap(),
    @SerialName("has_body") val hasBody: Boolean = false
)

/**
 * V2 request metadata with additional fields.
 */
@Serializable
data class RequestMetadataV2(
    @SerialName("correlation_id") val correlationId: String,
    @SerialName("client_ip") val clientIp: String,
    @SerialName("client_port") val clientPort: Int,
    @SerialName("server_name") val serverName: String? = null,
    val protocol: String = "HTTP/1.1",
    @SerialName("tls_version") val tlsVersion: String? = null,
    @SerialName("route_id") val routeId: String? = null,
    val timestamp: String = ""
)

/**
 * V2 request body chunk message.
 */
@Serializable
data class RequestBodyChunkV2(
    @SerialName("request_id") val requestId: Long,
    @SerialName("chunk_index") val chunkIndex: Int,
    val data: String,
    @SerialName("is_last") val isLast: Boolean = false
)

/**
 * V2 response headers message.
 */
@Serializable
data class ResponseHeadersV2(
    @SerialName("request_id") val requestId: Long,
    @SerialName("status_code") val statusCode: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    @SerialName("has_body") val hasBody: Boolean = false
)

/**
 * V2 response body chunk message.
 */
@Serializable
data class ResponseBodyChunkV2(
    @SerialName("request_id") val requestId: Long,
    @SerialName("chunk_index") val chunkIndex: Int,
    val data: String,
    @SerialName("is_last") val isLast: Boolean = false
)

/**
 * V2 decision message with request ID.
 */
@Serializable
data class DecisionMessageV2(
    @SerialName("request_id") val requestId: Long,
    val decision: DecisionV2,
    @SerialName("request_headers") val requestHeaders: List<HeaderOpV2> = emptyList(),
    @SerialName("response_headers") val responseHeaders: List<HeaderOpV2> = emptyList(),
    val audit: AuditMetadataV2? = null
)

/**
 * V2 decision types.
 */
@Serializable
sealed class DecisionV2 {
    @Serializable
    @SerialName("allow")
    data object Allow : DecisionV2()

    @Serializable
    @SerialName("block")
    data class Block(
        val status: Int,
        val body: String? = null,
        val headers: Map<String, String>? = null
    ) : DecisionV2()

    @Serializable
    @SerialName("redirect")
    data class Redirect(
        val url: String,
        val status: Int = 302
    ) : DecisionV2()
}

/**
 * V2 header operation.
 */
@Serializable
sealed class HeaderOpV2 {
    @Serializable
    @SerialName("set")
    data class Set(val name: String, val value: String) : HeaderOpV2()

    @Serializable
    @SerialName("add")
    data class Add(val name: String, val value: String) : HeaderOpV2()

    @Serializable
    @SerialName("remove")
    data class Remove(val name: String) : HeaderOpV2()
}

/**
 * V2 audit metadata.
 */
@Serializable
data class AuditMetadataV2(
    val tags: List<String> = emptyList(),
    @SerialName("rule_ids") val ruleIds: List<String> = emptyList(),
    val confidence: Float? = null,
    @SerialName("reason_codes") val reasonCodes: List<String> = emptyList(),
    val custom: Map<String, JsonElement> = emptyMap()
)

/**
 * Cancel request message.
 */
@Serializable
data class CancelRequestMessage(
    @SerialName("request_id") val requestId: Long,
    val reason: String? = null
)

/**
 * Cancel all requests message.
 */
@Serializable
data class CancelAllMessage(
    val reason: String? = null
)

/**
 * Registration request for reverse connections.
 */
@Serializable
data class RegistrationRequest(
    @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_V2_VERSION,
    @SerialName("agent_id") val agentId: String,
    val capabilities: AgentCapabilities,
    @SerialName("auth_token") val authToken: String? = null,
    val metadata: JsonObject? = null
)

/**
 * Registration response for reverse connections.
 */
@Serializable
data class RegistrationResponse(
    val accepted: Boolean,
    val error: String? = null,
    @SerialName("assigned_id") val assignedId: String? = null,
    val config: JsonObject? = null
)

/**
 * JSON serializer for v2 protocol.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
val protocolV2Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
