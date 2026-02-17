<div align="center">

<h1 align="center">
  Zentinel Agent Kotlin SDK
</h1>

<p align="center">
  <em>Build agents that extend Zentinel's security and policy capabilities.</em><br>
  <em>Inspect, block, redirect, and transform HTTP traffic.</em>
</p>

<p align="center">
  <a href="https://kotlinlang.org/">
    <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-1.9+-7f52ff?logo=kotlin&logoColor=white&style=for-the-badge">
  </a>
  <a href="https://github.com/zentinelproxy/zentinel">
    <img alt="Zentinel" src="https://img.shields.io/badge/Built%20for-Zentinel-f5a97f?style=for-the-badge">
  </a>
  <a href="LICENSE">
    <img alt="License" src="https://img.shields.io/badge/License-Apache--2.0-c6a0f6?style=for-the-badge">
  </a>
</p>

<p align="center">
  <a href="#quick-start">Quickstart</a> •
  <a href="#core-concepts">API Reference</a> •
  <a href="#examples">Examples</a>
</p>

</div>

---

The Zentinel Agent Kotlin SDK provides an idiomatic, coroutine-based API for building agents that integrate with the [Zentinel](https://github.com/zentinelproxy/zentinel) reverse proxy. Agents can inspect requests and responses, block malicious traffic, add headers, and attach audit metadata—all from Kotlin.

## Quick Start

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.raskell.zentinel:zentinel-agent-kotlin-sdk:0.1.0")
}
```

Create `MyAgent.kt`:

```kotlin
import io.raskell.zentinel.agent.*

class MyAgent : Agent {
    override val name = "my-agent"

    override suspend fun onRequest(request: Request): Decision {
        if (request.pathStartsWith("/admin")) {
            return Decision.deny().withBody("Access denied")
        }
        return Decision.allow()
    }
}

fun main(args: Array<String>) {
    runAgent(MyAgent(), args)
}
```

Run the agent:

```bash
./gradlew run --args="--socket /tmp/my-agent.sock"
```

## Features

| Feature | Description |
|---------|-------------|
| **Simple Agent API** | Implement `onRequest`, `onResponse`, and other hooks |
| **Fluent Decision Builder** | Chain methods: `Decision.deny().withBody(...).withTag(...)` |
| **Request/Response Wrappers** | Ergonomic access to headers, body, query params, metadata |
| **Typed Configuration** | `ConfigurableAgent<T>` interface with JSON parsing |
| **Coroutine Native** | Built on Kotlin coroutines for async processing |
| **Protocol Compatible** | Full compatibility with Zentinel agent protocol v1 |

## Why Agents?

Zentinel's agent system moves complex logic **out of the proxy core** and into isolated, testable, independently deployable processes:

- **Security isolation** — WAF engines, auth validation, and custom logic run in separate processes
- **Language flexibility** — Write agents in Python, Rust, Go, Kotlin, or any language
- **Independent deployment** — Update agent logic without restarting the proxy
- **Failure boundaries** — Agent crashes don't take down the dataplane

Agents communicate with Zentinel over Unix sockets using a simple length-prefixed JSON protocol.

## Architecture

```
┌─────────────┐         ┌──────────────┐         ┌──────────────┐
│   Client    │────────▶│   Zentinel   │────────▶│   Upstream   │
└─────────────┘         └──────────────┘         └──────────────┘
                               │
                               │ Unix Socket (JSON)
                               ▼
                        ┌──────────────┐
                        │    Agent     │
                        │   (Kotlin)   │
                        └──────────────┘
```

1. Client sends request to Zentinel
2. Zentinel forwards request headers to agent
3. Agent returns decision (allow, block, redirect) with optional header mutations
4. Zentinel applies the decision
5. Agent can also inspect response headers before they reach the client

---

## Core Concepts

### Agent

The `Agent` interface defines the hooks you can implement:

```kotlin
import io.raskell.zentinel.agent.*

class MyAgent : Agent {
    // Required: Agent identifier for logging
    override val name = "my-agent"

    // Called when request headers arrive
    override suspend fun onRequest(request: Request): Decision {
        return Decision.allow()
    }

    // Called when request body is available (if body inspection enabled)
    override suspend fun onRequestBody(request: Request): Decision {
        return Decision.allow()
    }

    // Called when response headers arrive from upstream
    override suspend fun onResponse(request: Request, response: Response): Decision {
        return Decision.allow()
    }

