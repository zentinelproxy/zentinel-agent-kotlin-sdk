# API Reference

## Agent

The interface for all Zentinel agents.

```kotlin
import io.raskell.zentinel.agent.*
```

### Required Properties

#### `name`

```kotlin
val name: String
```

Returns the agent identifier used for logging.

### Event Handlers

#### `onConfigure`

```kotlin
suspend fun onConfigure(config: JsonObject)
```

Called when the agent receives configuration from the proxy. Override to validate and store configuration.

**Default**: No-op

#### `onRequest`

```kotlin
suspend fun onRequest(request: Request): Decision
```

Called when request headers are received. This is the main entry point for request processing.

**Default**: Returns `Decision.allow()`

#### `onRequestBody`

```kotlin
suspend fun onRequestBody(request: Request): Decision
```

Called when the request body is available (requires body inspection to be enabled in Zentinel).

**Default**: Returns `Decision.allow()`

#### `onResponse`

```kotlin
suspend fun onResponse(request: Request, response: Response): Decision
```

Called when response headers are received from the upstream server.

**Default**: Returns `Decision.allow()`

#### `onResponseBody`

```kotlin
suspend fun onResponseBody(request: Request, response: Response): Decision
```

Called when the response body is available (requires body inspection to be enabled).

**Default**: Returns `Decision.allow()`

#### `onRequestComplete`

```kotlin
suspend fun onRequestComplete(request: Request, status: Int, durationMs: Long)
```

Called when request processing is complete. Use for logging or metrics.

**Default**: No-op

---

## ConfigurableAgent

A generic agent interface with typed configuration support.

```kotlin
@Serializable
data class RateLimitConfig(
    val requestsPerMinute: Int = 60,
    val enabled: Boolean = true
)

class RateLimitAgent : ConfigurableAgent<RateLimitConfig> {
    override val name = "rate-limiter"

    private var _config = RateLimitConfig()

    override fun config(): RateLimitConfig = _config

    override suspend fun onConfigure(rawConfig: JsonObject) {
        _config = protocolJson.decodeFromJsonElement(
            RateLimitConfig.serializer(),
            rawConfig
        )
        println("Rate limit set to ${_config.requestsPerMinute}/min")
    }

    override suspend fun onRequest(request: Request): Decision {
        if (!config().enabled) {
            return Decision.allow()
        }
        // Use config().requestsPerMinute...
        return Decision.allow()
    }
}
```

### Methods

#### `config()`

```kotlin
fun config(): T
```

Returns the current configuration instance.

---

## Decision

Fluent builder for agent decisions.

```kotlin
import io.raskell.zentinel.agent.Decision
```

### Factory Functions

#### `allow()`

Create an allow decision (pass request through).

```kotlin
Decision.allow()
```

#### `block(status: Int)`

Create a block decision with a status code.

```kotlin
Decision.block(403)
Decision.block(500)
```

#### `deny()`

Shorthand for `block(403)`.

```kotlin
Decision.deny()
```

#### `unauthorized()`

Shorthand for `block(401)`.

```kotlin
Decision.unauthorized()
```

#### `rateLimited()`

Shorthand for `block(429)`.

```kotlin
Decision.rateLimited()
```

#### `redirect(url: String, status: Int = 302)`

Create a redirect decision.

```kotlin
Decision.redirect("https://example.com/login")
Decision.redirect("https://example.com/new-path", 301)
```

#### `challenge(type: String, params: Map<String, String> = emptyMap())`

Create a challenge decision (e.g., CAPTCHA, JavaScript challenge).

```kotlin
Decision.challenge("captcha", mapOf("site_key" to "..."))
Decision.challenge("js_challenge")  // No params needed
```

### Chaining Methods

All methods return `Decision` for chaining.

#### `withBody(body: String)`

Set the response body for block decisions.

```kotlin
Decision.deny().withBody("Access denied")
```

#### `withJsonBody(value: JsonElement)`

Set a JSON response body. Automatically sets `Content-Type: application/json`.

```kotlin
Decision.block(400).withJsonBody(buildJsonObject {
    put("error", "Invalid request")
})
```

#### `withBlockHeader(name: String, value: String)`

Add a header to the block response.

```kotlin
Decision.deny().withBlockHeader("X-Blocked-By", "my-agent")
```

#### `addRequestHeader(name: String, value: String)`

Add a header to the upstream request.

```kotlin
Decision.allow().addRequestHeader("X-User-ID", "123")
```

#### `removeRequestHeader(name: String)`

