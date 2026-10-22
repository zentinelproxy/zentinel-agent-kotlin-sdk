package io.raskell.zentinel.agent.integration

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * The SDK against a real Zentinel, through the shared conformance harness.
 *
 * The harness (`sdk-conformance/run.py` in zentinelproxy/zentinel) starts a
 * `zentinel` binary in front of an echo upstream and checks over HTTP that
 * the agents in `examples/` speak agent protocol v2: handshake, configure,
 * header mutations, block, body inspection, reconnect. CI runs it through the
 * harness's GitHub Action; this test runs the same thing locally:
 *
 *     ZENTINEL_BIN=/path/to/zentinel \
 *     ZENTINEL_CONFORMANCE_HARNESS=/path/to/zentinel/sdk-conformance/run.py \
 *     ./gradlew test --tests '*ConformanceTest*'
 *
 * Both variables are required; without them the tests are skipped.
 */
class ConformanceTest {
    private val harness = System.getenv("ZENTINEL_CONFORMANCE_HARNESS")
    private val zentinel = System.getenv("ZENTINEL_BIN")
    private val lib = System.getProperty("zentinel.examples.lib") ?: "build/examples/lib"
    private val java = File(System.getProperty("java.home"), "bin/java").path

    private fun example(mainClass: String) =
        "'$java' -cp '$lib/*' io.raskell.zentinel.agent.examples.$mainClass"

    private fun harness(vararg args: String) {
        assumeTrue(
            !harness.isNullOrEmpty() && !zentinel.isNullOrEmpty(),
            "set ZENTINEL_BIN and ZENTINEL_CONFORMANCE_HARNESS to run against a real proxy"
        )
        val process = ProcessBuilder(listOf("python3", harness, "--zentinel-bin", zentinel) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(10, TimeUnit.MINUTES)
        println(output)
        assertEquals(0, process.exitValue(), output)
    }

    @Test
    fun `conformance agent over UDS and gRPC`() = harness(
        "--agent-cmd", example("ConformanceAgentKt") + " --socket {socket}",
        "--grpc-agent-cmd", example("ConformanceAgentKt") + " --grpc {grpc}"
    )

    @Test
    fun `README hello agent`() = harness(
        "--profile", "hello",
        "--agent-cmd", example("HelloAgentKt") + " {socket}"
    )
}