    // Called when response body is available (if body inspection enabled)
    override suspend fun onResponseBody(request: Request, response: Response): Decision {
        return Decision.allow()
    }

    // Called when request processing completes. Use for logging/metrics
    override suspend fun onRequestComplete(request: Request, status: Int, durationMs: Long) {
    }
}
```

### Request

Access HTTP request data with convenience methods:

```kotlin
override suspend fun onRequest(request: Request): Decision {
    // Path matching
    if (request.pathStartsWith("/api/")) {
        // ...
    }
    if (request.pathEquals("/health")) {
        return Decision.allow()
    }

    // Headers (case-insensitive)
    val auth = request.header("authorization")
    if (!request.hasHeader("x-api-key")) {
        return Decision.unauthorized()
    }

    // Common headers as properties
    val host = request.host
    val userAgent = request.userAgent
    val contentType = request.contentType

    // Query parameters
    val page = request.query("page")
    val tags = request.queryAll("tag")

    // Request metadata
    val clientIp = request.clientIp
    val correlationId = request.correlationId

    // Body (when body inspection is enabled)
    request.body()?.let { body ->
        val data = request.bodyString()
        // Or parse JSON
        val payload: Map<String, Any>? = request.bodyJson()
    }

    return Decision.allow()
}
```

### Response

Inspect upstream responses before they reach the client:

```kotlin
override suspend fun onResponse(request: Request, response: Response): Decision {
    // Status code
    if (response.statusCode >= 500) {
        return Decision.allow().withTag("upstream-error")
    }

    // Headers
    val contentType = response.header("content-type")

    // Add security headers to all responses
    return Decision.allow()
        .addResponseHeader("X-Frame-Options", "DENY")
        .addResponseHeader("X-Content-Type-Options", "nosniff")
        .removeResponseHeader("Server")
}
```

### Decision

Build responses with a fluent API:

```kotlin
// Allow the request
Decision.allow()

// Block with common status codes
Decision.deny()           // 403 Forbidden
Decision.unauthorized()   // 401 Unauthorized
Decision.rateLimited()    // 429 Too Many Requests
Decision.block(503)       // Custom status

// Block with response body
Decision.deny().withBody("Access denied")
Decision.block(400).withJsonBody(mapOf("error" to "Invalid request"))

// Redirect
Decision.redirect("/login")                    // 302 temporary
Decision.redirectPermanent("/new-path")        // 301 permanent

// Modify headers
Decision.allow()
    .addRequestHeader("X-User-ID", userId)
    .removeRequestHeader("Cookie")
    .addResponseHeader("X-Cache", "HIT")
    .removeResponseHeader("X-Powered-By")

// Audit metadata (appears in Zentinel logs)
Decision.deny()
    .withTag("blocked")
    .withRuleId("SQLI-001")
    .withConfidence(0.95f)
    .withReasonCode("MALICIOUS_PAYLOAD")
    .withMetadata("matched_pattern", pattern)

// Routing metadata for upstream selection
Decision.allow()
    .withRoutingMetadata("upstream", "backend-v2")

// Request more data before deciding
Decision.allow().needsMoreData()

// Body mutations
Decision.allow()
    .withRequestBodyMutation(BodyMutation.replace(0, modifiedBody))
    .withResponseBodyMutation(BodyMutation.dropChunk(1))
```

### ConfigurableAgent

For agents with typed configuration:

```kotlin
import io.raskell.zentinel.agent.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class RateLimitConfig(
    val requestsPerMinute: Int = 60,
    val enabled: Boolean = true
)

class RateLimitAgent : ConfigurableAgent<RateLimitConfig> {
    override val name = "rate-limiter"
    override var config = RateLimitConfig()

    override fun parseConfig(json: JsonObject): RateLimitConfig {
        return protocolJson.decodeFromJsonElement(
            RateLimitConfig.serializer(), json
        )
    }

    override suspend fun onConfigApplied(config: RateLimitConfig) {
        println("Rate limit set to ${config.requestsPerMinute}/min")
    }

    override suspend fun onRequest(request: Request): Decision {
        if (!config.enabled) return Decision.allow()
        // Use config.requestsPerMinute...
        return Decision.allow()
    }
}
```

---

## Running Agents

### Command Line

The `runAgent` helper parses CLI arguments:

```bash
# Basic usage
./gradlew run --args="--socket /tmp/my-agent.sock"

