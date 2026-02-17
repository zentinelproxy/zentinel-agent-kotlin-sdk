package io.raskell.zentinel.agent

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecisionTest {

    @Test
    fun `allow creates allow decision`() {
        val response = Decision.allow().build()
        assertEquals(ProtocolDecision.Allow, response.decision)
    }

    @Test
    fun `deny creates block decision with 403`() {
        val response = Decision.deny().build()
        val decision = response.decision as ProtocolDecision.Block
        assertEquals(403, decision.status)
    }

    @Test
    fun `unauthorized creates block decision with 401`() {
        val response = Decision.unauthorized().build()
        val decision = response.decision as ProtocolDecision.Block
        assertEquals(401, decision.status)
    }

    @Test
    fun `rateLimited creates block decision with 429`() {
        val response = Decision.rateLimited().build()
        val decision = response.decision as ProtocolDecision.Block
        assertEquals(429, decision.status)
    }

    @Test
    fun `block with custom status`() {
        val response = Decision.block(503).build()
        val decision = response.decision as ProtocolDecision.Block
        assertEquals(503, decision.status)
    }

    @Test
    fun `block with body`() {
        val response = Decision.deny()
            .withBody("Access denied")
            .build()
        val decision = response.decision as ProtocolDecision.Block
        assertEquals("Access denied", decision.body)
    }

    @Test
    fun `redirect creates redirect decision`() {
        val response = Decision.redirect("/login").build()
        val decision = response.decision as ProtocolDecision.Redirect
        assertEquals("/login", decision.url)
        assertEquals(302, decision.status)
    }

    @Test
    fun `redirectPermanent creates 301 redirect`() {
        val response = Decision.redirectPermanent("/new-path").build()
        val decision = response.decision as ProtocolDecision.Redirect
        assertEquals("/new-path", decision.url)
        assertEquals(301, decision.status)
    }

    @Test
    fun `add request headers`() {
        val response = Decision.allow()
            .addRequestHeader("X-User-ID", "123")
            .addRequestHeader("X-Role", "admin")
            .build()

        assertEquals(2, response.requestHeaders.size)
        assertTrue(response.requestHeaders.any { it is HeaderOp.Set && it.name == "X-User-ID" && it.value == "123" })
    }

    @Test
    fun `remove request headers`() {
        val response = Decision.allow()
            .removeRequestHeader("Cookie")
            .build()

        assertEquals(1, response.requestHeaders.size)
        assertTrue(response.requestHeaders.any { it is HeaderOp.Remove && it.name == "Cookie" })
    }

    @Test
    fun `add response headers`() {
        val response = Decision.allow()
            .addResponseHeader("X-Frame-Options", "DENY")
            .build()

        assertEquals(1, response.responseHeaders.size)
        assertTrue(response.responseHeaders.any { it is HeaderOp.Set && it.name == "X-Frame-Options" })
    }

    @Test
    fun `remove response headers`() {
        val response = Decision.allow()
            .removeResponseHeader("Server")
            .build()

        assertEquals(1, response.responseHeaders.size)
        assertTrue(response.responseHeaders.any { it is HeaderOp.Remove && it.name == "Server" })
    }

    @Test
    fun `audit tags`() {
        val response = Decision.deny()
            .withTag("blocked")
            .withTags("security", "waf")
            .build()

        assertEquals(3, response.audit.tags.size)
        assertTrue("blocked" in response.audit.tags)
        assertTrue("security" in response.audit.tags)
    }

    @Test
    fun `audit metadata`() {
        val response = Decision.deny()
            .withMetadata("user_id", "123")
            .withMetadata("count", 5)
            .withMetadata("verified", true)
            .build()

        assertEquals(3, response.audit.custom.size)
        assertEquals(JsonPrimitive("123"), response.audit.custom["user_id"])
        assertEquals(JsonPrimitive(5), response.audit.custom["count"])
        assertEquals(JsonPrimitive(true), response.audit.custom["verified"])
    }

    @Test
    fun `rule ids`() {
        val response = Decision.deny()
            .withRuleId("SQLI-001")
            .withRuleIds("XSS-001", "XSS-002")
            .build()

        assertEquals(3, response.audit.ruleIds.size)
        assertTrue("SQLI-001" in response.audit.ruleIds)
        assertTrue("XSS-001" in response.audit.ruleIds)
    }

    @Test
    fun `confidence is clamped`() {
        val response1 = Decision.deny().withConfidence(0.95f).build()
        assertEquals(0.95f, response1.audit.confidence)

        val response2 = Decision.deny().withConfidence(1.5f).build()
        assertEquals(1.0f, response2.audit.confidence)

        val response3 = Decision.deny().withConfidence(-0.5f).build()
        assertEquals(0.0f, response3.audit.confidence)
    }

    @Test
    fun `reason codes`() {
        val response = Decision.deny()
            .withReasonCode("POLICY_VIOLATION")
            .withReasonCodes("GEO_BLOCKED", "IP_BLACKLISTED")
            .build()

        assertEquals(3, response.audit.reasonCodes.size)
        assertTrue("POLICY_VIOLATION" in response.audit.reasonCodes)
    }

    @Test
    fun `routing metadata`() {
        val response = Decision.allow()
            .withRoutingMetadata("upstream", "backend-v2")
            .withRoutingMetadata("weight", "100")
            .build()

        assertEquals(2, response.routingMetadata.size)
        assertEquals("backend-v2", response.routingMetadata["upstream"])
    }

    @Test
    fun `needs more data`() {
        val response = Decision.allow().needsMoreData().build()
        assertTrue(response.needsMore)
    }

    @Test
    fun `request body mutation`() {
        val response = Decision.allow()
            .withRequestBodyMutation(BodyMutation.replace(0, "modified"))
            .build()

        assertEquals("modified", response.requestBodyMutation?.data)
        assertEquals(0, response.requestBodyMutation?.chunkIndex)
    }

    @Test
    fun `response body mutation`() {
        val response = Decision.allow()
            .withResponseBodyMutation(BodyMutation.dropChunk(1))
            .build()

        assertEquals("", response.responseBodyMutation?.data)
        assertEquals(1, response.responseBodyMutation?.chunkIndex)
        assertTrue(response.responseBodyMutation?.isDrop() == true)
    }

    @Test
    fun `body mutation pass through`() {
        val mutation = BodyMutation.passThrough(2)
        assertNull(mutation.data)
        assertEquals(2, mutation.chunkIndex)
        assertTrue(mutation.isPassThrough())
    }

    @Test
    fun `challenge creates challenge decision`() {
        val response = Decision.challenge("captcha", mapOf("site_key" to "abc123")).build()
        val decision = response.decision as ProtocolDecision.Challenge
        assertEquals("captcha", decision.challengeType)
        assertEquals("abc123", decision.params["site_key"])
    }

    @Test
    fun `challenge with default params`() {
        val response = Decision.challenge("js_challenge").build()
        val decision = response.decision as ProtocolDecision.Challenge
        assertEquals("js_challenge", decision.challengeType)
        assertTrue(decision.params.isEmpty())
    }
}
