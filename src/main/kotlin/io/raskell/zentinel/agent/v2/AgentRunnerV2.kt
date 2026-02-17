package io.raskell.zentinel.agent.v2

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import mu.KotlinLogging
import java.io.File
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Transport type for v2 agents.
 */
enum class TransportType {
    /** Unix Domain Socket transport. */
    UDS,
    /** gRPC transport (simulated as TCP for this SDK). */
    GRPC
}

/**
 * Configuration for AgentRunnerV2.
 */
data class AgentRunnerV2Config(
    /** Transport type to use. */
    val transport: TransportType = TransportType.UDS,

    /** Socket path for UDS transport. */
    val socketPath: String = "/tmp/zentinel-agent.sock",

    /** Host for gRPC/TCP transport. */
    val host: String = "127.0.0.1",

    /** Port for gRPC/TCP transport. */
    val port: Int = 50051,

    /** Timeout for requests. */
    val requestTimeout: Duration = 30.seconds,

    /** Timeout for handshake. */
    val handshakeTimeout: Duration = 10.seconds,

    /** Grace period for drain before forced shutdown. */
    val drainTimeout: Duration = 30.seconds,

    /** Maximum concurrent connections. */
    val maxConnections: Int = 100,

    /** Enable keep-alive ping/pong. */
    val enableKeepAlive: Boolean = true,

    /** Keep-alive interval. */
    val keepAliveInterval: Duration = 30.seconds
) {
    companion object {
        /** Create a UDS configuration. */
        fun uds(socketPath: String = "/tmp/zentinel-agent.sock") = AgentRunnerV2Config(
            transport = TransportType.UDS,
            socketPath = socketPath
        )

        /** Create a gRPC/TCP configuration. */
        fun grpc(host: String = "127.0.0.1", port: Int = 50051) = AgentRunnerV2Config(
            transport = TransportType.GRPC,
            host = host,
            port = port
        )
    }
}

/**
 * V2 Agent Runner with support for UDS and gRPC transports.
 *
 * Provides:
 * - Binary UDS protocol with handshake
 * - Request multiplexing
 * - Graceful shutdown and draining
 * - Health check endpoints
 * - Metrics collection
 *
 * Example:
 * ```kotlin
 * fun main() = runBlocking {
 *     val agent = MyAgentV2()
 *     val config = AgentRunnerV2Config.uds("/var/run/my-agent.sock")
 *
 *     AgentRunnerV2(agent, config).run()
 * }
 * ```
 */