# With options
./gradlew run --args="--socket /tmp/my-agent.sock --log-level DEBUG --json-logs"
```

| Option | Description | Default |
|--------|-------------|---------|
| `--socket PATH` | Unix socket path | `/tmp/zentinel-agent.sock` |
| `--log-level LEVEL` | TRACE, DEBUG, INFO, WARN, ERROR | `INFO` |
| `--json-logs` | Output logs as JSON | disabled |

### Programmatic

```kotlin
import io.raskell.zentinel.agent.AgentRunner
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    AgentRunner(MyAgent())
        .withSocket("/tmp/my-agent.sock")
        .withLogLevel("DEBUG")
        .withJsonLogs()
        .run()
}
```

---

## Zentinel Configuration

Configure Zentinel to connect to your agent:

```kdl
agents {
    agent "my-agent" type="custom" {
        unix-socket path="/tmp/my-agent.sock"
        events "request_headers"
        timeout-ms 100
        failure-mode "open"
    }
}

filters {
    filter "my-filter" {
        type "agent"
        agent "my-agent"
    }
}

routes {
    route "api" {
        matches {
            path-prefix "/api/"
        }
        upstream "backend"
        filters "my-filter"
    }
}
```

### Configuration Options

| Option | Description | Default |
|--------|-------------|---------|
| `unix-socket path="..."` | Path to agent's Unix socket | required |
| `events` | Events to send: `request_headers`, `request_body`, `response_headers`, `response_body` | `request_headers` |
| `timeout-ms` | Timeout for agent calls | `1000` |
| `failure-mode` | `"open"` (allow on failure) or `"closed"` (block on failure) | `"open"` |

---

## Examples

The `examples/` directory contains complete, runnable examples:

| Example | Description |
|---------|-------------|
| [`SimpleAgent.kt`](examples/SimpleAgent.kt) | Basic request blocking and header modification |
| [`ConfigurableAgent.kt`](examples/ConfigurableAgent.kt) | Rate limiting with typed configuration |
| [`BodyInspectionAgent.kt`](examples/BodyInspectionAgent.kt) | Request and response body inspection |

---

## Development

This project uses [mise](https://mise.jdx.dev/) for tool management.

```bash
# Install tools
mise install

# Build
./gradlew build

# Run tests
./gradlew test

# Run tests with output
./gradlew test --info

# Check code style
./gradlew ktlintCheck

# Build documentation
./gradlew dokkaHtml
```

### Without mise

```bash
# Requires Java 21+
./gradlew build
./gradlew test
```

### Project Structure

```
zentinel-agent-kotlin-sdk/
├── src/
│   ├── main/kotlin/io/raskell/zentinel/agent/
│   │   ├── Protocol.kt       # Wire protocol types
│   │   ├── Request.kt        # Request wrapper
│   │   ├── Response.kt       # Response wrapper
│   │   ├── Decision.kt       # Decision builder
│   │   ├── Agent.kt          # Agent interface
│   │   └── AgentRunner.kt    # Runner and CLI
│   └── test/kotlin/io/raskell/zentinel/agent/
│       ├── DecisionTest.kt
│       ├── RequestTest.kt
│       ├── ResponseTest.kt
│       ├── ProtocolTest.kt
│       └── AgentTest.kt
├── examples/                  # Example agents
├── build.gradle.kts
└── mise.toml
```

---

## Protocol

This SDK implements Zentinel Agent Protocol v1:

- **Transport**: Unix domain sockets
- **Encoding**: Length-prefixed JSON (4-byte big-endian length prefix)
- **Max message size**: 10 MB
- **Events**: `configure`, `request_headers`, `request_body_chunk`, `response_headers`, `response_body_chunk`, `request_complete`
- **Decisions**: `allow`, `block`, `redirect`, `challenge`

The protocol is designed for low latency and high throughput, with support for streaming body inspection.

---

## Community

- [Issues](https://github.com/zentinelproxy/zentinel-agent-kotlin-sdk/issues) — Bug reports and feature requests
- [Zentinel Discussions](https://github.com/zentinelproxy/zentinel/discussions) — Questions and ideas
- [Zentinel Documentation](https://zentinelproxy.io/docs) — Proxy documentation

Contributions welcome. Please open an issue to discuss significant changes before submitting a PR.

---

## License

Apache 2.0 — See [LICENSE](LICENSE).
