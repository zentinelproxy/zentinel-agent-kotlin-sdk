package io.raskell.zentinel.agent.v2

import io.raskell.zentinel.agent.Agent
import io.raskell.zentinel.agent.AgentResponse
import io.raskell.zentinel.agent.EventType
import io.raskell.zentinel.agent.protocolJson
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.io.EOFException
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Transport type for v2 agents.
 */
enum class TransportType {
    /** Unix domain socket, the v2 binary framing. */
    UDS,

    /** gRPC (`zentinel.agent.v2.AgentServiceV2`) over HTTP/2 cleartext. */
    GRPC,

    /** Both at once. */
    BOTH
}

/**
 * Configuration for AgentRunnerV2.
 */
data class AgentRunnerV2Config(
    /** Transport type to use. */
    val transport: TransportType = TransportType.UDS,

    /** Socket path for UDS transport. */
    val socketPath: String = "/tmp/zentinel-agent.sock",

    /** Host for the gRPC transport. */
    val host: String = "127.0.0.1",

    /** Port for the gRPC transport. */
    val port: Int = 50051,

    /** Timeout for the handshake on a new UDS connection. */
    val handshakeTimeout: Duration = 10.seconds,

    /** Grace period for drain before forced shutdown. */
    val drainTimeout: Duration = 30.seconds,

    /** Maximum concurrent UDS connections. */
    val maxConnections: Int = 100
) {
    companion object {
        /** Create a UDS configuration. */
        fun uds(socketPath: String = "/tmp/zentinel-agent.sock") = AgentRunnerV2Config(
            transport = TransportType.UDS,
            socketPath = socketPath
        )

        /** Create a gRPC configuration. */
        fun grpc(host: String = "127.0.0.1", port: Int = 50051) = AgentRunnerV2Config(
            transport = TransportType.GRPC,
            host = host,
            port = port
        )

        /** Serve both the Unix socket and gRPC. */
        fun both(socketPath: String, host: String, port: Int) = AgentRunnerV2Config(
            transport = TransportType.BOTH,
            socketPath = socketPath,
            host = host,
            port = port
        )
    }
}

/**
 * Runs an agent over agent protocol v2, on a Unix socket, gRPC, or both.
 *
 * On the Unix socket every frame is `length (4 bytes, BE) | type | JSON`,
 * with `length` counting the type byte. Per connection the proxy sends a
 * handshake, then a Configure message, then events; it multiplexes requests,
 * so events are handled concurrently and every response carries its
 * request's correlation id in `audit.custom.correlation_id`.
 *
 * Example:
 * ```kotlin
 * fun main() = runBlocking {
 *     AgentRunnerV2(MyAgent(), AgentRunnerV2Config.uds("/var/run/my-agent.sock")).run()
 * }
 * ```
 */
