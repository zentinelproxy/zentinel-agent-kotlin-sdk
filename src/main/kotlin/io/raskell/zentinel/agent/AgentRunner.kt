package io.raskell.zentinel.agent

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import mu.KotlinLogging
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Runner for Zentinel agents.
 *
 * Handles Unix socket server, protocol encoding/decoding, and agent lifecycle.
 *
 * Example:
 * ```kotlin
 * fun main() = runBlocking {
 *     AgentRunner(MyAgent())
 *         .withSocket("/tmp/my-agent.sock")
 *         .run()
 * }
 * ```
 */
class AgentRunner(private val agent: Agent) {
    private var socketPath: String = "/tmp/zentinel-agent.sock"
    private var logLevel: String = "INFO"
    private var jsonLogs: Boolean = false
    private var agentName: String? = null

    private val requestCache = ConcurrentHashMap<String, Request>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var running = true

    /**
     * Set the Unix socket path.
     */
    fun withSocket(path: String): AgentRunner {
        socketPath = path
        return this
    }

    /**
     * Set the log level.
     */
    fun withLogLevel(level: String): AgentRunner {
        logLevel = level
        return this
    }

    /**
     * Enable JSON log output.
     */
    fun withJsonLogs(): AgentRunner {
        jsonLogs = true
        return this
    }

    /**
     * Override the agent name for logging.
     */
    fun withName(name: String): AgentRunner {
        agentName = name
        return this
    }

    /**
     * Get the effective agent name (override or from agent).
     */
    private fun effectiveName(): String = agentName ?: agent.name

    /**
     * Request graceful shutdown.
     */
    fun shutdown() {
        running = false
    }

