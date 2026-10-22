package io.raskell.zentinel.agent.v2

import io.raskell.zentinel.agent.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.io.ByteArrayOutputStream
import java.util.Base64

private val logger = KotlinLogging.logger {}

/**
 * Routes protocol v2 events to an agent's methods.
 *
 * The handler is transport neutral: events arrive as JSON objects in the shape
 * the proxy sends on the Unix socket (the serde form of the Rust protocol
 * types), and every handler returns an [AgentResponse]. The UDS server passes
 * them through; the gRPC server converts protobuf messages to and from them.
 *
 * One handler serves every connection of a runner: the proxy keeps a pool of
 * connections per agent and may send a request's headers on one and its body
 * on another, so per-request state is keyed by correlation id, not connection.
 *
 * @param agent The agent. An [AgentV2] reports its own capabilities and gets
 *   the lifecycle callbacks; a plain [Agent] gets defaults from its name.
 */
class AgentHandlerV2(private val agent: Agent) {

    private class RequestState(val request: Request) {
        val requestBody = ByteArrayOutputStream()
        val responseBody = ByteArrayOutputStream()
        var responseStatus: Int = 200
        var responseHeaders: Map<String, List<String>> = emptyMap()
    }

    /** In-flight requests, bounded: `request_complete` reaches only agents that subscribe to it. */
    private val requests = object : LinkedHashMap<String, RequestState>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RequestState>?): Boolean =
            size > MAX_TRACKED_REQUESTS
    }

    private val shutdownMutex = Mutex()

    @Volatile
    private var draining = false

    private val agentV2: AgentV2? get() = agent as? AgentV2

    /** The agent's name. */
    val agentName: String get() = agent.name

    /** The agent's capabilities. */
    fun capabilities(): AgentCapabilities = agentV2?.capabilities() ?: AgentCapabilities()

    /** The agent's current health status. */
    fun healthStatus(): HealthStatus = agentV2?.healthStatus() ?: HealthStatus.Healthy

    /** The agent's current metrics. */
    fun metrics(): MetricsReport = agentV2?.metrics() ?: MetricsReport()

    /** Answer the UDS handshake. */
    fun handleHandshake(request: HandshakeRequest): HandshakeResponse {
        val caps = capabilities().toUds(agent.name)
        if (PROTOCOL_V2_VERSION !in request.supportedVersions) {
            return HandshakeResponse(
                capabilities = caps,
                success = false,
                error = "protocol v2 not offered (proxy supports ${request.supportedVersions})"
            )
        }
        logger.debug { "Handshake with ${request.proxyId} ${request.proxyVersion}" }
        return HandshakeResponse(capabilities = caps, success = true)
    }

    /**
     * Apply a configuration. The proxy sends one on every new connection,
     * after the handshake.
     *
     * @return true if the agent accepted it (`onConfigure` did not throw)
     */
    suspend fun configure(config: JsonElement?, version: String? = null): Boolean = try {
        agent.onConfigure(config as? JsonObject ?: JsonObject(emptyMap()))
        logger.info { "Configuration applied" + (version?.let { " (version $it)" } ?: "") }
        true
    } catch (e: Exception) {
        logger.error(e) { "Configuration rejected" }
        false
    }

    /**
     * Handle one event and return the response.
     *
     * @param type The event type
     * @param payload The event in the proxy's JSON shape
     */
    suspend fun dispatch(type: EventType, payload: JsonObject): AgentResponse = try {
        when (type) {
            EventType.CONFIGURE -> {
                val event = protocolJson.decodeFromJsonElement<ConfigureEvent>(payload)
                if (configure(event.config, event.configVersion)) {
                    AgentResponse.defaultAllow()
                } else {
                    AgentResponse.block(500, "Configuration rejected")
                }
            }
            EventType.REQUEST_HEADERS -> onRequestHeaders(protocolJson.decodeFromJsonElement(payload))
            EventType.REQUEST_BODY_CHUNK -> onRequestBodyChunk(protocolJson.decodeFromJsonElement(payload))
            EventType.RESPONSE_HEADERS -> onResponseHeaders(protocolJson.decodeFromJsonElement(payload))
            EventType.RESPONSE_BODY_CHUNK -> onResponseBodyChunk(protocolJson.decodeFromJsonElement(payload))
            EventType.REQUEST_COMPLETE -> onRequestComplete(protocolJson.decodeFromJsonElement(payload))
            EventType.GUARDRAIL_INSPECT -> onGuardrailInspect(protocolJson.decodeFromJsonElement(payload))
            EventType.WEBSOCKET_FRAME -> AgentResponse.defaultAllow()
        }
    } catch (e: Exception) {
        logger.error(e) { "Error handling $type" }
        when (type) {
            EventType.REQUEST_HEADERS, EventType.REQUEST_BODY_CHUNK -> AgentResponse.block(500, "Agent error")
            else -> AgentResponse.defaultAllow()
        }
    }

    private fun remember(correlationId: String, state: RequestState) =
        synchronized(requests) { requests[correlationId] = state }

    private fun state(correlationId: String): RequestState = synchronized(requests) {
        requests.getOrPut(correlationId) {
            // The agent did not see this request's headers (it is not
            // subscribed to them): give it what is known.
            RequestState(Request.fromEvent(placeholderHeaders(correlationId)))
        }
    }

    private fun forget(correlationId: String): RequestState? =
        synchronized(requests) { requests.remove(correlationId) }

    private suspend fun onRequestHeaders(event: RequestHeadersEvent): AgentResponse {
        if (draining) {
            return AgentResponse.block(503, "Agent is draining")
        }
        val request = Request.fromEvent(event)
        remember(event.metadata.correlationId, RequestState(request))
        return agent.onRequest(request).build()
    }

    private suspend fun onRequestBodyChunk(event: RequestBodyChunkEvent): AgentResponse {
        val state = state(event.correlationId)
        val body = synchronized(state) {
            state.requestBody.write(Base64.getDecoder().decode(event.data))
            if (event.isLast) state.requestBody.toByteArray() else null
        } ?: return Decision.allow().needsMoreData().build()
        return agent.onRequestBody(state.request.withBody(body)).build()
    }

    private suspend fun onResponseHeaders(event: ResponseHeadersEvent): AgentResponse {
        val state = state(event.correlationId)
        synchronized(state) {
            state.responseStatus = event.status
            state.responseHeaders = event.headers
            state.responseBody.reset()
        }
        return agent.onResponse(state.request, Response.fromEvent(event)).build()
    }

    private suspend fun onResponseBodyChunk(event: ResponseBodyChunkEvent): AgentResponse {
        val state = state(event.correlationId)
        val body = synchronized(state) {
            state.responseBody.write(Base64.getDecoder().decode(event.data))
            if (event.isLast) state.responseBody.toByteArray() else null
        } ?: return Decision.allow().needsMoreData().build()
        val response = Response.fromEvent(
            ResponseHeadersEvent(event.correlationId, state.responseStatus, state.responseHeaders)
        ).withBody(body)
        return agent.onResponseBody(state.request, response).build()
    }

    private suspend fun onRequestComplete(event: RequestCompleteEvent): AgentResponse {
        val state = forget(event.correlationId)
        if (state != null) {
            agent.onRequestComplete(state.request, event.status, event.durationMs)
        }
        return AgentResponse.defaultAllow()
    }

    private suspend fun onGuardrailInspect(event: GuardrailInspectEvent): AgentResponse {
        val response = agent.onGuardrailInspect(event)
        return AgentResponse(
            audit = AuditMetadata(
                tags = if (response.detected) listOf("guardrail_detected") else emptyList(),
                ruleIds = response.detections.map { it.category },
                confidence = response.confidence.toFloat(),
                custom = mapOf(
                    "guardrail_response" to protocolJson.encodeToJsonElement(GuardrailResponse.serializer(), response)
                )
            )
        )
    }

    /** A request was cancelled by the proxy (UDS 0x40 / gRPC `CancelRequest`). */
    suspend fun handleCancel(correlationId: String, reason: String?) {
        if (forget(correlationId) != null) {
            try {
                agentV2?.onRequestCancelled(correlationId, reason)
            } catch (e: Exception) {
                logger.error(e) { "Error in onRequestCancelled for $correlationId" }
            }
        }
    }

    /** Cancel every in-flight request. */
    suspend fun handleCancelAll(reason: String?) {
        synchronized(requests) { requests.clear() }
        try {
            agentV2?.onAllRequestsCancelled(reason)
        } catch (e: Exception) {
            logger.error(e) { "Error in onAllRequestsCancelled" }
        }
    }

    /** Start draining: new requests are blocked with 503. */
    suspend fun startDrain(timeoutMs: Long) {
        shutdownMutex.withLock {
            if (draining) return
            draining = true
            logger.info { "Starting drain with timeout ${timeoutMs}ms" }
            try {
                agentV2?.onDrain(timeoutMs)
            } catch (e: Exception) {
                logger.error(e) { "Error in onDrain" }
            }
        }
    }

    /** Shut the handler and agent down. */
    suspend fun shutdown() {
        shutdownMutex.withLock {
            draining = true
            if (activeRequestCount() > 0) {
                handleCancelAll("Agent shutdown")
            }
            try {
                agentV2?.onShutdown()
            } catch (e: Exception) {
                logger.error(e) { "Error in onShutdown" }
            }
        }
    }

    /** A connection or stream closed. */
    suspend fun handleStreamClosed(streamId: String, error: Throwable?) {
        try {
            agentV2?.onStreamClosed(streamId, error)
        } catch (e: Exception) {
            logger.error(e) { "Error in onStreamClosed for stream $streamId" }
        }
    }

    /** Number of requests whose state is held. */
    fun activeRequestCount(): Int = synchronized(requests) { requests.size }

    companion object {
        /** Requests remembered between their header and body/response events. */
        const val MAX_TRACKED_REQUESTS = 10_000

        private fun placeholderHeaders(correlationId: String) = RequestHeadersEvent(
            metadata = RequestMetadata(correlationId = correlationId, requestId = "", clientIp = "", clientPort = 0),
            method = "",
            uri = "",
            headers = emptyMap()
        )
    }
}

/**
 * The correlation id of an event payload (top level, or in `metadata`).
 */
fun correlationIdOf(payload: JsonObject): String {
    (payload["correlation_id"] as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
    val metadata = payload["metadata"] as? JsonObject ?: return ""
    return (metadata["correlation_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
}

/** The response with its request's correlation id in `audit.custom.correlation_id`. */
fun AgentResponse.withCorrelationId(correlationId: String): AgentResponse =
    copy(audit = audit.copy(custom = audit.custom + ("correlation_id" to JsonPrimitive(correlationId))))
