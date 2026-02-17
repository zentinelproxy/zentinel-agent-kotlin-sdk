# Getting Started with Zentinel Agent Kotlin SDK

This guide will walk you through creating your first Zentinel agent in Kotlin.

## Prerequisites

- Java 21 or later
- Gradle 8.5 or later
- A running Zentinel proxy instance (or just the SDK for development)

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.raskell.zentinel:zentinel-agent-sdk:0.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
}
```

## Your First Agent

Create a new file `Main.kt`:

```kotlin
import io.raskell.zentinel.agent.*
import kotlinx.coroutines.runBlocking

class MyAgent : Agent {
    override val name = "my-agent"

    override suspend fun onRequest(request: Request): Decision {
        // Block requests to /admin paths
        if (request.pathStartsWith("/admin")) {
            return Decision.deny().withBody("Access denied")
        }

        // Allow all other requests
        return Decision.allow()
    }
}

fun main(args: Array<String>) {
    runAgent(MyAgent(), args)
}
```

## Running Your Agent

```bash
./gradlew run --args="--socket /tmp/my-agent.sock"
```

Your agent is now listening on `/tmp/my-agent.sock` and ready to receive events from Zentinel.

## Understanding the Agent Interface

The `Agent` interface defines the hooks you can implement:

```kotlin
interface Agent {
    val name: String

    suspend fun onConfigure(config: JsonObject) {}
    suspend fun onRequest(request: Request): Decision = Decision.allow()
    suspend fun onRequestBody(request: Request): Decision = Decision.allow()
    suspend fun onResponse(request: Request, response: Response): Decision = Decision.allow()
    suspend fun onResponseBody(request: Request, response: Response): Decision = Decision.allow()
    suspend fun onRequestComplete(request: Request, status: Int, durationMs: Long) {}
}
```

All methods have default implementations, so you only need to override the ones you need.

## Making Decisions

The `Decision` builder provides a fluent API:

```kotlin
// Allow the request
Decision.allow()

// Block with 403 Forbidden
Decision.deny()

// Block with custom status
Decision.block(429).withBody("Too many requests")

// Redirect
Decision.redirect("/login")
Decision.redirect("/new-path", 301)

// Allow with header modifications
Decision.allow()
    .addRequestHeader("X-User-ID", "12345")
    .addResponseHeader("X-Cache", "HIT")
    .removeResponseHeader("Server")

// Add audit metadata
Decision.deny()
    .withTag("security")
    .withRuleId("ADMIN-001")
    .withMetadata("reason", JsonPrimitive("blocked by rule"))
```

## Working with Requests

The `Request` type provides convenient methods:

```kotlin
override suspend fun onRequest(request: Request): Decision {
    // Path inspection
    val path = request.path()
    if (request.pathStartsWith("/api/")) { /* ... */ }
    if (request.pathEquals("/health")) { /* ... */ }

    // Headers (case-insensitive)
    val auth = request.header("Authorization")
    val userAgent = request.userAgent()
    val contentType = request.contentType()

    // Request metadata
    val clientIp = request.clientIp()
    val method = request.method()
    val correlationId = request.correlationId()

    return Decision.allow()
}
```

## Working with Responses

Inspect upstream responses:

```kotlin
override suspend fun onResponse(request: Request, response: Response): Decision {
    // Check status code
    if (response.statusCode() >= 500) {
        return Decision.allow().withTag("upstream-error")
    }

    // Inspect headers
    val contentType = response.header("Content-Type")

    // Add security headers
    return Decision.allow()
        .addResponseHeader("X-Frame-Options", "DENY")
        .addResponseHeader("X-Content-Type-Options", "nosniff")
}
```

## Typed Configuration

For agents with configuration, use the `ConfigurableAgent` interface:

```kotlin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class MyConfig(
    val rateLimit: Int = 100,
    val enabled: Boolean = true
)

class MyAgent : ConfigurableAgent<MyConfig> {
    override val name = "my-configurable-agent"

    private var config = MyConfig()

    override fun config(): MyConfig = config

    override suspend fun onConfigure(rawConfig: JsonObject) {
        config = protocolJson.decodeFromJsonElement(MyConfig.serializer(), rawConfig)
        println("Config updated: rate_limit=${config.rateLimit}")
    }

    override suspend fun onRequest(request: Request): Decision {
        if (!config.enabled) {
            return Decision.allow()
        }
        // Use config.rateLimit...
        return Decision.allow()
    }
}
```

## Connecting to Zentinel

Configure Zentinel to use your agent:

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

## CLI Options

The SDK provides built-in CLI argument parsing:

```bash
# Basic usage
./gradlew run --args="--socket /tmp/my-agent.sock"

# With options
./gradlew run --args="--socket /tmp/my-agent.sock --log-level DEBUG --json-logs"
```

| Option | Description | Default |
|--------|-------------|---------|
| `--socket PATH` | Unix socket path | `/tmp/zentinel-agent.sock` |
| `--log-level LEVEL` | DEBUG, INFO, WARN, ERROR | `INFO` |
| `--json-logs` | Output logs as JSON | disabled |

## Request Logging

Use `onRequestComplete` for logging and metrics:

```kotlin
override suspend fun onRequestComplete(request: Request, status: Int, durationMs: Long) {
    println("${request.clientIp()} - ${request.method()} ${request.path()} -> $status (${durationMs}ms)")
}
```

## Error Handling

Return appropriate decisions for errors:

```kotlin
override suspend fun onRequest(request: Request): Decision {
    val token = request.header("Authorization")
    if (token == null) {
        return Decision.unauthorized()
            .withBody("Authorization header required")
            .withTag("auth-missing")
    }

    val userId = try {
        validateToken(token)
    } catch (e: Exception) {
        return Decision.unauthorized()
            .withBody("Invalid token")
            .withTag("auth-failed")
            .withMetadata("error", JsonPrimitive(e.message))
    }

    return Decision.allow().addRequestHeader("X-User-ID", userId)
}
```

## Testing Your Agent

Write unit tests for your agent:

```kotlin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

class MyAgentTest {
    @Test
    fun `blocks admin path`() = runBlocking {
        val agent = MyAgent()
        val request = Request.builder()
            .withPath("/admin/users")
            .build()

        val decision = agent.onRequest(request)

        assertFalse(decision.isAllow())
    }
}
```

## Next Steps

- Read the [API Reference](api.md) for complete documentation
- Browse [Examples](examples.md) for common patterns
- See the [Configuration](configuration.md) guide for Zentinel setup

## Need Help?

- [GitHub Issues](https://github.com/zentinelproxy/zentinel-agent-kotlin-sdk/issues)
- [Zentinel Documentation](https://zentinelproxy.io/docs)
