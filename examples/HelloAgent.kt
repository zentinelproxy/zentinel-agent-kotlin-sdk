package io.raskell.zentinel.agent.examples

// The README quick-start agent: adds X-Hello and allows. Kept identical to
// the README (apart from the package and taking the socket path as an
// argument), so CI can run it through the conformance harness:
//
//   java -cp 'build/examples/lib/*' io.raskell.zentinel.agent.examples.HelloAgentKt /tmp/hello.sock

import io.raskell.zentinel.agent.Decision
import io.raskell.zentinel.agent.Request
import io.raskell.zentinel.agent.v2.AgentRunnerV2
import io.raskell.zentinel.agent.v2.AgentRunnerV2Config
import io.raskell.zentinel.agent.v2.AgentV2
import kotlinx.coroutines.runBlocking

class HelloAgent : AgentV2 {
    override val name = "hello"

    override suspend fun onRequest(request: Request): Decision =
        Decision.allow().addRequestHeader("X-Hello", "from-agent")
}

fun main(args: Array<String>) = runBlocking {
    val socketPath = args.firstOrNull() ?: "/tmp/hello.sock"
    AgentRunnerV2(HelloAgent(), AgentRunnerV2Config.uds(socketPath)).run()
}