class AgentRunnerV2(
    private val agent: AgentV2,
    private val config: AgentRunnerV2Config = AgentRunnerV2Config()
) {
    private val handler = AgentHandlerV2(agent)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(true)
    private val draining = AtomicBoolean(false)
    private val connectionCount = AtomicLong(0)
    private val startTime = Instant.now()

    /**
     * Run the agent server.
     *
     * This method blocks until the server is shut down via shutdown() or SIGINT/SIGTERM.
     */
    suspend fun run() = withContext(Dispatchers.IO) {
        when (config.transport) {
            TransportType.UDS -> runUdsServer()
            TransportType.GRPC -> runGrpcServer()
        }
    }

    /**
     * Request graceful shutdown.
     */
    fun shutdown() {
        running.set(false)
    }

    /**
     * Request draining - stop accepting new connections but complete existing ones.
     */
    suspend fun drain() {
        if (draining.compareAndSet(false, true)) {
            logger.info { "Starting drain..." }
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
    // UDS Server Implementation
    // ========================================================================

    private suspend fun runUdsServer() {
        logger.info { "Starting v2 UDS server on ${config.socketPath}" }

        // Remove existing socket file
        File(config.socketPath).delete()

        val address = UnixDomainSocketAddress.of(config.socketPath)
        val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        serverChannel.bind(address)
        serverChannel.configureBlocking(false)

        // Register shutdown hook
        val shutdownHook = Thread {
            logger.info { "Received shutdown signal" }
            running.set(false)
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        logger.info { "Agent '${agent.name}' listening on ${config.socketPath}" }

        try {
            while (running.get()) {
                val clientChannel = serverChannel.accept()
                if (clientChannel != null) {
                    if (draining.get()) {
                        logger.debug { "Rejecting connection during drain" }
                        clientChannel.close()
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
                } else {
                    delay(10)
                }
            }
        } finally {
            logger.info { "Shutting down agent '${agent.name}'..." }

            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (e: IllegalStateException) {
                // JVM already shutting down
            }

            // Graceful shutdown
            handler.shutdown()
            scope.cancel()
            serverChannel.close()
            File(config.socketPath).delete()

            logger.info { "Agent '${agent.name}' stopped" }
        }
    }

    private suspend fun handleUdsConnection(channel: SocketChannel) {
        val streamId = "uds-${System.nanoTime()}"
        logger.debug { "New UDS connection: $streamId" }

        try {
            // Perform handshake
            val handshakeResult = performHandshake(channel)
            if (!handshakeResult) {
                logger.warn { "Handshake failed for $streamId" }
                return
            }

            // Process messages
            while (channel.isOpen && running.get()) {
                val message = readMessage(channel) ?: break
                val response = processMessage(message)
                if (response != null) {
                    writeMessage(channel, response)
                }
            }
        } catch (e: AsynchronousCloseException) {
            logger.debug { "Connection closed: $streamId" }
        } catch (e: Exception) {
            logger.error(e) { "Error handling connection $streamId" }
            handler.handleStreamClosed(streamId, e)
        } finally {
            try {
                channel.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
            handler.handleStreamClosed(streamId, null)
        }
    }

    private suspend fun performHandshake(channel: SocketChannel): Boolean {
        // Read handshake request
        val requestMsg = withTimeoutOrNull(config.handshakeTimeout) {
            readMessage(channel)
        }

        if (requestMsg == null || requestMsg.type != MessageType.HANDSHAKE_REQUEST) {
            logger.warn { "Invalid or missing handshake request" }
            return false
        }

        val request = protocolV2Json.decodeFromString<HandshakeRequest>(requestMsg.payload)
        val response = handler.handleHandshake(request)

        // Send handshake response
        val responseJson = protocolV2Json.encodeToString(HandshakeResponse.serializer(), response)
        writeMessage(channel, Message(MessageType.HANDSHAKE_RESPONSE, responseJson))

        logger.debug { "Handshake completed with ${request.clientName}" }
        return true
    }

    private suspend fun processMessage(message: Message): Message? {
        return when (message.type) {
            MessageType.REQUEST_HEADERS -> {
                val msg = protocolV2Json.decodeFromString<RequestHeadersV2>(message.payload)
                val decision = handler.handleRequestHeaders(msg)
                val json = protocolV2Json.encodeToString(DecisionMessageV2.serializer(), decision)
                Message(MessageType.DECISION, json)
            }
            MessageType.REQUEST_BODY_CHUNK -> {
                val msg = protocolV2Json.decodeFromString<RequestBodyChunkV2>(message.payload)
                val decision = handler.handleRequestBodyChunk(msg)
                val json = protocolV2Json.encodeToString(DecisionMessageV2.serializer(), decision)
                Message(MessageType.DECISION, json)
            }
            MessageType.RESPONSE_HEADERS -> {
                val msg = protocolV2Json.decodeFromString<ResponseHeadersV2>(message.payload)
                val decision = handler.handleResponseHeaders(msg)
                val json = protocolV2Json.encodeToString(DecisionMessageV2.serializer(), decision)
                Message(MessageType.DECISION, json)
            }
            MessageType.RESPONSE_BODY_CHUNK -> {
                val msg = protocolV2Json.decodeFromString<ResponseBodyChunkV2>(message.payload)
                val decision = handler.handleResponseBodyChunk(msg)
                val json = protocolV2Json.encodeToString(DecisionMessageV2.serializer(), decision)
                Message(MessageType.DECISION, json)
            }
            MessageType.CANCEL_REQUEST -> {
                val msg = protocolV2Json.decodeFromString<CancelRequestMessage>(message.payload)
                handler.handleCancelRequest(msg)
                null // No response for cancel
            }
            MessageType.CANCEL_ALL -> {
                val msg = protocolV2Json.decodeFromString<CancelAllMessage>(message.payload)
                handler.handleCancelAll(msg)
                null
            }
            MessageType.PING -> {
                Message(MessageType.PONG, "{}")
            }
            else -> {
                logger.warn { "Unknown message type: ${message.type}" }
                null
            }
        }
    }

    private data class Message(val type: Byte, val payload: String)

    private fun readMessage(channel: SocketChannel): Message? {
        // Read length prefix (4 bytes, big-endian)
        val lengthBuffer = ByteBuffer.allocate(4)
        var bytesRead = 0
        while (bytesRead < 4) {
            val read = channel.read(lengthBuffer)
            if (read == -1) return null
            bytesRead += read
        }
        lengthBuffer.flip()
        lengthBuffer.order(ByteOrder.BIG_ENDIAN)
        val length = lengthBuffer.int

        if (length > MAX_MESSAGE_SIZE_UDS || length < 1) {
            logger.error { "Invalid message length: $length" }
            return null
        }

        // Read type byte
        val typeBuffer = ByteBuffer.allocate(1)
        if (channel.read(typeBuffer) != 1) return null
        typeBuffer.flip()
        val type = typeBuffer.get()

        // Read payload
        val payloadLength = length - 1
        val payloadBuffer = ByteBuffer.allocate(payloadLength)
        bytesRead = 0
        while (bytesRead < payloadLength) {
            val read = channel.read(payloadBuffer)
            if (read == -1) return null
            bytesRead += read
        }
        payloadBuffer.flip()
        val payload = String(payloadBuffer.array(), 0, payloadLength, StandardCharsets.UTF_8)

        return Message(type, payload)
    }

    private fun writeMessage(channel: SocketChannel, message: Message) {
        val payloadBytes = message.payload.toByteArray(StandardCharsets.UTF_8)
        val totalLength = 1 + payloadBytes.size

        val buffer = ByteBuffer.allocate(4 + totalLength)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(totalLength)
        buffer.put(message.type)
        buffer.put(payloadBytes)
        buffer.flip()

        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    // ========================================================================
    // gRPC Server Implementation (TCP-based simulation)
    // ========================================================================

    private suspend fun runGrpcServer() {
        logger.info { "Starting v2 gRPC server on ${config.host}:${config.port}" }

        val address = InetSocketAddress(config.host, config.port)
        val serverChannel = ServerSocketChannel.open()
        serverChannel.bind(address)
        serverChannel.configureBlocking(false)

        val shutdownHook = Thread {
            logger.info { "Received shutdown signal" }
            running.set(false)
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        logger.info { "Agent '${agent.name}' listening on ${config.host}:${config.port}" }

        try {
            while (running.get()) {
                val clientChannel = serverChannel.accept()
                if (clientChannel != null) {
                    if (draining.get()) {
                        clientChannel.close()
                        continue
                    }

                    if (connectionCount.get() >= config.maxConnections) {
                        clientChannel.close()
                        continue
                    }

                    connectionCount.incrementAndGet()
                    clientChannel.configureBlocking(true)

                    scope.launch {
                        try {
                            handleGrpcConnection(clientChannel)
                        } finally {
                            connectionCount.decrementAndGet()
                        }
                    }
                } else {
                    delay(10)
                }
            }
        } finally {
            logger.info { "Shutting down gRPC agent '${agent.name}'..." }

            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (e: IllegalStateException) {
                // JVM already shutting down
            }

            handler.shutdown()
            scope.cancel()
            serverChannel.close()

            logger.info { "Agent '${agent.name}' stopped" }
        }
    }

    private suspend fun handleGrpcConnection(channel: SocketChannel) {
        val streamId = "grpc-${System.nanoTime()}"
        logger.debug { "New gRPC connection: $streamId" }

        try {
            // For gRPC simulation, use same wire format as UDS
            val handshakeResult = performHandshake(channel)
            if (!handshakeResult) {
                logger.warn { "gRPC handshake failed for $streamId" }
                return
            }

            while (channel.isOpen && running.get()) {
                val message = readMessage(channel) ?: break
                val response = processMessage(message)
                if (response != null) {
                    writeMessage(channel, response)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error handling gRPC connection $streamId" }
            handler.handleStreamClosed(streamId, e)
        } finally {
            try {
                channel.close()
            } catch (e: Exception) {
                // Ignore
            }
            handler.handleStreamClosed(streamId, null)
        }
    }
}

/**
 * Run a v2 agent with command-line argument parsing.
 *
 * Supported arguments:
 * - `--socket=PATH` or `--socket PATH`: Unix socket path
 * - `--host=HOST` or `--host HOST`: TCP host for gRPC
 * - `--port=PORT` or `--port PORT`: TCP port for gRPC
 * - `--transport=uds|grpc`: Transport type
 * - `--log-level=LEVEL`: Log level
 *
 * Example:
 * ```kotlin
 * fun main(args: Array<String>) {
 *     runAgentV2(MyAgentV2(), args)
 * }
 * ```
 */
fun runAgentV2(agent: AgentV2, args: Array<String> = emptyArray()) {
    val transport = args.find { it.startsWith("--transport=") }
        ?.substringAfter("=")
        ?: args.getOrNull(args.indexOf("--transport") + 1)
        ?: "uds"

    val config = when (transport.lowercase()) {
        "grpc", "tcp" -> {
            val host = args.find { it.startsWith("--host=") }?.substringAfter("=")
                ?: args.getOrNull(args.indexOf("--host") + 1)?.takeIf { !it.startsWith("--") }
                ?: "127.0.0.1"

            val port = args.find { it.startsWith("--port=") }?.substringAfter("=")?.toIntOrNull()
                ?: args.getOrNull(args.indexOf("--port") + 1)?.toIntOrNull()
                ?: 50051

            AgentRunnerV2Config.grpc(host, port)
        }
        else -> {
            val socketPath = args.find { it.startsWith("--socket=") }?.substringAfter("=")
                ?: args.getOrNull(args.indexOf("--socket") + 1)?.takeIf { !it.startsWith("--") }
                ?: "/tmp/zentinel-agent.sock"

            AgentRunnerV2Config.uds(socketPath)
        }
    }

    runBlocking {
        AgentRunnerV2(agent, config).run()
    }
}

/**
 * Extension function to run an AgentV2 with default configuration.
 */
suspend fun AgentV2.runV2(config: AgentRunnerV2Config = AgentRunnerV2Config()) {
    AgentRunnerV2(this, config).run()
}