Remove a header from the upstream request.

```kotlin
Decision.allow().removeRequestHeader("Cookie")
```

#### `addResponseHeader(name: String, value: String)`

Add a header to the client response.

```kotlin
Decision.allow().addResponseHeader("X-Frame-Options", "DENY")
```

#### `removeResponseHeader(name: String)`

Remove a header from the client response.

```kotlin
Decision.allow().removeResponseHeader("Server")
```

### Audit Methods

#### `withTag(tag: String)`

Add an audit tag.

```kotlin
Decision.deny().withTag("security")
```

#### `withTags(tags: List<String>)`

Add multiple audit tags.

```kotlin
Decision.deny().withTags(listOf("blocked", "rate-limit"))
```

#### `withRuleId(ruleId: String)`

Add a rule ID for audit logging.

```kotlin
Decision.deny().withRuleId("SQLI-001")
```

#### `withConfidence(confidence: Float)`

Set a confidence score (0.0 to 1.0).

```kotlin
Decision.deny().withConfidence(0.95f)
```

#### `withReasonCode(code: String)`

Add a reason code.

```kotlin
Decision.deny().withReasonCode("IP_BLOCKED")
```

#### `withMetadata(key: String, value: JsonElement)`

Add custom audit metadata.

```kotlin
Decision.deny().withMetadata("blocked_ip", JsonPrimitive("192.168.1.100"))
```

### Advanced Methods

#### `needsMoreData()`

Indicate that more data is needed before deciding.

```kotlin
Decision.allow().needsMoreData()
```

#### `withRoutingMetadata(key: String, value: String)`

Add routing metadata for upstream selection.

```kotlin
Decision.allow().withRoutingMetadata("upstream", "backend-v2")
```

#### `withRequestBodyMutation(mutation: BodyMutation)`

Set a mutation for the request body.

```kotlin
Decision.allow().withRequestBodyMutation(BodyMutation.replace(0, "modified body"))
```

#### `withResponseBodyMutation(mutation: BodyMutation)`

Set a mutation for the response body.

```kotlin
Decision.allow().withResponseBodyMutation(BodyMutation.replace(0, "modified body"))
```

---

## Request

Represents an incoming HTTP request.

```kotlin
import io.raskell.zentinel.agent.Request
```

### Methods

#### `method()`

The HTTP method (GET, POST, etc.).

```kotlin
if (request.method() == "POST") { /* ... */ }
```

#### `path()`

The request path without query string.

```kotlin
val path = request.path() // "/api/users"
```

#### `uri()`

The full URI including query string.

```kotlin
val uri = request.uri() // "/api/users?page=1"
```

#### `queryString()`

The raw query string.

```kotlin
val qs = request.queryString() // "page=1&limit=10"
```

#### `pathStartsWith(prefix: String)`

Check if the path starts with a prefix.

```kotlin
if (request.pathStartsWith("/api/")) { /* ... */ }
```

#### `pathEquals(path: String)`

Check if the path exactly matches.

```kotlin
if (request.pathEquals("/health")) { /* ... */ }
```

#### `pathEndsWith(suffix: String)`

Check if the path ends with a suffix.

```kotlin
if (request.pathEndsWith(".json")) { /* ... */ }
```

#### `pathContains(substring: String)`

Check if the path contains a substring.

```kotlin
if (request.pathContains("admin")) { /* ... */ }
```

#### `pathMatches(regex: Regex)`

Check if the path matches a regex pattern.

```kotlin
if (request.pathMatches(Regex("/api/users/\\d+"))) { /* ... */ }
```

### HTTP Method Checks

#### `isGet()`, `isPost()`, `isPut()`, `isDelete()`, `isPatch()`, `isHead()`, `isOptions()`

Check the HTTP method (case-insensitive).

```kotlin
if (request.isGet()) { /* ... */ }
if (request.isPost()) { /* ... */ }
if (request.isPut()) { /* ... */ }
if (request.isDelete()) { /* ... */ }
```

### Header Methods

#### `header(name: String)`

Get a header value (case-insensitive).

```kotlin
val auth = request.header("authorization")
```

#### `headerAll(name: String)`

Get all values for a header.

```kotlin
val accepts = request.headerAll("accept")
```

#### `hasHeader(name: String)`

Check if a header exists.

```kotlin
if (request.hasHeader("Authorization")) { /* ... */ }
```

#### `headers()`

Get all headers as a map.

```kotlin
val headers = request.headers()
```

### Common Headers

