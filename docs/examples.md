# Examples

Common patterns and use cases for Zentinel agents.

## Basic Request Blocking

Block requests based on path patterns:

```kotlin
package com.example

import io.raskell.zentinel.agent.*

class BlockingAgent : Agent {
    override val name = "blocking-agent"

    private val blockedPaths = listOf("/admin", "/internal", "/.git", "/.env")

    override suspend fun onRequest(request: Request): Decision {
        for (blocked in blockedPaths) {
            if (request.pathStartsWith(blocked)) {
                return Decision.deny()
                    .withBody("Not Found")
                    .withTag("path-blocked")
            }
        }
        return Decision.allow()
    }
}

fun main(args: Array<String>) {
    runAgent(BlockingAgent(), args)
}
```

## IP-Based Access Control

Block or allow requests based on client IP:

```kotlin
package com.example

import io.raskell.zentinel.agent.*
import kotlinx.serialization.json.JsonPrimitive

class IPFilterAgent : Agent {
    override val name = "ip-filter"

    private val allowedIPs = setOf(
        "10.0.0.1",
        "192.168.1.1",
        "127.0.0.1"
    )

    override suspend fun onRequest(request: Request): Decision {
        val clientIP = request.clientIp()

        if (clientIP in allowedIPs) {
            return Decision.allow()
        }

        return Decision.deny()
            .withTag("ip-blocked")
            .withMetadata("blocked_ip", JsonPrimitive(clientIP))
    }
}

fun main(args: Array<String>) {
    runAgent(IPFilterAgent(), args)
}
```

## Authentication Validation

Validate JWT tokens:

```kotlin
package com.example

import io.raskell.zentinel.agent.*
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys

class AuthAgent(private val secret: String) : Agent {
    override val name = "auth-agent"

    private val key = Keys.hmacShaKeyFor(secret.toByteArray())

    override suspend fun onRequest(request: Request): Decision {
        // Skip auth for public paths
        if (request.pathStartsWith("/public")) {
            return Decision.allow()
        }

        val auth = request.authorization()
        if (auth == null || !auth.startsWith("Bearer ")) {
            return Decision.unauthorized()
                .withBody("Missing or invalid Authorization header")
                .withTag("auth-missing")
        }

        val tokenString = auth.removePrefix("Bearer ")

        return try {
            val claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(tokenString)
                .body

            val userId = claims.subject
            val role = claims["role"] as? String ?: "user"

            Decision.allow()
                .addRequestHeader("X-User-ID", userId)
                .addRequestHeader("X-User-Role", role)
        } catch (e: Exception) {
            Decision.unauthorized()
                .withBody("Invalid token")
                .withTag("auth-invalid")
        }
    }
}

fun main(args: Array<String>) {
    runAgent(AuthAgent("your-secret-key"), args)
}
```

## Rate Limiting

Simple in-memory rate limiting:

```kotlin
package com.example

import io.raskell.zentinel.agent.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class RateLimitAgent : Agent {
    override val name = "rate-limit"

    private val maxRequests = 100
    private val windowSeconds = 60L
    private val requests = ConcurrentHashMap<String, MutableList<Instant>>()

    override suspend fun onRequest(request: Request): Decision {
        val key = request.clientIp()
        val now = Instant.now()
        val windowStart = now.minusSeconds(windowSeconds)

        val timestamps = requests.computeIfAbsent(key) { mutableListOf() }

        synchronized(timestamps) {
            // Clean old entries and add current
            timestamps.removeIf { it.isBefore(windowStart) }
            timestamps.add(now)

            if (timestamps.size > maxRequests) {
                return Decision.rateLimited()
                    .withBody("Too many requests")
                    .withTag("rate-limited")
                    .addResponseHeader("Retry-After", windowSeconds.toString())
            }

            val remaining = maxRequests - timestamps.size
            return Decision.allow()
                .addResponseHeader("X-RateLimit-Limit", maxRequests.toString())
                .addResponseHeader("X-RateLimit-Remaining", remaining.toString())
        }
    }
}

fun main(args: Array<String>) {
    runAgent(RateLimitAgent(), args)
}
```

## Header Modification

Add, remove, or modify headers:

