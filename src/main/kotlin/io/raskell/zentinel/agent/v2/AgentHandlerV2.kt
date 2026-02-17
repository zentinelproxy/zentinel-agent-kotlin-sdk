package io.raskell.zentinel.agent.v2

import io.raskell.zentinel.agent.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import mu.KotlinLogging
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Handler that bridges an AgentV2 implementation to the v2 protocol.
 *
 * Manages request state, multiplexing, and protocol translation.
 *
 * Example:
 * ```kotlin
 * val agent = MyAgentV2()
 * val handler = AgentHandlerV2(agent)
 *
 * // Handle incoming messages
 * val response = handler.handleRequestHeaders(requestHeaders)
 * ```
 */
class AgentHandlerV2(private val agent: AgentV2) {

    /**
     * In-flight requests indexed by request ID.
     */
    private val requests = ConcurrentHashMap<Long, RequestState>()

    /**
     * Mutex for coordinating shutdown operations.
     */
    private val shutdownMutex = Mutex()

    /**
     * Whether the handler is draining (not accepting new requests).
     */
    @Volatile
    private var draining = false

    /**
     * State for an in-flight request.
     */
    private data class RequestState(
        val request: Request,
        val requestV2: RequestV2,
        var bodyChunks: MutableList<ByteArray> = mutableListOf(),
        var responseHeaders: Response? = null
    )

    /**
     * Get the agent's capabilities.
     */
    fun capabilities(): AgentCapabilities = agent.capabilities()

    /**
     * Get the agent's current health status.
     */
    fun healthStatus(): HealthStatus = agent.healthStatus()

    /**
     * Get the agent's current metrics.
     */
    fun metrics(): MetricsReport = agent.metrics()

    /**
     * Handle a handshake request.
     */
    fun handleHandshake(request: HandshakeRequest): HandshakeResponse {
        logger.debug { "Handshake from ${request.clientName}, version ${request.protocolVersion}" }

        if (request.protocolVersion != PROTOCOL_V2_VERSION) {
            logger.warn { "Version mismatch: client=${request.protocolVersion}, server=$PROTOCOL_V2_VERSION" }
        }

        return HandshakeResponse(
            protocolVersion = PROTOCOL_V2_VERSION,
            agentName = agent.name,
            capabilities = agent.capabilities(),
            encoding = "json"
        )
    }

    /**
     * Handle request headers message.
     */
    suspend fun handleRequestHeaders(msg: RequestHeadersV2): DecisionMessageV2 {
        if (draining) {
            logger.debug { "Rejecting request ${msg.requestId} during drain" }
            return DecisionMessageV2(
                requestId = msg.requestId,
                decision = DecisionV2.Block(
                    status = 503,
                    body = "Agent is draining"
                )
            )
        }

        val event = RequestHeadersEvent(
            metadata = RequestMetadata(
                correlationId = msg.metadata.correlationId,
                requestId = msg.metadata.correlationId,
                clientIp = msg.metadata.clientIp,
                clientPort = msg.metadata.clientPort,
                serverName = msg.metadata.serverName,
                protocol = msg.metadata.protocol,
                tlsVersion = msg.metadata.tlsVersion,
                routeId = msg.metadata.routeId,
                timestamp = msg.metadata.timestamp
            ),
            method = msg.method,
            uri = msg.uri,
            headers = msg.headers
        )

        val request = Request.fromEvent(event)
        val requestV2 = RequestV2(request, msg.requestId)

        // Store request state for potential body handling
        requests[msg.requestId] = RequestState(request, requestV2)

        return try {
            val decision = agent.onRequest(request)
            val response = decision.build()
            toDecisionMessageV2(msg.requestId, response)
        } catch (e: Exception) {
            logger.error(e) { "Error handling request headers for ${msg.requestId}" }
            DecisionMessageV2(
                requestId = msg.requestId,
                decision = DecisionV2.Block(
                    status = 500,
                    body = "Agent error: ${e.message}"
                )
            )
        }
    }

