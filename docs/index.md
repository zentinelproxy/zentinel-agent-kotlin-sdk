# Zentinel Agent Kotlin SDK

A Kotlin SDK for building agents that integrate with the [Zentinel](https://github.com/zentinelproxy/zentinel) reverse proxy.

## Overview

Zentinel agents are external processors that can inspect and modify HTTP traffic passing through the Zentinel proxy. They communicate with Zentinel over Unix sockets (UDS) or gRPC using the v2 agent protocol.

Agents can:

- **Inspect requests** - Examine headers, paths, query parameters, and body content
- **Block requests** - Return custom error responses (403, 401, 429, etc.)
- **Redirect requests** - Send clients to different URLs
- **Modify headers** - Add, remove, or modify request/response headers
- **Add audit metadata** - Attach tags, rule IDs, and custom data for logging

## Installation

The SDK is not on Maven Central. Build it and publish it to your local Maven
repository first (`./gradlew publishToMavenLocal` in a clone of this
repository, see the [README](../README.md#install)), then add `mavenLocal()`
to your repositories and add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.raskell.zentinel:zentinel-agent-kotlin-sdk:0.3.0")
}
```

For Maven, the coordinates are `io.raskell.zentinel:zentinel-agent-kotlin-sdk:0.3.0`, also from the local repository.

## Quick Example

```kotlin
package com.example

import io.raskell.zentinel.agent.*
import kotlinx.coroutines.runBlocking

class MyAgent : Agent {
    override val name = "my-agent"

    override suspend fun onRequest(request: Request): Decision {
        // Block requests to /admin
        if (request.pathStartsWith("/admin")) {
            return Decision.deny().withBody("Access denied")
        }

        // Allow everything else
        return Decision.allow()
    }
}

fun main() = runBlocking {
    AgentRunner(MyAgent())
        .withSocket("/tmp/my-agent.sock")
        .run()
}
```

Run the agent:

```bash
./gradlew run --args="--socket /tmp/my-agent.sock"
```

## Documentation

- [Quickstart Guide](quickstart.md) - Get up and running in 5 minutes
- [API Reference](api.md) - Complete API documentation
- [Examples](examples.md) - Common patterns and use cases
- [Zentinel Configuration](configuration.md) - How to configure Zentinel to use agents

## Architecture

```
┌─────────────┐     ┌──────────────┐     ┌──────────────┐
│   Client    │────▶│   Zentinel   │────▶│   Upstream   │
└─────────────┘     └──────────────┘     └──────────────┘
                           │
                           │ Unix Socket
                           ▼
                    ┌──────────────┐
                    │    Agent     │
                    │   (Kotlin)   │
                    └──────────────┘
```

1. Client sends request to Zentinel
2. Zentinel forwards request headers to agent via Unix socket
3. Agent returns a decision (allow, block, redirect)
4. Zentinel applies the decision and forwards to upstream (if allowed)
5. Agent can also process response headers

## Protocol

The SDK implements version 2 of the Zentinel Agent Protocol:

- **Transport**: Unix domain sockets (UDS) or gRPC
- **Encoding**: Length-prefixed binary (4-byte big-endian length + 1-byte type prefix) for UDS
- **Max message size**: 16 MB (UDS) / 10 MB (gRPC)

For the canonical protocol specification, including wire format details, event types, and architectural diagrams, see the [Zentinel Agent Protocol documentation](https://github.com/zentinelproxy/zentinel/tree/main/crates/agent-protocol).

## License

Apache 2.0