```kotlin
package com.example

import io.raskell.zentinel.agent.*

class HeaderAgent : Agent {
    override val name = "header-agent"

    override suspend fun onRequest(request: Request): Decision {
        return Decision.allow()
            // Add headers for upstream
            .addRequestHeader("X-Forwarded-By", "zentinel")
            .addRequestHeader("X-Request-ID", request.correlationId())
            // Remove sensitive headers
            .removeRequestHeader("X-Internal-Token")
    }

    override suspend fun onResponse(request: Request, response: Response): Decision {
        return Decision.allow()
            // Add security headers
            .addResponseHeader("X-Frame-Options", "DENY")
            .addResponseHeader("X-Content-Type-Options", "nosniff")
            .addResponseHeader("X-XSS-Protection", "1; mode=block")
            // Remove server info
            .removeResponseHeader("Server")
            .removeResponseHeader("X-Powered-By")
    }
}

fun main(args: Array<String>) {
    runAgent(HeaderAgent(), args)
}
```

## Configurable Agent

Agent with runtime configuration:

```kotlin
package com.example

import io.raskell.zentinel.agent.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Config(
    val enabled: Boolean = true,
    val blockedPaths: List<String> = listOf("/admin"),
    val logRequests: Boolean = false
)

class ConfigurableBlocker : ConfigurableAgent<Config> {
    override val name = "configurable-blocker"

    private var _config = Config()

    override fun config(): Config = _config

    override suspend fun onConfigure(rawConfig: JsonObject) {
        _config = protocolJson.decodeFromJsonElement(Config.serializer(), rawConfig)
        println("Configuration updated: enabled=${_config.enabled}")
    }

    override suspend fun onRequest(request: Request): Decision {
        val cfg = config()

        if (!cfg.enabled) {
            return Decision.allow()
        }

        if (cfg.logRequests) {
            println("Request: ${request.method()} ${request.path()}")
        }

        for (blocked in cfg.blockedPaths) {
            if (request.pathStartsWith(blocked)) {
                return Decision.deny()
            }
        }

        return Decision.allow()
    }
}

fun main(args: Array<String>) {
    runAgent(ConfigurableBlocker(), args)
}
```

## Request Logging

Log all requests with timing:

```kotlin
package com.example

import io.raskell.zentinel.agent.*
import kotlinx.serialization.json.JsonPrimitive

class LoggingAgent : Agent {
    override val name = "logging-agent"

    override suspend fun onRequest(request: Request): Decision {
        return Decision.allow()
            .withTag("method:${request.method()}")
            .withMetadata("path", JsonPrimitive(request.path()))
            .withMetadata("client_ip", JsonPrimitive(request.clientIp()))
    }

    override suspend fun onRequestComplete(request: Request, status: Int, durationMs: Long) {
        println("${request.clientIp()} - ${request.method()} ${request.path()} -> $status (${durationMs}ms)")
    }
}

fun main(args: Array<String>) {
    runAgent(LoggingAgent(), args)
}
```

## Content-Type Validation

Validate request content types:

```kotlin
package com.example

import io.raskell.zentinel.agent.*

class ContentTypeAgent : Agent {
    override val name = "content-type-validator"

    private val allowedTypes = setOf(
        "application/json",
        "application/x-www-form-urlencoded",
        "multipart/form-data"
    )

    override suspend fun onRequest(request: Request): Decision {
        // Only check methods with body
        val method = request.method()
        if (method !in listOf("POST", "PUT", "PATCH")) {
            return Decision.allow()
        }

        val contentType = request.contentType()
        if (contentType == null) {
            return Decision.block(400)
                .withBody("Content-Type header required")
        }

        // Check against allowed types (ignore params like charset)
        val baseType = contentType.split(";").first().trim().lowercase()

        if (baseType !in allowedTypes) {
            return Decision.block(415)
                .withBody("Unsupported Content-Type: $baseType")
                .withTag("invalid-content-type")
        }

        return Decision.allow()
    }
}

fun main(args: Array<String>) {
    runAgent(ContentTypeAgent(), args)
}
```

## Redirect Agent

Redirect requests to different URLs:

