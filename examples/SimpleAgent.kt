package io.raskell.zentinel.agent.examples

import io.raskell.zentinel.agent.*

/**
 * A simple agent that blocks admin paths and adds headers.
 */
class SimpleAgent : Agent {
    override val name = "simple-agent"

    override suspend fun onRequest(request: Request): Decision {
        // Block admin paths without authorization
        if (request.pathStartsWith("/admin") && request.authorization == null) {
            return Decision.deny()
                .withBody("Access denied: Authorization required")
                .withTag("unauthorized-admin-access")
                .withRuleId("ADMIN-001")
        }

        // Block suspicious paths
        if (request.path.contains("..") || request.path.contains("etc/passwd")) {
            return Decision.deny()
                .withBody("Forbidden")
                .withTag("path-traversal")
                .withRuleId("SEC-001")
                .withConfidence(0.95f)
        }

        // Allow with added headers
        return Decision.allow()
            .addRequestHeader("X-Processed-By", name)
            .addRequestHeader("X-Client-IP", request.clientIp)
    }

    override suspend fun onResponse(request: Request, response: Response): Decision {
        // Add security headers to HTML responses
        if (response.isHtml()) {
            return Decision.allow()
                .addResponseHeader("X-Frame-Options", "DENY")
                .addResponseHeader("X-Content-Type-Options", "nosniff")
                .addResponseHeader("X-XSS-Protection", "1; mode=block")
                .removeResponseHeader("Server")
        }

        return Decision.allow()
    }

    override suspend fun onRequestComplete(request: Request, status: Int, durationMs: Long) {
        println("${request.method} ${request.path} -> $status (${durationMs}ms)")
    }
}

fun main(args: Array<String>) {
    runAgent(SimpleAgent(), args)
}
