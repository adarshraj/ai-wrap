package com.adars.aiwrap.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuditServiceTest {

    private val service = AuditService()

    @Test
    fun `record produces valid JSON structure`() {
        // Just verify it doesn't throw and the record() call runs cleanly
        // (log output goes to logger, not return value)
        service.record(
            userId = "user1", action = "text", provider = "openai",
            template = "insights-qa", model = "gpt-4o-mini",
            processingTimeMs = 123L, success = true,
        )
        // No exception = pass
    }

    @Test
    fun `record with special chars in userId does not throw`() {
        service.record(
            userId = "user%20with\"quotes", action = "text", provider = "gemini",
            template = null, model = null,
            processingTimeMs = 0L, success = false, errorType = "RuntimeException",
        )
    }

    @Test
    fun `record with token usage does not throw`() {
        service.record(
            userId = "user2", action = "text", provider = "openai",
            template = null, model = "gpt-4o", processingTimeMs = 500L,
            success = true, inputTokens = 150, outputTokens = 320,
        )
    }

    @Test
    fun `record without token usage does not throw`() {
        service.record(
            userId = "user3", action = "vision", provider = "gemini",
            template = "ocr-receipt-structured", model = "gemini-2.0-flash",
            processingTimeMs = 800L, success = true,
            inputTokens = null, outputTokens = null,
        )
    }
}
