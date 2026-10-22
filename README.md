<p align="center">
  <a href="https://zentinelproxy.io">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/zentinelproxy/design/main/marks/lockup/zentinel-lockup-dark.svg">
      <img alt="Zentinel" src="https://raw.githubusercontent.com/zentinelproxy/design/main/marks/lockup/zentinel-lockup-light.svg" height="52">
    </picture>
  </a>
</p>

<h3 align="center">Kotlin agent SDK</h3>

<p align="center">Write Zentinel agents in Kotlin on the JVM, speaking agent protocol v2 over a Unix socket or gRPC.</p>

<p align="center">
  <a href="https://github.com/zentinelproxy/zentinel-agent-kotlin-sdk/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/zentinelproxy/zentinel-agent-kotlin-sdk/actions/workflows/ci.yml/badge.svg"></a>
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
a runner with Unix socket and gRPC transports and health and metrics
reporting. It is not on Maven Central; build it and publish it to your local
Maven repository (see [Install](#install)).

> **Status (checked on 2026-10-22): agents built with this SDK work with Zentinel over agent protocol v2, on both the Unix socket and gRPC.**
> CI runs them behind a real `zentinel` binary through the shared
> [conformance harness](https://github.com/zentinelproxy/zentinel/tree/main/sdk-conformance):
> handshake, configuration, request and response header changes, blocking,
> request-body inspection, and reconnecting after the agent restarts all pass.
> Delivery of the agent's `config { }` block to `onConfigure` needs a Zentinel
> with zentinel#603, which is on `main` but not in a release yet (26.10_7 and
> earlier never send it). Version 0.2.0 and earlier could not connect at all
> (zentinel#600): the Unix socket runner expected a different handshake, and
> the "gRPC" transport was a plain TCP socket.

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

Run it with `gradle run`. It listens on `/tmp/hello.sock`. The same agent is
[`examples/HelloAgent.kt`](examples/HelloAgent.kt), which CI runs through the
conformance harness.

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

To serve gRPC instead, use `AgentRunnerV2Config.grpc("127.0.0.1", 50051)` and
point the agent at it with `grpc "http://127.0.0.1:50051"` in place of
`unix-socket`. `runAgentV2(agent, args)` does either from the command line:
`--socket PATH`, `--grpc HOST:PORT`, or both.

## Install

Build the SDK and publish it to `~/.m2`:

```bash
git clone https://github.com/zentinelproxy/zentinel-agent-kotlin-sdk
cd zentinel-agent-kotlin-sdk
mise install                  # JDK 21 (or any JDK 21 on your PATH)
./gradlew publishToMavenLocal
```

Then, in your project's `build.gradle.kts`:

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
    implementation("io.raskell.zentinel:zentinel-agent-kotlin-sdk:0.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
}

kotlin { jvmToolchain(21) }

application { mainClass.set("MainKt") }
```

Instead of publishing, a Gradle build can also use a clone directly with
`includeBuild("../zentinel-agent-kotlin-sdk")` in its `settings.gradle.kts`.

## API overview

- **`Agent`**: `name`, `onRequest`, `onRequestBody`, `onResponse`,
  `onResponseBody`, `onRequestComplete`, `onConfigure`, `onGuardrailInspect`,
  with allow-everything defaults.
- **`v2.AgentV2`**: adds `capabilities()`, `healthStatus()`, `metrics()`,
  cancellation (by correlation id), drain and shutdown hooks.
  `v2.AgentRunnerV2(agent, config)` with `AgentRunnerV2Config.uds(path)`,
  `.grpc(host, port)` or `.both(..)`; `runAgentV2(agent, args)` reads
  `--socket PATH` and `--grpc HOST:PORT`. The gRPC transport is the proxy's
  `zentinel.agent.v2.AgentServiceV2`, generated from its `agent_v2.proto`
  (copied into `src/main/proto/`). `AgentRunner` and `runAgent` from the root
  package are wrappers over the v2 runner.
- **`Request` / `Response`**: header, path, query, client IP and body accessors.
- **`Decision`**: `allow()`, `deny()`, `unauthorized()`, `rateLimited()`,
  `block(status)`, `redirect(url)`, `challenge(..)`, then chain
  `addRequestHeader`, `removeRequestHeader`, `addResponseHeader`, `withBody`,
  `withJsonBody`, `withTag`, `withRuleId`, `withConfidence`, `withMetadata`,
  `withRoutingMetadata`, body mutations.

Examples are in [`examples/`](examples/); `./gradlew installExamples` compiles
them and puts them with their classpath in `build/examples/lib`, so
`java -cp 'build/examples/lib/*' io.raskell.zentinel.agent.examples.ConformanceAgentKt --socket /tmp/c.sock`
runs one.

## Development

```bash
mise install          # JDK 21
./gradlew build       # compiles, runs the unit tests, installs the examples
```

To run the examples behind a real Zentinel through the conformance harness
(the same checks CI runs), point the integration test at a `zentinel`
binary built from `main` and a checkout of zentinel:

```bash
ZENTINEL_BIN=/path/to/zentinel \
ZENTINEL_CONFORMANCE_HARNESS=/path/to/zentinel/sdk-conformance/run.py \
./gradlew test --tests '*ConformanceTest*'
```

No lint or API-doc task is configured.

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
