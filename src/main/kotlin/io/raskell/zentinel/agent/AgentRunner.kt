package io.raskell.zentinel.agent

import io.raskell.zentinel.agent.v2.AgentRunnerV2
import io.raskell.zentinel.agent.v2.AgentRunnerV2Config
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Runner for plain [Agent]s on a Unix socket.
 *
 * It used to speak the v1 framing (length plus JSON, no message type), which
 * the proxy no longer understands. It is now a thin wrapper over
 * [AgentRunnerV2], so every agent speaks protocol v2.
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
    private var runner: AgentRunnerV2? = null

    /** Set the Unix socket path. */
    fun withSocket(path: String): AgentRunner = apply { socketPath = path }

    /** Set the log level (kept for compatibility; configure logback instead). */
    fun withLogLevel(level: String): AgentRunner = apply { logLevel = level }

    /** Enable JSON log output (kept for compatibility; configure logback instead). */
    fun withJsonLogs(): AgentRunner = apply { jsonLogs = true }

    /** Override the agent name for logging. */
    fun withName(name: String): AgentRunner = apply { agentName = name }

    /** Request graceful shutdown. */
    fun shutdown() {
        runner?.shutdown()
    }

    /**
     * Run the agent server. Suspends until shut down (SIGINT/SIGTERM or [shutdown]).
     */
    suspend fun run() {
        logger.info { "Starting agent '${agentName ?: agent.name}' on socket $socketPath" }
        val r = AgentRunnerV2(agent, AgentRunnerV2Config.uds(socketPath))
        runner = r
        r.run()
    }
}

/**
 * Run an agent with CLI argument parsing.
 *
 * Supported arguments:
 * - `--socket=PATH` or `--socket PATH`: Unix socket path (default: /tmp/zentinel-agent.sock)
 * - `--grpc HOST:PORT`: serve gRPC instead (or as well, with `--socket`)
 * - `--log-level`, `--json-logs`, `--name`: accepted for compatibility
 *
 * Example:
 * ```kotlin
 * fun main(args: Array<String>) {
 *     runAgent(MyAgent(), args)
 * }
 * ```
 */
fun runAgent(agent: Agent, args: Array<String> = emptyArray()) {
    io.raskell.zentinel.agent.v2.runAgentV2(agent, args)
}
