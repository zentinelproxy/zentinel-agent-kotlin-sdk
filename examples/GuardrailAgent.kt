package io.raskell.zentinel.agent.examples

import io.raskell.zentinel.agent.*

/**
 * Guardrail agent example for AI content safety.
 *
 * This example demonstrates a guardrail agent that:
 * - Detects prompt injection attempts in user input
 * - Detects PII (emails, phone numbers, SSN patterns)
 * - Returns structured detection results with confidence scores
 */
class GuardrailAgent : Agent {
    override val name = "guardrail-agent"

    // Prompt injection patterns
    private val injectionPatterns = listOf(
        Regex("""(?i)ignore\s+(all\s+)?(previous|prior|above)\s+(instructions?|prompts?)""") to "ignore_instructions",
        Regex("""(?i)disregard\s+(all\s+)?(previous|prior|above)""") to "disregard_previous",
        Regex("""(?i)you\s+are\s+now\s+(a|an|in)\s+""") to "role_switch",
        Regex("""(?i)pretend\s+(you('re|are)|to\s+be)""") to "pretend_role",
        Regex("""(?i)system\s*:\s*""") to "system_prompt_inject",
        Regex("""\[INST\]|\[/INST\]|<<SYS>>|<</SYS>>""") to "llama_format_inject",
        Regex("""<\|im_start\|>|<\|im_end\|>""") to "chatml_format_inject",
    )

    // PII patterns
    private val piiPatterns = listOf(
        Triple(Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""), "email", "Email address"),
        Triple(Regex("""\b\d{3}[-.]?\d{3}[-.]?\d{4}\b"""), "phone", "Phone number"),
        Triple(Regex("""\b\d{3}[-]?\d{2}[-]?\d{4}\b"""), "ssn", "Social Security Number"),
        Triple(Regex("""\b\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}\b"""), "credit_card", "Credit card number"),
    )

    override suspend fun onRequest(request: Request): Decision {
        // Allow all requests - guardrail inspection happens via onGuardrailInspect
        return Decision.allow()
    }

    override suspend fun onGuardrailInspect(event: GuardrailInspectEvent): GuardrailResponse {
        return when (event.inspectionType) {
            GuardrailInspectionType.PROMPT_INJECTION -> detectPromptInjection(event.content)
            GuardrailInspectionType.PII_DETECTION -> detectPii(event.content)
        }
    }

    private fun detectPromptInjection(content: String): GuardrailResponse {
        var response = GuardrailResponse.clean()

        for ((pattern, category) in injectionPatterns) {
            val match = pattern.find(content)
            if (match != null) {
                val detection = GuardrailDetection(
                    category = "prompt_injection.$category",
                    description = "Potential prompt injection detected: ${category.replace("_", " ")}",
                    severity = DetectionSeverity.HIGH,
                    confidence = 0.85,
                    span = TextSpan(match.range.first, match.range.last + 1)
                )
                response = response.addDetection(detection)
            }
        }

        return response
    }

    private fun detectPii(content: String): GuardrailResponse {
        var response = GuardrailResponse.clean()
        var redacted = content

        for ((pattern, category, description) in piiPatterns) {
            for (match in pattern.findAll(content)) {
                val detection = GuardrailDetection(
                    category = "pii.$category",
                    description = "$description detected",
                    severity = DetectionSeverity.MEDIUM,
                    confidence = 0.95,
                    span = TextSpan(match.range.first, match.range.last + 1)
                )
                response = response.addDetection(detection)
                redacted = redacted.replace(match.value, "[REDACTED_${category.uppercase()}]")
            }
        }

        if (response.detected) {
            response = response.withRedactedContent(redacted)
        }

        return response
    }
}

fun main(args: Array<String>) {
    runAgent(GuardrailAgent(), args)
}