```kotlin
request.host()          // Host header
request.userAgent()     // User-Agent header
request.contentType()   // Content-Type header
request.authorization() // Authorization header
```

### Query Methods

#### `query(name: String)`

Get a single query parameter.

```kotlin
val page = request.query("page")
```

#### `queryAll(name: String)`

Get all values for a query parameter.

```kotlin
val tags = request.queryAll("tag")
```

### Body Methods

#### `body()`

Get the request body as bytes.

```kotlin
val body = request.body()
```

#### `bodyString()`

Get the request body as string.

```kotlin
val bodyStr = request.bodyString()
```

#### `bodyJson(deserializer: DeserializationStrategy<T>)`

Parse the body as JSON.

```kotlin
@Serializable
data class Payload(val name: String)

val payload = request.bodyJson(Payload.serializer())
```

### Metadata Methods

```kotlin
request.correlationId()  // Request correlation ID
request.requestId()      // Proxy request ID
request.clientIp()       // Client IP address
request.clientPort()     // Client port
request.serverName()     // Server name
request.protocol()       // HTTP protocol version
```

### Content Type Checks

```kotlin
request.isJson()      // Content-Type contains application/json
request.isForm()      // Content-Type is form-urlencoded
request.isMultipart() // Content-Type is multipart
```

---

## Response

Represents an HTTP response from the upstream.

```kotlin
import io.raskell.zentinel.agent.Response
```

### Methods

#### `statusCode()`

The HTTP status code.

```kotlin
if (response.statusCode() == 200) { /* ... */ }
```

#### `isSuccess()`

Check if status is 2xx.

#### `isRedirect()`

Check if status is 3xx.

#### `isClientError()`

Check if status is 4xx.

#### `isServerError()`

Check if status is 5xx.

#### `isError()`

Check if status is 4xx or 5xx.

### Header Methods

```kotlin
response.header(name: String)
response.headerAll(name: String)
response.hasHeader(name: String)
response.headers()
```

### Common Headers

```kotlin
response.contentType()
response.location()  // For redirects
```

### Content Type Checks

```kotlin
response.isJson()
response.isHtml()
```

### Body Methods

```kotlin
response.body()
response.bodyString()
response.bodyJson(deserializer: DeserializationStrategy<T>)
```

---

## AgentRunner

Runner for starting and managing an agent.

```kotlin
import io.raskell.zentinel.agent.AgentRunner
```

### Usage

```kotlin
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    AgentRunner(MyAgent())
        .withSocket("/tmp/my-agent.sock")
        .withLogLevel("debug")
        .run()
}
```

### Builder Methods

#### `withSocket(path: String)`

Set the Unix socket path.

#### `withLogLevel(level: String)`

Set the log level (DEBUG, INFO, WARN, ERROR).

#### `withJsonLogs()`

Enable JSON log format.

#### `withName(name: String)`

Override the agent name for logging.

```kotlin
AgentRunner(MyAgent())
    .withName("custom-agent-name")
    .run()
```

#### `shutdown()`

Request graceful shutdown. The agent will stop accepting new connections and exit cleanly.

```kotlin
val runner = AgentRunner(MyAgent())
// Later...
runner.shutdown()
```

### Signal Handling

The agent runner automatically handles SIGINT and SIGTERM signals for graceful shutdown. When a shutdown signal is received, the runner will:
1. Stop accepting new connections
2. Wait for in-flight requests to complete
3. Clean up the Unix socket file
4. Exit cleanly

---

## runAgent

Convenience function to run an agent with CLI argument parsing.

```kotlin
import io.raskell.zentinel.agent.runAgent

fun main(args: Array<String>) {
    runAgent(MyAgent(), args)
}
```

This parses `--socket`, `--log-level`, and `--json-logs` from command line arguments.

---

## BodyMutation

Helper class for body mutations.

```kotlin
import io.raskell.zentinel.agent.BodyMutation
```

### Factory Methods

#### `passThrough(chunkIndex: Int)`

Pass the chunk through unchanged.

```kotlin
BodyMutation.passThrough(0)
```

#### `dropChunk(chunkIndex: Int)`

Drop the chunk entirely.

```kotlin
BodyMutation.dropChunk(0)
```

#### `replace(chunkIndex: Int, data: String)`

Replace the chunk with new data.

```kotlin
BodyMutation.replace(0, "new content")
```

### Instance Methods

#### `isPassThrough()`

Check if this is a pass-through mutation.

#### `isDrop()`

Check if this is a drop mutation.
