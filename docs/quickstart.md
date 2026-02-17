# Quickstart Guide

This guide will help you create your first Zentinel agent in under 5 minutes.

## Prerequisites

- Java 21+
- Gradle 8.5+ (or use the wrapper)
- Zentinel proxy (for testing with real traffic)

## Step 1: Create a New Project

```bash
mkdir my-agent
cd my-agent
gradle init --type kotlin-application
```

Or use the Gradle wrapper if available.

## Step 2: Add Dependencies

Update `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    application
}

dependencies {
    implementation("io.raskell.zentinel:zentinel-agent-sdk:0.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
}

application {
    mainClass.set("MainKt")
}

kotlin {
    jvmToolchain(21)
}
```

## Step 3: Create Your Agent

Create `src/main/kotlin/Main.kt`:

```kotlin
import io.raskell.zentinel.agent.*
import kotlinx.coroutines.runBlocking

class MyAgent : Agent {
    override val name = "my-agent"

    override suspend fun onRequest(request: Request): Decision {
        // Log the request
        println("Processing: ${request.method()} ${request.path()}")

        // Block requests to sensitive paths
        if (request.pathStartsWith("/admin")) {
            return Decision.deny()
                .withBody("Access denied")
                .withTag("blocked")
        }

        // Allow with a custom header
        return Decision.allow()
            .addRequestHeader("X-Processed-By", "my-agent")
    }
}

fun main(args: Array<String>) {
    runAgent(MyAgent(), args)
}
```

## Step 4: Run the Agent

```bash
./gradlew run --args="--socket /tmp/my-agent.sock --log-level debug"
```

You should see:

```
[my-agent] INFO: Agent 'my-agent' listening on /tmp/my-agent.sock
```

## Step 5: Configure Zentinel

Add the agent to your Zentinel configuration (`zentinel.kdl`):

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
        timeout-ms 100
        failure-mode "open"
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

## Step 6: Test It

With Zentinel running, send a test request:

```bash
# This should pass through
curl http://localhost:8080/api/users

# This should be blocked
curl http://localhost:8080/api/admin/settings
```

## Command Line Options

The `runAgent` function supports these CLI arguments:

| Option | Description | Default |
|--------|-------------|---------|
| `--socket PATH` | Unix socket path | `/tmp/zentinel-agent.sock` |
| `--log-level LEVEL` | Log level (DEBUG, INFO, WARN, ERROR) | `INFO` |
| `--json-logs` | Enable JSON log format | disabled |

## Next Steps

- Read the [API Reference](api.md) for complete documentation
- See [Examples](examples.md) for common patterns
- Learn about [Zentinel Configuration](configuration.md) options
