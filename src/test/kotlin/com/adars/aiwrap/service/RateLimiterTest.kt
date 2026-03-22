package com.adars.aiwrap.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class RateLimiterTest {

    @Test
    fun `requests within limit all pass`() {
        val limiter = RateLimiter(requestsPerMinute = 5, requestsPerDay = 10000)
        assertDoesNotThrow { repeat(5) { limiter.check("user1") } }
    }

    @Test
    fun `request exceeding limit throws`() {
        val limiter = RateLimiter(requestsPerMinute = 2, requestsPerDay = 10000)
        limiter.check("user2")
        limiter.check("user2")
        assertThrows<RateLimitExceededException> { limiter.check("user2") }
    }

    @Test
    fun `different users have independent counters`() {
        val limiter = RateLimiter(requestsPerMinute = 1, requestsPerDay = 10000)
        limiter.check("userA")
        // userB should not be affected by userA's counter
        assertDoesNotThrow { limiter.check("userB") }
    }

    @Test
    fun `exception message contains the configured limit`() {
        val limiter = RateLimiter(requestsPerMinute = 1, requestsPerDay = 10000)
        limiter.check("user3")
        val ex = assertThrows<RateLimitExceededException> { limiter.check("user3") }
        assertThat(ex.message).contains("1")
    }

    @Test
    fun `limit of zero blocks first request`() {
        val limiter = RateLimiter(requestsPerMinute = 0, requestsPerDay = 10000)
        assertThrows<RateLimitExceededException> { limiter.check("user4") }
    }

    @Test
    fun `daily limit blocks when exceeded`() {
        val limiter = RateLimiter(requestsPerMinute = 100, requestsPerDay = 2)
        limiter.check("dayUser")
        limiter.check("dayUser")
        assertThrows<RateLimitExceededException> { limiter.check("dayUser") }
    }

    @Test
    fun `daily limit message mentions tomorrow`() {
        val limiter = RateLimiter(requestsPerMinute = 100, requestsPerDay = 0)
        val ex = assertThrows<RateLimitExceededException> { limiter.check("dayUser2") }
        assertThat(ex.message).containsIgnoringCase("tomorrow")
    }

    @Test
    fun `check returns remaining count`() {
        val limiter = RateLimiter(requestsPerMinute = 10, requestsPerDay = 10000)
        val remaining = limiter.check("remainUser")
        assertThat(remaining).isEqualTo(9L)
    }

    @Test
    fun `exception carries limit and retry-after`() {
        val limiter = RateLimiter(requestsPerMinute = 1, requestsPerDay = 10000)
        limiter.check("ex1")
        val ex = assertThrows<RateLimitExceededException> { limiter.check("ex1") }
        assertThat(ex.limit).isEqualTo(1L)
        assertThat(ex.retryAfterSeconds).isEqualTo(60L)
    }
}