```kotlin
package com.example

import io.raskell.zentinel.agent.*

class RedirectAgent : Agent {
    override val name = "redirect-agent"

    private val redirects = mapOf(
        "/old-path" to "/new-path",
        "/legacy" to "/v2/api",
        "/blog" to "https://blog.example.com"
    )

    override suspend fun onRequest(request: Request): Decision {
        val target = redirects[request.path()]
        if (target != null) {
            return Decision.redirect(target)
        }

        // Redirect HTTP to HTTPS
        val proto = request.header("x-forwarded-proto")
        if (proto == "http") {
            val httpsURL = "https://${request.host()}${request.uri()}"
            return Decision.redirect(httpsURL, 301)
        }

        return Decision.allow()
    }
}

fun main(args: Array<String>) {
    runAgent(RedirectAgent(), args)
}
```

## Bot Protection with Challenge

Issue challenges to suspicious requests:

```kotlin
package com.example

import io.raskell.zentinel.agent.*

class BotProtectionAgent(private val captchaSiteKey: String) : Agent {
    override val name = "bot-protection"

    private val suspiciousUserAgents = listOf(
        "curl", "wget", "python-requests", "scrapy", "bot"
    )

    private fun isSuspicious(userAgent: String): Boolean {
        val uaLower = userAgent.lowercase()
        return suspiciousUserAgents.any { it in uaLower }
    }

    override suspend fun onRequest(request: Request): Decision {
        // Check for missing or suspicious User-Agent
        val userAgent = request.userAgent()

        if (userAgent == null) {
            // No User-Agent - issue JavaScript challenge
            return Decision.challenge("js_challenge")
                .withTag("no-user-agent")
        }

        // Check for known bot patterns
        if (isSuspicious(userAgent)) {
            return Decision.challenge("captcha", mapOf(
                "site_key" to captchaSiteKey,
                "action" to "bot_check"
            ))
                .withTag("suspicious-ua")
                .withConfidence(0.75f)
        }

        // Check for high request rate from single IP (simplified)
        if (request.header("x-high-rate") != null) {
            return Decision.challenge("proof_of_work", mapOf(
                "difficulty" to "medium"
            ))
                .withTag("rate-challenge")
        }

        return Decision.allow()
    }
}

fun main(args: Array<String>) {
    runAgent(BotProtectionAgent("your-captcha-site-key"), args)
}
```

## Login Protection with CAPTCHA

Protect login endpoints with CAPTCHA challenges:

```kotlin
package com.example

import io.raskell.zentinel.agent.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class LoginProtectionAgent(private val captchaSiteKey: String) : Agent {
    override val name = "login-protection"

    // Track failed login attempts per IP
    private val failedAttempts = ConcurrentHashMap<String, AtomicInteger>()
    private val challengeThreshold = 3

    override suspend fun onRequest(request: Request): Decision {
        // Only protect login endpoint
        if (!request.pathEquals("/login") || !request.isPost()) {
            return Decision.allow()
        }

        val clientIp = request.clientIp()
        val attempts = failedAttempts.computeIfAbsent(clientIp) { AtomicInteger(0) }

        // If too many failed attempts, require CAPTCHA
        if (attempts.get() >= challengeThreshold) {
            return Decision.challenge("captcha", mapOf(
                "site_key" to captchaSiteKey,
                "action" to "login"
            ))
                .withTag("login-challenge")
                .withMetadata("failed_attempts", kotlinx.serialization.json.JsonPrimitive(attempts.get()))
        }

        return Decision.allow()
    }

    override suspend fun onResponse(request: Request, response: Response): Decision {
        // Track failed login responses
        if (request.pathEquals("/login") && request.isPost()) {
            val clientIp = request.clientIp()
            val attempts = failedAttempts.computeIfAbsent(clientIp) { AtomicInteger(0) }

            if (response.statusCode() == 401) {
                attempts.incrementAndGet()
            } else if (response.isSuccess()) {
                // Reset on successful login
                attempts.set(0)
            }
        }

        return Decision.allow()
    }
}

fun main(args: Array<String>) {
    runAgent(LoginProtectionAgent("your-captcha-site-key"), args)
}
```

## Combining Multiple Checks

Agent that performs multiple validations:

