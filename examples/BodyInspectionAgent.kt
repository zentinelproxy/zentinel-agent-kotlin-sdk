package io.raskell.zentinel.agent.examples

import io.raskell.zentinel.agent.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An agent that inspects request and response bodies.
 */
class BodyInspectionAgent : Agent {
    override val name = "body-inspection-agent"

    // Patterns that indicate SQL injection attempts
    private val sqlInjectionPatterns = listOf(
        Regex("(?i)union\\s+select"),
        Regex("(?i)or\\s+1\\s*=\\s*1"),
        Regex("(?i);\\s*drop\\s+table"),
        Regex("(?i)'\\s*or\\s+'"),
        Regex("(?i)--\\s*$")
    )

    // Patterns that indicate XSS attempts
    private val xssPatterns = listOf(
        Regex("(?i)<script[^>]*>"),
        Regex("(?i)javascript:"),
        Regex("(?i)on\\w+\\s*="),
        Regex("(?i)<iframe[^>]*>")
    )

    override suspend fun onRequest(request: Request): Decision {
        // For POST/PUT requests, we want body inspection
        if (request.method in listOf("POST", "PUT", "PATCH")) {
            // Return needs_more_data to get the body
            return Decision.allow().needsMoreData()
        }

        // Check query parameters for injection
        val queryString = request.queryString ?: ""
        val sqliMatch = checkSqlInjection(queryString)
        if (sqliMatch != null) {
            return Decision.deny()
                .withBody("Potential SQL injection detected")
                .withTag("sql-injection")
                .withRuleId("SQLI-001")
                .withConfidence(0.9f)
                .withMetadata("pattern", sqliMatch)
        }

        return Decision.allow()
    }

    override suspend fun onRequestBody(request: Request): Decision {
        val body = request.bodyString() ?: return Decision.allow()

        // Check for SQL injection
        val sqliMatch = checkSqlInjection(body)
        if (sqliMatch != null) {
            return Decision.deny()
                .withBody("Potential SQL injection detected in request body")
                .withTag("sql-injection")
                .withRuleId("SQLI-002")
                .withConfidence(0.95f)
                .withMetadata("pattern", sqliMatch)
        }

        // Check for XSS
        val xssMatch = checkXss(body)
        if (xssMatch != null) {
            return Decision.deny()
                .withBody("Potential XSS detected in request body")
                .withTag("xss")
                .withRuleId("XSS-001")
                .withConfidence(0.9f)
                .withMetadata("pattern", xssMatch)
        }

        // If JSON, validate structure
        if (request.isJson()) {
            try {
                Json.parseToJsonElement(body)
            } catch (e: Exception) {
                return Decision.badRequest()
                    .withBody("Invalid JSON in request body")
                    .withTag("invalid-json")
                    .withRuleId("JSON-001")
            }
        }

        return Decision.allow()
    }

    override suspend fun onResponse(request: Request, response: Response): Decision {
        // Check for sensitive data in error responses
        if (response.isServerError()) {
            return Decision.allow().needsMoreData()
        }

        return Decision.allow()
    }

    override suspend fun onResponseBody(request: Request, response: Response): Decision {
        val body = response.bodyString() ?: return Decision.allow()

        // Check for sensitive data leakage in error responses
        if (response.isServerError()) {
            val sensitivePatterns = listOf(
                "stack trace" to Regex("(?i)at\\s+[\\w.]+\\([^)]+:\\d+\\)"),
                "sql error" to Regex("(?i)(sql|mysql|postgresql|oracle).*error"),
                "file path" to Regex("(?i)/[a-z]+/[a-z]+/[^\\s]+\\.(java|kt|py|rb|php)")
            )

            for ((name, pattern) in sensitivePatterns) {
                if (pattern.containsMatchIn(body)) {
                    // Replace response body with generic error
                    return Decision.allow()
                        .withResponseBodyMutation(
                            BodyMutation.replace(0, "Internal server error")
                        )
                        .withTag("sensitive-data-filtered")
                        .withMetadata("filtered_type", name)
                }
            }
        }

        return Decision.allow()
    }

    private fun checkSqlInjection(input: String): String? {
        for (pattern in sqlInjectionPatterns) {
            if (pattern.containsMatchIn(input)) {
                return pattern.pattern
            }
        }
        return null
    }

    private fun checkXss(input: String): String? {
        for (pattern in xssPatterns) {
            if (pattern.containsMatchIn(input)) {
                return pattern.pattern
            }
        }
        return null
    }
}

fun main(args: Array<String>) {
    runAgent(BodyInspectionAgent(), args)
}