class AgentRunnerV2(
    private val agent: Agent,
    private val config: AgentRunnerV2Config = AgentRunnerV2Config()
) {
    private val handler = AgentHandlerV2(agent)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(true)
    private val draining = AtomicBoolean(false)
    private val connectionCount = AtomicLong(0)

    /**
     * Run the agent server.
     *
     * Suspends until [shutdown] is called or the JVM receives SIGINT/SIGTERM.
     */
    suspend fun run() = withContext(Dispatchers.IO) {
        val shutdownHook = Thread {
            logger.info { "Received shutdown signal" }
            running.set(false)
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        var grpc: GrpcAgentServerV2? = null
        try {
            if (config.transport != TransportType.UDS) {
                grpc = GrpcAgentServerV2(handler, config.host, config.port).start()
                logger.info { "Agent '${agent.name}' listening on gRPC ${config.host}:${grpc.port}" }
            }
            if (config.transport != TransportType.GRPC) {
                runUdsServer()
            } else {
                while (running.get()) delay(100)
            }
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (e: IllegalStateException) {
                // JVM already shutting down
            }
            grpc?.stop()
            handler.shutdown()
            scope.cancel()
            logger.info { "Agent '${agent.name}' stopped" }
        }
    }

    /**
     * Request graceful shutdown.
     */
    fun shutdown() {
        running.set(false)
    }

    /**
     * Request draining - new requests are blocked with 503.
     */
    suspend fun drain() {
        if (draining.compareAndSet(false, true)) {
            handler.startDrain(config.drainTimeout.inWholeMilliseconds)
        }
    }

    /**
     * Get current metrics.
     */
    fun metrics(): MetricsReport = handler.metrics()

    /**
     * Get current health status.
     */
    fun healthStatus(): HealthStatus = handler.healthStatus()

    // ========================================================================
    // UDS server
    // ========================================================================

    private suspend fun runUdsServer() {
        File(config.socketPath).delete()
        File(config.socketPath).absoluteFile.parentFile?.mkdirs()

        val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        serverChannel.bind(UnixDomainSocketAddress.of(config.socketPath))
        serverChannel.configureBlocking(false)
        logger.info { "Agent '${agent.name}' listening on ${config.socketPath}" }

        try {
            while (running.get()) {
                val clientChannel = serverChannel.accept()
                if (clientChannel == null) {
                    delay(10)
                    continue
                }
                if (connectionCount.get() >= config.maxConnections) {
                    logger.warn { "Max connections reached, rejecting" }
                    clientChannel.close()
                    continue
                }
                connectionCount.incrementAndGet()
                clientChannel.configureBlocking(true)
                scope.launch {
                    try {
                        handleUdsConnection(clientChannel)
                    } finally {
                        connectionCount.decrementAndGet()
                    }
                }
            }
        } finally {
            serverChannel.close()
            File(config.socketPath).delete()
        }
    }

    private suspend fun handleUdsConnection(channel: SocketChannel) {
        val streamId = "uds-${System.nanoTime()}"
        val writeLock = Mutex()
        val events = SupervisorJob(scope.coroutineContext[Job])

        suspend fun send(type: Byte, payload: String) {
            writeLock.withLock { writeFrame(channel, type, payload.toByteArray(StandardCharsets.UTF_8)) }
        }

        suspend fun respond(type: EventType, payload: JsonObject) {
            val response = handler.dispatch(type, payload).withCorrelationId(correlationIdOf(payload))
            send(MessageType.AGENT_RESPONSE, protocolJson.encodeToString(AgentResponse.serializer(), response))
        }

        var error: Throwable? = null
        try {
            val first = withTimeoutOrNull(config.handshakeTimeout) {
                runInterruptible { readFrame(channel) }
            } ?: return
            if (first.type != MessageType.HANDSHAKE_REQUEST) {
                logger.warn { "Expected a handshake request, got message type 0x%02x".format(first.type) }
                return
            }
            val request = protocolV2Json.decodeFromString(HandshakeRequest.serializer(), first.text())
            val handshake = handler.handleHandshake(request)
            send(MessageType.HANDSHAKE_RESPONSE, protocolV2Json.encodeToString(HandshakeResponse.serializer(), handshake))
            if (!handshake.success) return

            while (running.get()) {
                val frame = runInterruptible { readFrame(channel) } ?: break
                when (frame.type) {
                    MessageType.PING -> send(MessageType.PONG, frame.text())
                    MessageType.CANCEL -> {
                        val payload = parseObject(frame)
                        val cid = correlationIdOf(payload)
                        scope.launch(events) { handler.handleCancel(cid, payload["reason"]?.toString()) }
                    }
                    MessageType.CONFIGURE -> {
                        // In order: no request on this connection precedes its configuration.
                        respond(EventType.CONFIGURE, parseObject(frame))
                    }
                    else -> {
                        val type = MessageType.eventType(frame.type)
                        if (type == null) {
                            logger.debug { "Ignoring message type 0x%02x".format(frame.type) }
                        } else {
                            val payload = parseObject(frame)
                            scope.launch(events) { respond(type, payload) }
                        }
                    }
                }
            }
        } catch (e: AsynchronousCloseException) {
            // closed during shutdown
        } catch (e: ClosedChannelException) {
            // closed during shutdown
        } catch (e: EOFException) {
            // peer went away mid-frame
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
            logger.error(e) { "Error on UDS connection $streamId" }
        } finally {
            events.cancel()
            try {
                channel.close()
            } catch (e: Exception) {
                // ignore
            }
            handler.handleStreamClosed(streamId, error)
        }
    }

    private fun parseObject(frame: Frame): JsonObject =
        if (frame.payload.isEmpty()) JsonObject(emptyMap()) else protocolJson.parseToJsonElement(frame.text()).jsonObject

    internal class Frame(val type: Byte, val payload: ByteArray) {
        fun text(): String = String(payload, StandardCharsets.UTF_8)
    }

    internal companion object {
        /** Read one frame; null when the peer closed the connection cleanly. */
        fun readFrame(channel: SocketChannel): Frame? {
            val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            if (!readFully(channel, header, allowEof = true)) return null
            header.flip()
            val length = header.int
            if (length < 1 || length > MAX_MESSAGE_SIZE_UDS) {
                throw IllegalStateException("Invalid UDS frame length: $length")
            }
            val body = ByteBuffer.allocate(length)
            readFully(channel, body, allowEof = false)
            val bytes = body.array()
            return Frame(bytes[0], bytes.copyOfRange(1, length))
        }

        private fun readFully(channel: SocketChannel, buffer: ByteBuffer, allowEof: Boolean): Boolean {
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) == -1) {
                    if (allowEof && buffer.position() == 0) return false
                    throw EOFException("Connection closed mid-frame")
                }
            }
            return true
        }

        /** Encode one frame: the length counts the type byte. */
        fun encodeFrame(type: Byte, payload: ByteArray): ByteBuffer {
            require(payload.size + 1 <= MAX_MESSAGE_SIZE_UDS) { "UDS message too large: ${payload.size} bytes" }
            val buffer = ByteBuffer.allocate(5 + payload.size).order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(payload.size + 1)
            buffer.put(type)
            buffer.put(payload)
            buffer.flip()
            return buffer
        }

        fun writeFrame(channel: SocketChannel, type: Byte, payload: ByteArray) {
            val buffer = encodeFrame(type, payload)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
        }
    }
}