    /**
     * Handle request body chunk message.
     */
    suspend fun handleRequestBodyChunk(msg: RequestBodyChunkV2): DecisionMessageV2 {
        val state = requests[msg.requestId]
        if (state == null) {
            logger.warn { "Body chunk for unknown request ${msg.requestId}" }
            return DecisionMessageV2(
                requestId = msg.requestId,
                decision = DecisionV2.Allow
            )
        }

        // Decode and accumulate body chunk
        val chunkBytes = Base64.getDecoder().decode(msg.data)
        state.bodyChunks.add(chunkBytes)

        if (!msg.isLast) {
            // More chunks expected, allow to continue
            return DecisionMessageV2(
                requestId = msg.requestId,
                decision = DecisionV2.Allow
            )
        }

        // Combine all body chunks
        val totalSize = state.bodyChunks.sumOf { it.size }
        val fullBody = ByteArray(totalSize)
        var offset = 0
        for (chunk in state.bodyChunks) {
            System.arraycopy(chunk, 0, fullBody, offset, chunk.size)
            offset += chunk.size
        }

        val requestWithBody = state.request.withBody(fullBody)

        return try {
            val decision = agent.onRequestBody(requestWithBody)
            val response = decision.build()
            toDecisionMessageV2(msg.requestId, response)
        } catch (e: Exception) {
            logger.error(e) { "Error handling request body for ${msg.requestId}" }
            DecisionMessageV2(
                requestId = msg.requestId,
                decision = DecisionV2.Block(
                    status = 500,
                    body = "Agent error: ${e.message}"
                )
            )
        }
    }

    /**
     * Handle response headers message.
     */
    suspend fun handleResponseHeaders(msg: ResponseHeadersV2): DecisionMessageV2 {
        val state = requests[msg.requestId]
        if (state == null) {
            logger.warn { "Response headers for unknown request ${msg.requestId}" }
            return DecisionMessageV2(
                requestId = msg.requestId,
                decision = DecisionV2.Allow
            )
        }

        val event = ResponseHeadersEvent(
            correlationId = state.request.correlationId,
            status = msg.statusCode,
            headers = msg.headers
        )

        val response = Response.fromEvent(event)
        state.responseHeaders = response

        return try {
            val decision = agent.onResponse(state.request, response)
            val agentResponse = decision.build()
            toDecisionMessageV2(msg.requestId, agentResponse)
        } catch (e: Exception) {
            logger.error(e) { "Error handling response headers for ${msg.requestId}" }
            DecisionMessageV2(
                requestId = msg.requestId,
                decision = DecisionV2.Allow
            )
        }
    }

    /**
     * Handle response body chunk message.
     */
    suspend fun handleResponseBodyChunk(msg: ResponseBodyChunkV2): DecisionMessageV2 {
        val state = requests[msg.requestId]
        if (state == null) {
            logger.warn { "Response body chunk for unknown request ${msg.requestId}" }
            return DecisionMessageV2(
                requestId = msg.requestId,
                decision = DecisionV2.Allow
            )
        }

        val responseHeaders = state.responseHeaders
        if (responseHeaders == null) {
            logger.warn { "Response body chunk before response headers for ${msg.requestId}" }
            return DecisionMessageV2(
                requestId = msg.requestId,
                decision = DecisionV2.Allow
            )
        }

        val chunkBytes = Base64.getDecoder().decode(msg.data)
        val responseWithBody = responseHeaders.withBody(chunkBytes)

        return try {
            val decision = agent.onResponseBody(state.request, responseWithBody)
            val agentResponse = decision.build()
            toDecisionMessageV2(msg.requestId, agentResponse)
        } catch (e: Exception) {
            logger.error(e) { "Error handling response body for ${msg.requestId}" }
            DecisionMessageV2(
                requestId = msg.requestId,
                decision = DecisionV2.Allow
            )
        }
    }

    /**
     * Handle request cancellation.
     */
    suspend fun handleCancelRequest(msg: CancelRequestMessage) {
        val state = requests.remove(msg.requestId)
        if (state != null) {
            logger.debug { "Request ${msg.requestId} cancelled: ${msg.reason}" }
            try {
                agent.onRequestCancelled(msg.requestId, msg.reason)
            } catch (e: Exception) {
                logger.error(e) { "Error in onRequestCancelled for ${msg.requestId}" }
            }
        }
    }

