package io.raskell.zentinel.agent.v2

import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.raskell.zentinel.agent.Decision
import io.raskell.zentinel.agent.Request
import io.raskell.zentinel.agent.v2.grpc.AgentServiceV2Grpc
import io.raskell.zentinel.agent.v2.grpc.AgentToProxy
import io.raskell.zentinel.agent.v2.grpc.ConfigureEvent
import io.raskell.zentinel.agent.v2.grpc.Header
import io.raskell.zentinel.agent.v2.grpc.Ping
import io.raskell.zentinel.agent.v2.grpc.ProxyToAgent
import io.raskell.zentinel.agent.v2.grpc.RequestHeadersEvent
import io.raskell.zentinel.agent.v2.grpc.RequestMetadata
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.raskell.zentinel.agent.v2.grpc.HandshakeRequest as PbHandshakeRequest

/**
 * The runner's transports, driven the way the proxy drives them: a real Unix
 * socket with the v2 framing, and a real gRPC stream.
 */
class WireTest {

    private class Hello : AgentV2 {
        @Volatile
        var configured: JsonObject? = null

        override val name = "wire"

        override suspend fun onConfigure(config: JsonObject) {
            if (config["reject"] != null) throw IllegalArgumentException("rejected")
            configured = config
        }

        override suspend fun onRequest(request: Request): Decision {
            if (request.pathStartsWith("/slow")) delay(300)
            return Decision.allow().addRequestHeader("X-Hello", "from-agent")
        }
    }

