package io.raskell.zentinel.agent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * A builder for constructing agent decisions with a fluent API.
 *
 * Example usage:
 * ```kotlin
 * // Simple allow
 * Decision.allow()
 *
 * // Block with status and body
 * Decision.deny()
 *     .withBody("Access denied")
 *     .withTag("blocked")
 *
 * // Allow with header modifications
 * Decision.allow()
 *     .addRequestHeader("X-User-ID", "12345")
 *     .addResponseHeader("X-Processed-By", "my-agent")
 * ```
 */
class Decision private constructor(
    private var decisionType: DecisionType = DecisionType.ALLOW,
    private var statusCode: Int? = null,
    private var body: String? = null,
    private var blockHeaders: MutableMap<String, String>? = null,
    private var redirectUrl: String? = null,
    private var challengeType: String? = null,
    private var challengeParams: Map<String, String>? = null
) {
    private val addRequestHeaders = mutableMapOf<String, String>()
    private val removeRequestHeaders = mutableListOf<String>()
    private val addResponseHeaders = mutableMapOf<String, String>()
    private val removeResponseHeaders = mutableListOf<String>()
    private val tags = mutableListOf<String>()
    private val customMetadata = mutableMapOf<String, JsonElement>()
    private val ruleIds = mutableListOf<String>()
    private var confidence: Float? = null
    private val reasonCodes = mutableListOf<String>()
    private val routingMetadata = mutableMapOf<String, String>()
    private var needsMore = false
    private var requestBodyMutation: BodyMutation? = null
    private var responseBodyMutation: BodyMutation? = null

    private enum class DecisionType {
        ALLOW, BLOCK, REDIRECT, CHALLENGE
    }

    // ========================================================================
    // Body and Headers for Block Responses
    // ========================================================================

    /** Set the response body for block responses. */
    fun withBody(body: String): Decision {
        this.body = body
        return this
    }

    /** Set the response body as JSON. */
    fun withJsonBody(value: Any): Decision {
        this.body = protocolJson.encodeToString(kotlinx.serialization.serializer(), value)
        return this
    }

    /** Add a header to the block response. */
    fun withBlockHeader(name: String, value: String): Decision {
        if (blockHeaders == null) blockHeaders = mutableMapOf()
        blockHeaders!![name] = value
        return this
    }

    // ========================================================================
    // Request Header Modifications
    // ========================================================================

    /** Add a header to the request (sent to upstream). */
    fun addRequestHeader(name: String, value: String): Decision {
        addRequestHeaders[name] = value
        return this
    }

    /** Remove a header from the request. */
    fun removeRequestHeader(name: String): Decision {
        removeRequestHeaders.add(name)
        return this
    }

    // ========================================================================
    // Response Header Modifications
    // ========================================================================

    /** Add a header to the response (sent to client). */
    fun addResponseHeader(name: String, value: String): Decision {
        addResponseHeaders[name] = value
        return this
    }

    /** Remove a header from the response. */
    fun removeResponseHeader(name: String): Decision {
        removeResponseHeaders.add(name)
        return this
    }

    // ========================================================================
    // Audit Metadata
    // ========================================================================

    /** Add an audit tag for logging/tracing. */
    fun withTag(tag: String): Decision {
        tags.add(tag)
        return this
    }

    /** Add multiple audit tags. */
    fun withTags(vararg tags: String): Decision {
        this.tags.addAll(tags)
        return this
    }

    /** Add custom metadata for logging/tracing. */
    fun withMetadata(key: String, value: String): Decision {
        customMetadata[key] = JsonPrimitive(value)
        return this
    }

    /** Add custom metadata for logging/tracing. */
    fun withMetadata(key: String, value: Number): Decision {
        customMetadata[key] = JsonPrimitive(value)
        return this
    }

    /** Add custom metadata for logging/tracing. */
    fun withMetadata(key: String, value: Boolean): Decision {
        customMetadata[key] = JsonPrimitive(value)
        return this
    }

    /** Add custom metadata for logging/tracing. */
    fun withMetadata(key: String, value: JsonElement): Decision {
        customMetadata[key] = value
        return this
    }

    /** Add a rule ID for audit/compliance tracking. */
    fun withRuleId(ruleId: String): Decision {
        ruleIds.add(ruleId)
        return this
    }

    /** Add multiple rule IDs. */
    fun withRuleIds(vararg ruleIds: String): Decision {
        this.ruleIds.addAll(ruleIds)
        return this
    }

    /** Set the confidence score for the decision (0.0 to 1.0). */
    fun withConfidence(confidence: Float): Decision {
        this.confidence = confidence.coerceIn(0f, 1f)
        return this
    }

    /** Add a reason code explaining the decision. */
    fun withReasonCode(code: String): Decision {
        reasonCodes.add(code)
        return this
    }

    /** Add multiple reason codes. */
    fun withReasonCodes(vararg codes: String): Decision {
        reasonCodes.addAll(codes)
        return this
    }

    // ========================================================================
    // Routing Metadata
    // ========================================================================

    /** Add routing metadata for upstream selection/load balancing. */
    fun withRoutingMetadata(key: String, value: String): Decision {
        routingMetadata[key] = value
        return this
    }

    // ========================================================================
    // Streaming / Body Mutation
    // ========================================================================

    /** Indicate that more data is needed before making a final decision. */
    fun needsMoreData(): Decision {
        needsMore = true
        return this
    }

    /** Set a mutation to apply to the request body. */
    fun withRequestBodyMutation(mutation: BodyMutation): Decision {
        requestBodyMutation = mutation
        return this
    }

    /** Set a mutation to apply to the response body. */
    fun withResponseBodyMutation(mutation: BodyMutation): Decision {
        responseBodyMutation = mutation
        return this
    }

    // ========================================================================
    // Build
    // ========================================================================

    /** Build the protocol response. */
    fun build(): AgentResponse {
        val decision: ProtocolDecision = when (decisionType) {
            DecisionType.ALLOW -> ProtocolDecision.Allow
            DecisionType.BLOCK -> ProtocolDecision.Block(
                status = statusCode ?: 403,
                body = body,
                headers = blockHeaders
            )
            DecisionType.REDIRECT -> ProtocolDecision.Redirect(
                url = redirectUrl ?: "/",
                status = statusCode ?: 302
            )
            DecisionType.CHALLENGE -> ProtocolDecision.Challenge(
                challengeType = challengeType ?: "captcha",
                params = challengeParams ?: emptyMap()
            )
        }

        val requestHeaderOps = buildRequestMutations()
        val responseHeaderOps = buildResponseMutations()

        return AgentResponse(
            version = PROTOCOL_VERSION,
            decision = decision,
            requestHeaders = requestHeaderOps,
            responseHeaders = responseHeaderOps,
            routingMetadata = routingMetadata.toMap(),
            audit = AuditMetadata(
                tags = tags.toList(),
                ruleIds = ruleIds.toList(),
                confidence = confidence,
                reasonCodes = reasonCodes.toList(),
                custom = customMetadata.toMap()
            ),
            needsMore = needsMore,
            requestBodyMutation = requestBodyMutation,
            responseBodyMutation = responseBodyMutation
        )
    }

    private fun buildRequestMutations(): List<HeaderOp> {
        val mutations = mutableListOf<HeaderOp>()
        addRequestHeaders.forEach { (name, value) ->
            mutations.add(HeaderOp.Set(name, value))
        }
        removeRequestHeaders.forEach { name ->
            mutations.add(HeaderOp.Remove(name))
        }
        return mutations
    }

    private fun buildResponseMutations(): List<HeaderOp> {
        val mutations = mutableListOf<HeaderOp>()
        addResponseHeaders.forEach { (name, value) ->
            mutations.add(HeaderOp.Set(name, value))
        }
        removeResponseHeaders.forEach { name ->
            mutations.add(HeaderOp.Remove(name))
        }
        return mutations
    }

    companion object {
        // ====================================================================
        // Factory Methods
        // ====================================================================

        /** Create an allow decision. */
        fun allow(): Decision = Decision(DecisionType.ALLOW)

        /** Create a block decision with a status code. */
        fun block(statusCode: Int): Decision = Decision(
            decisionType = DecisionType.BLOCK,
            statusCode = statusCode
        )

        /** Create a deny decision (403 Forbidden). */
        fun deny(): Decision = block(403)

        /** Create an unauthorized decision (401 Unauthorized). */
        fun unauthorized(): Decision = block(401)

        /** Create a rate limited decision (429 Too Many Requests). */
        fun rateLimited(): Decision = block(429)

        /** Create a bad request decision (400 Bad Request). */
        fun badRequest(): Decision = block(400)

        /** Create a not found decision (404 Not Found). */
        fun notFound(): Decision = block(404)

        /** Create an internal server error decision (500 Internal Server Error). */
        fun internalServerError(): Decision = block(500)

        /** Create a redirect decision. */
        fun redirect(url: String, statusCode: Int = 302): Decision = Decision(
            decisionType = DecisionType.REDIRECT,
            statusCode = statusCode,
            redirectUrl = url
        )

        /** Create a permanent redirect (301). */
        fun redirectPermanent(url: String): Decision = redirect(url, 301)

        /**
         * Create a challenge decision (e.g., CAPTCHA, JavaScript challenge).
         *
         * @param type The challenge type (e.g., "captcha", "js_challenge")
         * @param params Additional parameters for the challenge
         */
        fun challenge(type: String, params: Map<String, String> = emptyMap()): Decision = Decision(
            decisionType = DecisionType.CHALLENGE,
            challengeType = type,
            challengeParams = params
        )
    }
}