    /**
     * Run the agent server.
     *
     * This method blocks until the server is shut down.
     * Handles SIGINT and SIGTERM for graceful shutdown.
     */
    suspend fun run() = withContext(Dispatchers.IO) {
        val name = effectiveName()
        logger.info { "Starting agent '$name' on socket $socketPath" }

        // Remove existing socket file
        File(socketPath).delete()

        val address = UnixDomainSocketAddress.of(socketPath)
        val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        serverChannel.bind(address)

        // Configure non-blocking for interrupt handling
        serverChannel.configureBlocking(false)

        // Register shutdown hooks for graceful termination
        val shutdownHook = Thread {
            logger.info { "Received shutdown signal, stopping agent '$name'..." }
            running = false
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        logger.info { "Agent '$name' listening on $socketPath" }

        try {
            while (isActive && running) {
                val clientChannel = serverChannel.accept()
                if (clientChannel != null) {
                    clientChannel.configureBlocking(true)
                    scope.launch {
                        handleClient(clientChannel)
                    }
                } else {
                    // No connection available, sleep briefly to avoid busy-waiting
                    delay(10)
                }
            }
        } finally {
            logger.info { "Shutting down agent '$name'..." }
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (e: IllegalStateException) {
                // JVM is already shutting down
            }
            scope.cancel()
            serverChannel.close()
            File(socketPath).delete()
            logger.info { "Agent '$name' stopped" }
        }
    }

    private suspend fun handleClient(channel: SocketChannel) {
        try {
            while (channel.isOpen) {
                // Read length prefix (4 bytes, big-endian)
                val lengthBuffer = ByteBuffer.allocate(4)
                var bytesRead = 0
                while (bytesRead < 4) {
                    val read = channel.read(lengthBuffer)
                    if (read == -1) return
                    bytesRead += read
                }
                lengthBuffer.flip()
                val length = lengthBuffer.int

                if (length > MAX_MESSAGE_SIZE) {
                    logger.error { "Message too large: $length bytes" }
                    return
                }

                // Read message body
                val messageBuffer = ByteBuffer.allocate(length)
                bytesRead = 0
                while (bytesRead < length) {
                    val read = channel.read(messageBuffer)
                    if (read == -1) return
                    bytesRead += read
                }
                messageBuffer.flip()
                val messageBytes = ByteArray(length)
                messageBuffer.get(messageBytes)
                val json = String(messageBytes, StandardCharsets.UTF_8)

                // Process message
                val request = protocolJson.decodeFromString<AgentRequest>(json)
                val response = handleRequest(request)
                val responseJson = protocolJson.encodeToString(AgentResponse.serializer(), response)

                // Write response with length prefix
                val responseBytes = responseJson.toByteArray(StandardCharsets.UTF_8)
                val responseBuffer = ByteBuffer.allocate(4 + responseBytes.size)
                responseBuffer.putInt(responseBytes.size)
                responseBuffer.put(responseBytes)
                responseBuffer.flip()
                while (responseBuffer.hasRemaining()) {
                    channel.write(responseBuffer)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error handling client" }
        } finally {
            try {
                channel.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
        }
    }

    internal suspend fun handleRequest(request: AgentRequest): AgentResponse {
        return try {
            when (request.eventType) {
                EventType.CONFIGURE -> handleConfigure(request.payload)
                EventType.REQUEST_HEADERS -> handleRequestHeaders(request.payload)
                EventType.REQUEST_BODY_CHUNK -> handleRequestBodyChunk(request.payload)
                EventType.RESPONSE_HEADERS -> handleResponseHeaders(request.payload)
                EventType.RESPONSE_BODY_CHUNK -> handleResponseBodyChunk(request.payload)
                EventType.REQUEST_COMPLETE -> handleRequestComplete(request.payload)
                EventType.GUARDRAIL_INSPECT -> handleGuardrailInspect(request.payload)
                EventType.WEBSOCKET_FRAME -> AgentResponse.defaultAllow()
            }
        } catch (e: Exception) {
            logger.error(e) { "Error handling ${request.eventType}" }
            AgentResponse.block(500, "Agent error: ${e.message}")
        }
    }

    private suspend fun handleConfigure(payload: JsonElement): AgentResponse {
        val event = protocolJson.decodeFromJsonElement<ConfigureEvent>(payload)
        return try {
            agent.onConfigure(event.config)
            AgentResponse.defaultAllow()
        } catch (e: Exception) {
            AgentResponse.block(500, "Configuration error: ${e.message}")
        }
    }

    private suspend fun handleRequestHeaders(payload: JsonElement): AgentResponse {
        val event = protocolJson.decodeFromJsonElement<RequestHeadersEvent>(payload)
        val request = Request.fromEvent(event)

        // Cache the request for later events
        requestCache[event.metadata.correlationId] = request

        val decision = agent.onRequest(request)
        return decision.build()
    }

    private suspend fun handleRequestBodyChunk(payload: JsonElement): AgentResponse {
        val event = protocolJson.decodeFromJsonElement<RequestBodyChunkEvent>(payload)
        val cachedRequest = requestCache[event.correlationId] ?: return AgentResponse.defaultAllow()

        val body = Base64.getDecoder().decode(event.data)
        val requestWithBody = cachedRequest.withBody(body)

        val decision = agent.onRequestBody(requestWithBody)
        return decision.build()
    }

    private suspend fun handleResponseHeaders(payload: JsonElement): AgentResponse {
        val event = protocolJson.decodeFromJsonElement<ResponseHeadersEvent>(payload)
        val cachedRequest = requestCache[event.correlationId] ?: return AgentResponse.defaultAllow()

        val response = Response.fromEvent(event)
        val decision = agent.onResponse(cachedRequest, response)
        return decision.build()
    }

    private suspend fun handleResponseBodyChunk(payload: JsonElement): AgentResponse {
        val event = protocolJson.decodeFromJsonElement<ResponseBodyChunkEvent>(payload)
        val cachedRequest = requestCache[event.correlationId] ?: return AgentResponse.defaultAllow()

        val body = Base64.getDecoder().decode(event.data)
        val response = Response.fromEvent(
            ResponseHeadersEvent(
                correlationId = event.correlationId,
                status = 200,
                headers = emptyMap()
            )
        ).withBody(body)

        val decision = agent.onResponseBody(cachedRequest, response)
        return decision.build()
    }

    private suspend fun handleRequestComplete(payload: JsonElement): AgentResponse {
        val event = protocolJson.decodeFromJsonElement<RequestCompleteEvent>(payload)
        val cachedRequest = requestCache.remove(event.correlationId)

        if (cachedRequest != null) {
            agent.onRequestComplete(cachedRequest, event.status, event.durationMs)
        }

        return AgentResponse.defaultAllow()
    }

    private suspend fun handleGuardrailInspect(payload: JsonElement): AgentResponse {
        val event = protocolJson.decodeFromJsonElement<GuardrailInspectEvent>(payload)
        val response = agent.onGuardrailInspect(event)

        // Convert GuardrailResponse to a JSON-compatible AgentResponse with custom audit
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
}

/**
 * Run an agent with CLI argument parsing.
 *
 * Supported arguments:
 * - `--socket=PATH` or `--socket PATH`: Unix socket path (default: /tmp/zentinel-agent.sock)
 * - `--log-level=LEVEL` or `--log-level LEVEL`: Log level (default: INFO)
 * - `--json-logs`: Enable JSON log output
 * - `--name=NAME` or `--name NAME`: Override agent name for logging
 *
 * Example:
 * ```kotlin
 * fun main(args: Array<String>) {
 *     runAgent(MyAgent(), args)
 * }
 * ```
 */
fun runAgent(agent: Agent, args: Array<String> = emptyArray()) {
    val socketPath = args.find { it.startsWith("--socket=") }?.substringAfter("=")
        ?: args.getOrNull(args.indexOf("--socket") + 1)?.takeIf { !it.startsWith("--") }
        ?: "/tmp/zentinel-agent.sock"

    val logLevel = args.find { it.startsWith("--log-level=") }?.substringAfter("=")
        ?: args.getOrNull(args.indexOf("--log-level") + 1)?.takeIf { !it.startsWith("--") }
        ?: "INFO"

    val agentName = args.find { it.startsWith("--name=") }?.substringAfter("=")
        ?: args.getOrNull(args.indexOf("--name") + 1)?.takeIf { !it.startsWith("--") }

    val jsonLogs = args.contains("--json-logs")

    runBlocking {
        AgentRunner(agent)
            .withSocket(socketPath)
            .withLogLevel(logLevel)
            .apply { if (jsonLogs) withJsonLogs() }
            .apply { agentName?.let { withName(it) } }
            .run()
    }
}