    private val dir = Files.createTempDirectory("zentinel-kotlin-wire")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @AfterEach
    fun cleanup() {
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    private fun connect(path: String): SocketChannel {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            try {
                return SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(UnixDomainSocketAddress.of(path)) }
            } catch (e: Exception) {
                if (System.currentTimeMillis() > deadline) throw e
                Thread.sleep(20)
            }
        }
    }

    private fun send(ch: SocketChannel, type: Byte, json: String) =
        AgentRunnerV2.writeFrame(ch, type, json.toByteArray())

    private fun recv(ch: SocketChannel): Pair<Byte, JsonElement> {
        val frame = assertNotNull(AgentRunnerV2.readFrame(ch))
        return frame.type to Json.parseToJsonElement(frame.text())
    }

    private fun headers(cid: String, uri: String) =
        """{"metadata":{"correlation_id":"$cid","request_id":"1","client_ip":"127.0.0.1","client_port":1,""" +
            """"server_name":null,"protocol":"HTTP/1.1","tls_version":null,"tls_cipher":null,"route_id":"r",""" +
            """"upstream_id":null,"timestamp":"t"},"method":"GET","uri":"$uri","headers":{}}"""

    @Test
    fun `uds speaks the proxy's v2 framing end to end`() {
        val socket = dir.resolve("a.sock").toString()
        val agent = Hello()
        val runner = AgentRunnerV2(agent, AgentRunnerV2Config.uds(socket))
        scope.launch { runner.run() }

        connect(socket).use { ch ->
            send(
                ch,
                MessageType.HANDSHAKE_REQUEST,
                """{"supported_versions":[2],"proxy_id":"zentinel","proxy_version":"0.6.50","config":null,"supported_encodings":["json"]}"""
            )
            val (hsType, hs) = recv(ch)
            assertEquals(MessageType.HANDSHAKE_RESPONSE, hsType)
            assertEquals(JsonPrimitive(true), hs.jsonObject["success"])
            assertEquals(JsonPrimitive("wire"), hs.jsonObject["capabilities"]!!.jsonObject["agent_id"])

            // Configure (0x17) is answered, with its correlation id, before anything else.
            send(ch, MessageType.CONFIGURE, """{"config":{"nested":{"value":"ok"}},"config_version":null,"correlation_id":"cfg-1"}""")
            val (cfgType, cfg) = recv(ch)
            assertEquals(MessageType.AGENT_RESPONSE, cfgType)
            assertEquals(JsonPrimitive("allow"), cfg.jsonObject["decision"])
            assertEquals(JsonPrimitive("cfg-1"), cfg.jsonObject["audit"]!!.jsonObject["custom"]!!.jsonObject["correlation_id"])
            assertEquals(JsonPrimitive("ok"), agent.configured!!["nested"]!!.jsonObject["value"])

            send(ch, MessageType.PING, """{"sequence":7}""")
            val (pongType, pong) = recv(ch)
            assertEquals(MessageType.PONG, pongType)
            assertEquals(JsonPrimitive(7), pong.jsonObject["sequence"])

            // Multiplexed: the slow request does not hold up the fast one,
            // and each response names its request.
            send(ch, MessageType.REQUEST_HEADERS, headers("slow", "/slow"))
            send(ch, MessageType.REQUEST_HEADERS, headers("fast", "/fast"))
            val order = (1..2).map {
                val (type, body) = recv(ch)
                assertEquals(MessageType.AGENT_RESPONSE, type)
                assertEquals(
                    Json.parseToJsonElement("""[{"set":{"name":"X-Hello","value":"from-agent"}}]"""),
                    body.jsonObject["request_headers"]
                )
                body.jsonObject["audit"]!!.jsonObject["custom"]!!.jsonObject["correlation_id"]!!.jsonPrimitive.content
            }
            assertEquals(listOf("fast", "slow"), order)
        }
        runner.shutdown()
    }

    @Test
    fun `uds rejected configuration is answered with a block`() {
        val socket = dir.resolve("b.sock").toString()
        val runner = AgentRunnerV2(Hello(), AgentRunnerV2Config.uds(socket))
        scope.launch { runner.run() }
        connect(socket).use { ch ->
            send(ch, MessageType.HANDSHAKE_REQUEST, """{"supported_versions":[2],"proxy_id":"p","proxy_version":"v","config":null}""")
            recv(ch)
            send(ch, MessageType.CONFIGURE, """{"config":{"reject":true},"correlation_id":"cfg-2"}""")
            val (_, cfg) = recv(ch)
            assertTrue(cfg.jsonObject["decision"]!!.jsonObject.containsKey("block"))
        }
        runner.shutdown()
    }

    private fun grpcServer(agent: Hello): Pair<GrpcAgentServerV2, ManagedChannel> {
        val server = GrpcAgentServerV2(AgentHandlerV2(agent), "127.0.0.1", 0).start()
        val channel = NettyChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
        return server to channel
    }

    private class Recorder : ClientCall.Listener<AgentToProxy>() {
        val headers = LinkedBlockingQueue<Metadata>()
        val messages = LinkedBlockingQueue<AgentToProxy>()
        val closed = LinkedBlockingQueue<Status>()
        override fun onHeaders(headers: Metadata) {
            this.headers.add(headers)
        }
        override fun onMessage(message: AgentToProxy) {
            messages.add(message)
        }
        override fun onClose(status: Status, trailers: Metadata) {
            closed.add(status)
        }
    }

    private fun open(channel: ManagedChannel): Pair<ClientCall<ProxyToAgent, AgentToProxy>, Recorder> {
        val call = channel.newCall(AgentServiceV2Grpc.getProcessStreamMethod(), CallOptions.DEFAULT)
        val rec = Recorder()
        call.start(rec, Metadata())
        call.request(Int.MAX_VALUE)
        return call to rec
    }

    @Test
    fun `grpc sends response headers before any message and serves the stream`() {
        val agent = Hello()
        val (server, channel) = grpcServer(agent)
        try {
            val (call, rec) = open(channel)
            // The proxy sends nothing until it has the headers.
            assertNotNull(rec.headers.poll(5, TimeUnit.SECONDS), "response headers were not sent when the stream opened")

            call.sendMessage(
                ProxyToAgent.newBuilder().setHandshake(
                    PbHandshakeRequest.newBuilder().addSupportedVersions(2).setProxyId("p").setProxyVersion("v")
                ).build()
            )
            val hs = rec.messages.poll(5, TimeUnit.SECONDS)!!
            assertTrue(hs.handshake.success)
            assertEquals("wire", hs.handshake.capabilities.agentId)

            call.sendMessage(
                ProxyToAgent.newBuilder().setConfigure(
                    ConfigureEvent.newBuilder().setConfigJson("""{"nested":{"value":"ok"}}""").setIsInitial(true)
                ).build()
            )
            call.sendMessage(ProxyToAgent.newBuilder().setPing(Ping.newBuilder().setSequence(3).setTimestampMs(10)).build())
            val pong = rec.messages.poll(5, TimeUnit.SECONDS)!!
            assertEquals(3, pong.pong.sequence)
            // The Pong means the configuration was applied.
            assertEquals(JsonPrimitive("ok"), agent.configured!!["nested"]!!.jsonObject["value"])

            call.sendMessage(
                ProxyToAgent.newBuilder().setRequestHeaders(
                    RequestHeadersEvent.newBuilder()
                        .setMetadata(RequestMetadata.newBuilder().setCorrelationId("g1").setClientIp("127.0.0.1"))
                        .setMethod("GET").setUri("/x")
                        .addHeaders(Header.newBuilder().setName("host").setValue("h"))
                ).build()
            )
            val resp = rec.messages.poll(5, TimeUnit.SECONDS)!!.response
            assertEquals("g1", resp.correlationId)
            assertTrue(resp.hasAllow())
            assertEquals("X-Hello", resp.getRequestHeaders(0).set.name)
            assertEquals("from-agent", resp.getRequestHeaders(0).set.value)
            call.halfClose()
            assertEquals(Status.Code.OK, rec.closed.poll(5, TimeUnit.SECONDS)!!.code)
        } finally {
            channel.shutdownNow()
            server.stop()
        }
    }

    @Test
    fun `grpc ends the stream when the configuration is rejected`() {
        val (server, channel) = grpcServer(Hello())
        try {
            val (call, rec) = open(channel)
            call.sendMessage(
                ProxyToAgent.newBuilder().setHandshake(PbHandshakeRequest.newBuilder().addSupportedVersions(2)).build()
            )
            rec.messages.poll(5, TimeUnit.SECONDS)!!
            call.sendMessage(
                ProxyToAgent.newBuilder().setConfigure(ConfigureEvent.newBuilder().setConfigJson("""{"reject":true}""")).build()
            )
            call.sendMessage(ProxyToAgent.newBuilder().setPing(Ping.newBuilder().setSequence(1)).build())
            val status = rec.closed.poll(5, TimeUnit.SECONDS)
            assertNotNull(status)
            assertTrue(!status.isOk)
            assertTrue(rec.messages.none { it.hasPong() }, "a rejected configuration must not be ponged")
        } finally {
            channel.shutdownNow()
            server.stop()
        }
    }
}

