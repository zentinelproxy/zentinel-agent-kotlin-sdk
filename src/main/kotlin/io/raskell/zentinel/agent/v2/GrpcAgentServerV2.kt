package io.raskell.zentinel.agent.v2

import io.grpc.ForwardingServerCall
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import io.raskell.zentinel.agent.AgentResponse
import io.raskell.zentinel.agent.EventType
import io.raskell.zentinel.agent.HeaderOp
import io.raskell.zentinel.agent.ProtocolDecision
import io.raskell.zentinel.agent.v2.grpc.AgentServiceV2Grpc
import io.raskell.zentinel.agent.v2.grpc.AgentToProxy
import io.raskell.zentinel.agent.v2.grpc.ProxyToAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.TimeUnit
import io.raskell.zentinel.agent.v2.grpc.AgentResponse as PbAgentResponse
import io.raskell.zentinel.agent.v2.grpc.HeaderOp as PbHeaderOp
import io.raskell.zentinel.agent.v2.grpc.Header as PbHeader

private val logger = KotlinLogging.logger {}

/**
 * Agent protocol v2 over gRPC: the `zentinel.agent.v2.AgentServiceV2` service
 * from the proxy's `agent_v2.proto`.
 *
 * The proxy opens one bidirectional `ProcessStream` per connection and waits
 * for the stream's response headers before it sends the handshake, so they
 * are sent as soon as the stream opens. Then come a `ConfigureEvent` and a
 * `Ping`; the `Pong` tells the proxy the configuration was applied, so the
 * configuration is applied before that Ping is answered, and a rejected
 * configuration ends the stream. Events follow and are handled concurrently.
 * Messages are converted to the JSON shapes [AgentHandlerV2] works with, so
 * both transports run the same agent code.
 */