    /**
     * Handle cancel all requests.
     */
    suspend fun handleCancelAll(msg: CancelAllMessage) {
        val requestIds = requests.keys.toList()
        requests.clear()

        logger.debug { "All ${requestIds.size} requests cancelled: ${msg.reason}" }

        try {
            agent.onAllRequestsCancelled(msg.reason)
        } catch (e: Exception) {
            logger.error(e) { "Error in onAllRequestsCancelled" }
        }
    }

    /**
     * Handle request completion (cleanup).
     */
    suspend fun handleRequestComplete(requestId: Long, status: Int, durationMs: Long) {
        val state = requests.remove(requestId)
        if (state != null) {
            try {
                agent.onRequestComplete(state.request, status, durationMs)
            } catch (e: Exception) {
                logger.error(e) { "Error in onRequestComplete for $requestId" }
            }
        }
    }

    /**
     * Start draining - stop accepting new requests.
     */
    suspend fun startDrain(timeoutMs: Long) {
        shutdownMutex.withLock {
            if (draining) return
            draining = true
            logger.info { "Starting drain with timeout ${timeoutMs}ms" }

            try {
                agent.onDrain(timeoutMs)
            } catch (e: Exception) {
                logger.error(e) { "Error in onDrain" }
            }
        }
    }

    /**
     * Shutdown the handler and agent.
     */
    suspend fun shutdown() {
        shutdownMutex.withLock {
            draining = true

            // Cancel all pending requests
            val pendingCount = requests.size
            if (pendingCount > 0) {
                logger.info { "Cancelling $pendingCount pending requests during shutdown" }
                handleCancelAll(CancelAllMessage(reason = "Agent shutdown"))
            }

            try {
                agent.onShutdown()
            } catch (e: Exception) {
                logger.error(e) { "Error in onShutdown" }
            }

            logger.info { "Agent handler shutdown complete" }
        }
    }

    /**
     * Handle stream close notification.
     */
    suspend fun handleStreamClosed(streamId: String, error: Throwable?) {
        try {
            agent.onStreamClosed(streamId, error)
        } catch (e: Exception) {
            logger.error(e) { "Error in onStreamClosed for stream $streamId" }
        }
    }

    /**
     * Get count of active requests.
     */
    fun activeRequestCount(): Int = requests.size

    /**
     * Convert AgentResponse to DecisionMessageV2.
     */
    private fun toDecisionMessageV2(requestId: Long, response: AgentResponse): DecisionMessageV2 {
        val decision = when (val d = response.decision) {
            is ProtocolDecision.Allow -> DecisionV2.Allow
            is ProtocolDecision.Block -> DecisionV2.Block(
                status = d.status,
                body = d.body,
                headers = d.headers
            )
            is ProtocolDecision.Redirect -> DecisionV2.Redirect(
                url = d.url,
                status = d.status
            )
            is ProtocolDecision.Challenge -> DecisionV2.Block(
                status = 403,
                body = "Challenge required"
            )
        }

        val requestHeaders = response.requestHeaders.map { op ->
            when (op) {
                is HeaderOp.Set -> HeaderOpV2.Set(op.name, op.value)
                is HeaderOp.Add -> HeaderOpV2.Add(op.name, op.value)
                is HeaderOp.Remove -> HeaderOpV2.Remove(op.name)
            }
        }

        val responseHeaders = response.responseHeaders.map { op ->
            when (op) {
                is HeaderOp.Set -> HeaderOpV2.Set(op.name, op.value)
                is HeaderOp.Add -> HeaderOpV2.Add(op.name, op.value)
                is HeaderOp.Remove -> HeaderOpV2.Remove(op.name)
            }
        }

        val audit = if (response.audit.tags.isNotEmpty() ||
            response.audit.ruleIds.isNotEmpty() ||
            response.audit.confidence != null ||
            response.audit.reasonCodes.isNotEmpty() ||
            response.audit.custom.isNotEmpty()
        ) {
            AuditMetadataV2(
                tags = response.audit.tags,
                ruleIds = response.audit.ruleIds,
                confidence = response.audit.confidence,
                reasonCodes = response.audit.reasonCodes,
                custom = response.audit.custom
            )
        } else {
            null
        }

        return DecisionMessageV2(
            requestId = requestId,
            decision = decision,
            requestHeaders = requestHeaders,
            responseHeaders = responseHeaders,
            audit = audit
        )
    }
}
