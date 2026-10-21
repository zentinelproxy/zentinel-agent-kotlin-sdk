<p align="center">
  <a href="https://zentinelproxy.io">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/zentinelproxy/design/main/marks/lockup/zentinel-lockup-dark.svg">
      <img alt="Zentinel" src="https://raw.githubusercontent.com/zentinelproxy/design/main/marks/lockup/zentinel-lockup-light.svg" height="52">
    </picture>
  </a>
</p>

<h3 align="center">Kotlin agent SDK</h3>

<p align="center">Write Zentinel agents in Kotlin on the JVM, speaking agent protocol v2 over a Unix socket.</p>

<p align="center">
  <img alt="License: Apache-2.0" src="https://img.shields.io/badge/license-Apache--2.0-9b4d8a?style=flat&labelColor=1f161e">
  <img alt="Agent protocol: v2" src="https://img.shields.io/badge/agent%20protocol-v2-f68d31?style=flat&labelColor=1f161e">
  <img alt="JDK 21+" src="https://img.shields.io/badge/jdk-21%2B-f68d31?style=flat&labelColor=1f161e&logo=openjdk&logoColor=white">
</p>

<p align="center">
  <a href="https://docs.zentinelproxy.io/agents/v2/">Agent protocol</a> &middot;
  <a href="https://docs.zentinelproxy.io">Docs</a> &middot;
  <a href="https://github.com/zentinelproxy">All repositories</a>
</p>

The SDK gives you a coroutine-based `Agent` interface with one suspend function
per event, a `Decision` builder, typed configuration, and in the `v2` package
a runner with health and metrics reporting. It is not on Maven Central; build
it and publish it to your local Maven repository.

> **Status (checked on 2026-10-21 against Zentinel 0.6.50): agents built with this SDK do not connect to Zentinel.**
> The `v2` Unix socket runner uses the v2 framing but parses a different
> handshake request from the one the proxy sends (`Field 'client_name' is
> required`), so every connection is dropped. The `v2` "gRPC" transport is a
> plain TCP socket with the UDS framing, not gRPC, and the root package's
> `PROTOCOL_VERSION` is still 1. The repository has no CI; its 102 unit tests
> pass locally with Gradle 8.5 and JDK 21. The quick start shows the intended
> shape.

## Quick start

In a Gradle project set up as in [Install](#install), create
`src/main/kotlin/Main.kt`:

```kotlin
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

fun main() = runBlocking {
    AgentRunnerV2(HelloAgent(), AgentRunnerV2Config.uds("/tmp/hello.sock")).run()
}
```

Run it with `gradle run`. It listens on `/tmp/hello.sock`.

Then point Zentinel at the socket. This assumes your config already defines a
listener and an upstream named `backend`:

```kdl
agents {
    agent "hello" type="custom" {
        unix-socket "/tmp/hello.sock"
        events "request_headers"
        timeout-ms 100
    }
}

filters {
    filter "hello" {
        type "agent"
        agent "hello"
        failure-mode "closed"
    }
}

routes {
    route "default" {
        matches {
            path-prefix "/"
        }
        upstream "backend"
        filters "hello"
    }
}
```

Check the file with `zentinel test -c zentinel.kdl` and start Zentinel. Every
request on the route then reaches `backend` with `X-Hello: from-agent`.
`failure-mode "closed"` on the filter turns an unreachable agent into a 503
instead of letting requests through unprocessed, so a wiring mistake shows up
on the first request.

With this SDK as it is today, that 503 is what you get: see the status note
above.

## Install

Build the SDK and publish it to `~/.m2`:

```bash
git clone https://github.com/zentinelproxy/zentinel-agent-kotlin-sdk
cd zentinel-agent-kotlin-sdk
mise install                  # JDK 21 and Gradle 8.5
gradle publishToMavenLocal
```

Use an installed Gradle: the repository has no `gradle-wrapper.jar`, so
`./gradlew` fails. Then, in your project's `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "1.9.22"
    application
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.raskell.zentinel:zentinel-agent-kotlin-sdk:0.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
}

kotlin { jvmToolchain(21) }

application { mainClass.set("MainKt") }
```

## API overview

- **`Agent`**: `name`, `onRequest`, `onRequestBody`, `onResponse`,
  `onResponseBody`, `onRequestComplete`, `onConfigure`, `onGuardrailInspect`,
  with allow-everything defaults.
- **`v2.AgentV2`**: adds `capabilities()`, `healthStatus()`, `metrics()`,
  cancellation, drain and shutdown hooks. `v2.AgentRunnerV2(agent, config)`
  with `AgentRunnerV2Config.uds(path)`; `runAgentV2(agent, args)` reads
  `--socket`, `--transport`, `--host`, `--port`.
- **`Request` / `Response`**: header, path, query, client IP and body accessors.
- **`Decision`**: `allow()`, `deny()`, `unauthorized()`, `rateLimited()`,
  `block(status)`, `redirect(url)`, `challenge(..)`, then chain
  `addRequestHeader`, `removeRequestHeader`, `addResponseHeader`, `withBody`,
  `withJsonBody`, `withTag`, `withRuleId`, `withConfidence`, `withMetadata`,
  `withRoutingMetadata`, body mutations.

Examples are in [`examples/`](examples/).

## Development

```bash
mise install          # JDK 21, Gradle 8.5
gradle build
gradle test
```

There is no CI workflow and no lint or API-doc task configured.

## Related

- [Agent protocol v2](https://docs.zentinelproxy.io/agents/v2/)
- [Agent configuration](https://docs.zentinelproxy.io/configuration/agents/)
- [Guides in this repository](docs/index.md): getting started, API, configuration, examples
- [Other SDKs](https://github.com/zentinelproxy?q=sdk)
- [Zentinel](https://github.com/zentinelproxy/zentinel)

## License

Apache-2.0, see [`LICENSE`](LICENSE).

---

<p align="center">
  <sub>Part of <a href="https://zentinelproxy.io">Zentinel</a>, the security-first reverse proxy. The shisa guards the gate.</sub>
</p>
