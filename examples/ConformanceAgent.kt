package io.raskell.zentinel.agent.examples

// The conformance agent of the Zentinel SDK conformance harness: implements
// the behaviour sdk-conformance/README.md in zentinelproxy/zentinel
// specifies, so the harness can check this SDK against a real proxy.
//
//   java -cp 'build/examples/lib/*' io.raskell.zentinel.agent.examples.ConformanceAgentKt --socket /tmp/c.sock
//   java -cp 'build/examples/lib/*' io.raskell.zentinel.agent.examples.ConformanceAgentKt --grpc 127.0.0.1:50051

import io.raskell.zentinel.agent.Decision
import io.raskell.zentinel.agent.EventType
import io.raskell.zentinel.agent.Request
import io.raskell.zentinel.agent.Response
import io.raskell.zentinel.agent.v2.AgentCapabilities
import io.raskell.zentinel.agent.v2.AgentV2
import io.raskell.zentinel.agent.v2.runAgentV2
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ConformanceAgent : AgentV2 {
    override val name = "conformance"

    @Volatile
    private var config: JsonObject? = null

    override fun capabilities() = AgentCapabilities(
        agentId = "conformance",
        name = "Conformance agent",
        version = "0.3.0",
        supportedEvents = listOf(EventType.REQUEST_HEADERS, EventType.REQUEST_BODY_CHUNK, EventType.RESPONSE_HEADERS)
    )

    override suspend fun onConfigure(config: JsonObject) {
        this.config = config
    }

    private fun configValue(): String {
        val value = (config?.get("nested") as? JsonObject)?.get("value") as? JsonPrimitive
        return if (value != null && value.isString) value.content else "unconfigured"
    }

    override suspend fun onRequest(request: Request): Decision {
        if (request.pathStartsWith("/block")) {
            return Decision.block(403).withBody("blocked-by-agent")
        }
        return Decision.allow()
            .addRequestHeader("X-Hello", "from-agent")
            .addRequestHeader("X-Conformance-Config", configValue())
    }

    override suspend fun onRequestBody(request: Request): Decision {
        val body = request.bodyString() ?: ""
        if ("block-this-body" in body) {
            return Decision.block(403).withBody("body-seen:$body")
        }
        return Decision.allow()
    }

    override suspend fun onResponse(request: Request, response: Response): Decision =
        Decision.allow().addResponseHeader("X-Conformance-Response", "from-agent")
}

fun main(args: Array<String>) {
    runAgentV2(ConformanceAgent(), args)
}