```kotlin
package com.example

import io.raskell.zentinel.agent.*

class SecurityAgent : Agent {
    override val name = "security-agent"

    private val suspiciousPatterns = listOf("/../", "/etc/", "/proc/", ".php")

    override suspend fun onRequest(request: Request): Decision {
        // Check 1: User-Agent required
        if (request.userAgent() == null) {
            return Decision.block(400).withBody("User-Agent required")
        }

        // Check 2: Block suspicious paths
        val pathLower = request.path().lowercase()
        for (pattern in suspiciousPatterns) {
            if (pattern in pathLower) {
                return Decision.deny()
                    .withTag("path-traversal")
                    .withRuleId("SEC-001")
            }
        }

        // Check 3: Block large requests without content-length
        val method = request.method()
        if (method in listOf("POST", "PUT")) {
            if (!request.hasHeader("content-length")) {
                return Decision.block(411).withBody("Content-Length required")
            }
        }

        // All checks passed
        return Decision.allow()
            .withTag("security-passed")
            .addResponseHeader("X-Security-Check", "passed")
    }
}

fun main(args: Array<String>) {
    runAgent(SecurityAgent(), args)
}
```

## Body Inspection Agent

Inspect request bodies for malicious content:

```kotlin
package com.example

import io.raskell.zentinel.agent.*
import kotlinx.serialization.json.JsonPrimitive

class BodyInspectionAgent : Agent {
    override val name = "body-inspector"

    private val sqlPatterns = listOf(
        "(?i)union\\s+select",
        "(?i)or\\s+1\\s*=\\s*1",
        "(?i)drop\\s+table",
        "(?i)--\\s*$"
    ).map { it.toRegex() }

    private val xssPatterns = listOf(
        "(?i)<script",
        "(?i)javascript:",
        "(?i)on\\w+\\s*="
    ).map { it.toRegex() }

    override suspend fun onRequestBody(request: Request): Decision {
        val body = request.bodyString() ?: return Decision.allow()

        // Check for SQL injection
        for (pattern in sqlPatterns) {
            if (pattern.containsMatchIn(body)) {
                return Decision.deny()
                    .withTag("sqli-detected")
                    .withRuleId("SQLI-001")
                    .withConfidence(0.9f)
                    .withMetadata("pattern", JsonPrimitive(pattern.pattern))
            }
        }

        // Check for XSS
        for (pattern in xssPatterns) {
            if (pattern.containsMatchIn(body)) {
                return Decision.deny()
                    .withTag("xss-detected")
                    .withRuleId("XSS-001")
                    .withConfidence(0.85f)
            }
        }

        return Decision.allow()
    }
}

fun main(args: Array<String>) {
    runAgent(BodyInspectionAgent(), args)
}
```

## Response Transformation

Transform responses from upstream:

```kotlin
package com.example

import io.raskell.zentinel.agent.*

class ResponseTransformAgent : Agent {
    override val name = "response-transform"

    override suspend fun onResponse(request: Request, response: Response): Decision {
        // Add CORS headers for API requests
        if (request.pathStartsWith("/api/")) {
            return Decision.allow()
                .addResponseHeader("Access-Control-Allow-Origin", "*")
                .addResponseHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE")
                .addResponseHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        }

        // Add cache headers for static content
        val contentType = response.contentType()
        if (contentType != null && (contentType.startsWith("image/") || contentType.startsWith("text/css"))) {
            return Decision.allow()
                .addResponseHeader("Cache-Control", "public, max-age=86400")
        }

        return Decision.allow()
    }

    override suspend fun onResponseBody(request: Request, response: Response): Decision {
        // Example: redact sensitive data from JSON responses
        if (response.isJson()) {
            val body = response.bodyString()
            if (body != null && body.contains("\"password\"")) {
                val redacted = body.replace(
                    Regex("\"password\"\\s*:\\s*\"[^\"]*\""),
                    "\"password\":\"[REDACTED]\""
                )
                return Decision.allow()
                    .withResponseBodyMutation(BodyMutation.replace(0, redacted))
            }
        }

        return Decision.allow()
    }
}

fun main(args: Array<String>) {
    runAgent(ResponseTransformAgent(), args)
}
```