/**
 * Run an agent over protocol v2 with command-line argument parsing.
 *
 * - `--socket PATH`: serve the Unix socket at PATH (default `/tmp/zentinel-agent.sock`)
 * - `--grpc HOST:PORT`: serve gRPC on HOST:PORT
 * - both together serve both
 * - `--transport uds|grpc` with `--host`/`--port` is also accepted
 *
 * Every option may also be written `--name=value`.
 */
fun runAgentV2(agent: Agent, args: Array<String> = emptyArray()) {
    fun arg(name: String): String? =
        args.find { it.startsWith("--$name=") }?.substringAfter("=")
            ?: args.indexOf("--$name").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.takeIf { !it.startsWith("--") }

    val socket = arg("socket")
    var grpc = arg("grpc")
    if (grpc == null && arg("transport")?.lowercase() == "grpc") {
        grpc = "${arg("host") ?: "127.0.0.1"}:${arg("port") ?: "50051"}"
    }

    val config = if (grpc != null) {
        val host = grpc.substringBeforeLast(":").removePrefix("[").removeSuffix("]").ifEmpty { "0.0.0.0" }
        val port = grpc.substringAfterLast(":").toInt()
        if (socket != null) AgentRunnerV2Config.both(socket, host, port) else AgentRunnerV2Config.grpc(host, port)
    } else {
        AgentRunnerV2Config.uds(socket ?: "/tmp/zentinel-agent.sock")
    }

    runBlocking {
        AgentRunnerV2(agent, config).run()
    }
}

/**
 * Extension function to run an agent with the given configuration.
 */
suspend fun Agent.runV2(config: AgentRunnerV2Config = AgentRunnerV2Config()) {
    AgentRunnerV2(this, config).run()
}