class GrpcAgentServerV2(
    private val handler: AgentHandlerV2,
    private val host: String,
    private val requestedPort: Int
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: Server? = null

    /** The bound port (useful when started on port 0). */
    val port: Int get() = server?.port ?: requestedPort

    /** Bind and start serving. */
    fun start(): GrpcAgentServerV2 {
        val service = ServerInterceptors.intercept(Service(), SendHeadersImmediately)
        server = NettyServerBuilder.forAddress(InetSocketAddress(host, requestedPort))
            .addService(service)
            .maxInboundMessageSize(MAX_MESSAGE_SIZE_GRPC)
            .build()
            .start()
        return this
    }

    /** Stop serving. */
    fun stop() {
        server?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
        server?.shutdownNow()
        scope.cancel()
    }

    /** Block until the server terminates. */
    fun awaitTermination() {
        server?.awaitTermination()
    }

    private inner class Service : AgentServiceV2Grpc.AgentServiceV2ImplBase() {
        override fun processStream(responseObserver: StreamObserver<AgentToProxy>): StreamObserver<ProxyToAgent> {
            val streamId = "grpc-${System.nanoTime()}"
            val inbound = Channel<ProxyToAgent>(Channel.UNLIMITED)
            val out = SerializedObserver(responseObserver)

            // Control messages (handshake, configure, ping) in arrival order;
            // events concurrently.
            val job = scope.launch {
                try {
                    for (msg in inbound) {
                        when (msg.messageCase) {
                            ProxyToAgent.MessageCase.HANDSHAKE -> out.next(handshake(msg.handshake))
                            ProxyToAgent.MessageCase.CONFIGURE -> {
                                if (!configure(msg.configure)) {
                                    logger.error { "Configuration rejected; ending the stream" }
                                    out.error(Status.FAILED_PRECONDITION.withDescription("configuration rejected"))
                                    return@launch
                                }
                            }
                            ProxyToAgent.MessageCase.PING -> out.next(pong(msg.ping))
                            ProxyToAgent.MessageCase.CANCEL -> {
                                val cancel = msg.cancel
                                launch { handler.handleCancel(cancel.correlationId, cancel.reason.toString()) }
                            }
                            ProxyToAgent.MessageCase.MESSAGE_NOT_SET, null -> Unit
                            else -> launch { event(msg)?.let { out.next(it) } }
                        }
                    }
                } finally {
                    handler.handleStreamClosed(streamId, null)
                }
            }

            return object : StreamObserver<ProxyToAgent> {
                override fun onNext(value: ProxyToAgent) {
                    inbound.trySend(value)
                }

                override fun onError(t: Throwable) {
                    inbound.close()
                    job.cancel()
                }

                override fun onCompleted() {
                    inbound.close()
                    job.invokeOnCompletion { out.completed() }
                }
            }
        }

        override fun processEvent(request: ProxyToAgent, responseObserver: StreamObserver<AgentToProxy>) {
            val reply = runBlocking {
                when (request.messageCase) {
                    ProxyToAgent.MessageCase.HANDSHAKE -> handshake(request.handshake)
                    ProxyToAgent.MessageCase.PING -> pong(request.ping)
                    ProxyToAgent.MessageCase.CONFIGURE -> {
                        configure(request.configure)
                        AgentToProxy.getDefaultInstance()
                    }
                    else -> event(request) ?: AgentToProxy.getDefaultInstance()
                }
            }
            responseObserver.onNext(reply)
            responseObserver.onCompleted()
        }
    }

    private fun handshake(req: io.raskell.zentinel.agent.v2.grpc.HandshakeRequest): AgentToProxy {
        val caps = handler.capabilities()
        val uds = caps.toUds(handler.agentName)
        val f = caps.features
        val capabilities = io.raskell.zentinel.agent.v2.grpc.AgentCapabilities.newBuilder()
            .setProtocolVersion(PROTOCOL_V2_VERSION)
            .setAgentId(uds.agentId)
            .setName(uds.name)
            .setVersion(uds.version)
            .addAllSupportedEvents(caps.supportedEvents.map { it.grpcNumber() }.distinct())
            .setFeatures(
                io.raskell.zentinel.agent.v2.grpc.AgentFeatures.newBuilder()
                    .setStreamingBody(f.streamingBody)
                    .setWebsocket(f.websocket)
                    .setGuardrails(f.guardrails)
                    .setConfigPush(f.configPush)
                    .setMetricsExport(f.metricsExport)
                    .setConcurrentRequests(f.concurrentRequests)
                    .setCancellation(f.cancellation)
                    .setFlowControl(f.flowControl)
                    .setHealthReporting(f.healthReporting)
            )
            .setLimits(
                io.raskell.zentinel.agent.v2.grpc.AgentLimits.newBuilder()
                    .setMaxBodySize(caps.limits.maxBodySize)
                    .setMaxConcurrency(caps.limits.maxConcurrency)
                    .setPreferredChunkSize(caps.limits.preferredChunkSize)
            )
            .setHealthConfig(
                io.raskell.zentinel.agent.v2.grpc.HealthConfig.newBuilder()
                    .setReportIntervalMs(caps.healthReportIntervalMs)
            )
        val ok = PROTOCOL_V2_VERSION in req.supportedVersionsList
        val hs = io.raskell.zentinel.agent.v2.grpc.HandshakeResponse.newBuilder()
            .setProtocolVersion(PROTOCOL_V2_VERSION)
            .setCapabilities(capabilities)
            .setSuccess(ok)
        if (!ok) hs.error = "protocol v2 not offered (proxy supports ${req.supportedVersionsList})"
        return AgentToProxy.newBuilder().setHandshake(hs).build()
    }

    private suspend fun configure(ev: io.raskell.zentinel.agent.v2.grpc.ConfigureEvent): Boolean {
        val config = try {
            if (ev.configJson.isEmpty()) JsonObject(emptyMap()) else Json.parseToJsonElement(ev.configJson)
        } catch (e: Exception) {
            logger.error(e) { "Configuration is not valid JSON" }
            return false
        }
        return handler.configure(config, if (ev.hasConfigVersion()) ev.configVersion else null)
    }

    private fun pong(ping: io.raskell.zentinel.agent.v2.grpc.Ping): AgentToProxy =
        AgentToProxy.newBuilder().setPong(
            io.raskell.zentinel.agent.v2.grpc.Pong.newBuilder()
                .setSequence(ping.sequence)
                .setPingTimestampMs(ping.timestampMs)
                .setTimestampMs(System.currentTimeMillis())
        ).build()

    private suspend fun event(msg: ProxyToAgent): AgentToProxy? {
        val (type, payload) = eventToJson(msg) ?: return null
        val response = handler.dispatch(type, payload)
        return AgentToProxy.newBuilder().setResponse(responseToProto(correlationIdOf(payload), response)).build()
    }

    /** Wraps an observer so concurrent event handlers can write to it. */
    private class SerializedObserver(private val delegate: StreamObserver<AgentToProxy>) {
        private var done = false

        @Synchronized
        fun next(value: AgentToProxy) {
            if (!done) delegate.onNext(value)
        }

        @Synchronized
        fun error(status: Status) {
            if (!done) {
                done = true
                delegate.onError(status.asRuntimeException())
            }
        }

        @Synchronized
        fun completed() {
            if (!done) {
                done = true
                delegate.onCompleted()
            }
        }
    }

    /**
     * Sends the response headers as soon as a call starts. grpc-java would
     * hold them back until the first response message, and the proxy waits
     * for them before it sends the handshake.
     */
    private object SendHeadersImmediately : ServerInterceptor {
        override fun <ReqT, RespT> interceptCall(
            call: ServerCall<ReqT, RespT>,
            headers: Metadata,
            next: ServerCallHandler<ReqT, RespT>
        ): ServerCall.Listener<ReqT> {
            val wrapped = object : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                private var sent = false

                @Synchronized
                override fun sendHeaders(headers: Metadata) {
                    if (!sent) {
                        sent = true
                        super.sendHeaders(headers)
                    }
                }
            }
            val listener = next.startCall(wrapped, headers)
            if (call.methodDescriptor.type == io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING) {
                wrapped.sendHeaders(Metadata())
            }
            return listener
        }
    }

    companion object {
        private fun headersMap(headers: List<PbHeader>): JsonObject {
            val out = LinkedHashMap<String, MutableList<String>>()
            for (h in headers) out.getOrPut(h.name) { mutableListOf() }.add(h.value)
            return JsonObject(out.mapValues { (_, v) -> JsonArray(v.map { JsonPrimitive(it) }) })
        }

        private fun b64(bytes: com.google.protobuf.ByteString): String =
            Base64.getEncoder().encodeToString(bytes.toByteArray())

        /** Convert a `ProxyToAgent` event to the handler's JSON shape. */
        fun eventToJson(msg: ProxyToAgent): Pair<EventType, JsonObject>? = when (msg.messageCase) {
            ProxyToAgent.MessageCase.REQUEST_HEADERS -> {
                val ev = msg.requestHeaders
                val md = ev.metadata
                EventType.REQUEST_HEADERS to buildJsonObject {
                    putJsonObject("metadata") {
                        put("correlation_id", md.correlationId)
                        put("request_id", md.requestId)
                        put("client_ip", md.clientIp)
                        put("client_port", md.clientPort)
                        if (md.hasServerName()) put("server_name", md.serverName)
                        put("protocol", md.protocol)
                        if (md.hasTlsVersion()) put("tls_version", md.tlsVersion)
                        if (md.hasRouteId()) put("route_id", md.routeId)
                        if (md.hasUpstreamId()) put("upstream_id", md.upstreamId)
                        put("timestamp", md.timestampMs.toString())
                        if (md.hasTraceparent()) put("traceparent", md.traceparent)
                    }
                    put("method", ev.method)
                    put("uri", ev.uri)
                    put("headers", headersMap(ev.headersList))
                }
            }
            ProxyToAgent.MessageCase.REQUEST_BODY_CHUNK, ProxyToAgent.MessageCase.RESPONSE_BODY_CHUNK -> {
                val request = msg.messageCase == ProxyToAgent.MessageCase.REQUEST_BODY_CHUNK
                val ev = if (request) msg.requestBodyChunk else msg.responseBodyChunk
                (if (request) EventType.REQUEST_BODY_CHUNK else EventType.RESPONSE_BODY_CHUNK) to buildJsonObject {
                    put("correlation_id", ev.correlationId)
                    put("data", b64(ev.data))
                    put("is_last", ev.isLast)
                    if (ev.hasTotalSize()) put("total_size", ev.totalSize)
                    put("chunk_index", ev.chunkIndex)
                    put(if (request) "bytes_received" else "bytes_sent", ev.bytesTransferred)
                }
            }
            ProxyToAgent.MessageCase.RESPONSE_HEADERS -> {
                val ev = msg.responseHeaders
                EventType.RESPONSE_HEADERS to buildJsonObject {
                    put("correlation_id", ev.correlationId)
                    put("status", ev.statusCode)
                    put("headers", headersMap(ev.headersList))
                }
            }
            ProxyToAgent.MessageCase.REQUEST_COMPLETE -> {
                val ev = msg.requestComplete
                EventType.REQUEST_COMPLETE to buildJsonObject {
                    put("correlation_id", ev.correlationId)
                    put("status", ev.statusCode)
                    put("duration_ms", ev.durationMs)
                    put("request_body_size", ev.bytesReceived)
                    put("response_body_size", ev.bytesSent)
                    if (ev.hasError()) put("error", ev.error)
                }
            }
            ProxyToAgent.MessageCase.GUARDRAIL -> {
                val ev = msg.guardrail
                EventType.GUARDRAIL_INSPECT to buildJsonObject {
                    put("correlation_id", ev.correlationId)
                    put("inspection_type", "prompt_injection")
                    put("content", ev.content)
                    if (ev.hasModel()) put("model", ev.model)
                }
            }
            ProxyToAgent.MessageCase.WEBSOCKET_FRAME -> EventType.WEBSOCKET_FRAME to buildJsonObject {
                put("correlation_id", msg.websocketFrame.correlationId)
            }
            else -> null
        }

        private fun headerOp(op: HeaderOp): PbHeaderOp = when (op) {
            is HeaderOp.Set -> PbHeaderOp.newBuilder().setSet(PbHeader.newBuilder().setName(op.name).setValue(op.value)).build()
            is HeaderOp.Add -> PbHeaderOp.newBuilder().setAdd(PbHeader.newBuilder().setName(op.name).setValue(op.value)).build()
            is HeaderOp.Remove -> PbHeaderOp.newBuilder().setRemove(op.name).build()
        }

        /** Convert an [AgentResponse] to the protobuf message. */
        fun responseToProto(correlationId: String, resp: AgentResponse): PbAgentResponse {
            val out = PbAgentResponse.newBuilder()
                .setCorrelationId(correlationId)
                .setNeedsMore(resp.needsMore)
            when (val d = resp.decision) {
                is ProtocolDecision.Allow -> out.setAllow(io.raskell.zentinel.agent.v2.grpc.AllowDecision.getDefaultInstance())
                is ProtocolDecision.Block -> {
                    val block = io.raskell.zentinel.agent.v2.grpc.BlockDecision.newBuilder().setStatus(d.status)
                    d.body?.let { block.setBody(it) }
                    d.headers?.forEach { (name, value) -> block.addHeaders(PbHeader.newBuilder().setName(name).setValue(value)) }
                    out.setBlock(block)
                }
                is ProtocolDecision.Redirect -> out.setRedirect(
                    io.raskell.zentinel.agent.v2.grpc.RedirectDecision.newBuilder().setUrl(d.url).setStatus(d.status)
                )
                is ProtocolDecision.Challenge -> out.setChallenge(
                    io.raskell.zentinel.agent.v2.grpc.ChallengeDecision.newBuilder()
                        .setChallengeType(d.challengeType)
                        .putAllParams(d.params)
                )
            }
            resp.requestHeaders.forEach { out.addRequestHeaders(headerOp(it)) }
            resp.responseHeaders.forEach { out.addResponseHeaders(headerOp(it)) }
            val a = resp.audit
            if (a.tags.isNotEmpty() || a.ruleIds.isNotEmpty() || a.reasonCodes.isNotEmpty() ||
                a.confidence != null || a.custom.isNotEmpty()
            ) {
                val audit = io.raskell.zentinel.agent.v2.grpc.AuditMetadata.newBuilder()
                    .addAllTags(a.tags)
                    .addAllRuleIds(a.ruleIds)
                    .addAllReasonCodes(a.reasonCodes)
                    .putAllCustom(
                        a.custom.mapValues { (_, v) -> if (v is JsonPrimitive && v.isString) v.content else v.toString() }
                    )
                a.confidence?.let { audit.setConfidence(it) }
                out.setAudit(audit)
            }
            return out.build()
        }
    }
}
