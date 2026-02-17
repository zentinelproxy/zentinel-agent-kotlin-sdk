# Zentinel Agent Kotlin SDK

A Kotlin SDK for building agents that integrate with the [Zentinel](https://github.com/zentinelproxy/zentinel) reverse proxy.

## Overview

Zentinel agents are external processors that can inspect and modify HTTP traffic passing through the Zentinel proxy. They communicate with Zentinel over Unix sockets using a length-prefixed JSON protocol.

Agents can:

- **Inspect requests** - Examine headers, paths, query parameters, and body content
- **Block requests** - Return custom error responses (403, 401, 429, etc.)
- **Redirect requests** - Send clients to different URLs
- **Modify headers** - Add, remove, or modify request/response headers
- **Add audit metadata** - Attach tags, rule IDs, and custom data for logging

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.raskell.zentinel:zentinel-agent-sdk:0.1.0")
}
```

Or for Maven (`pom.xml`):

```xml
<dependency>
    <groupId>io.raskell.zentinel</groupId>
    <artifactId>zentinel-agent-sdk</artifactId>
    <version>0.1.0</version>
</dependency>
```

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

The SDK implements version 1 of the Zentinel Agent Protocol:

- **Transport**: Unix domain sockets (UDS) or gRPC
- **Encoding**: Length-prefixed JSON (4-byte big-endian length prefix) for UDS
- **Max message size**: 10MB

For the canonical protocol specification, including wire format details, event types, and architectural diagrams, see the [Zentinel Agent Protocol documentation](https://github.com/zentinelproxy/zentinel/tree/main/crates/agent-protocol).

## License

Apache 2.0
