package com.adars.aiwrap.service

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-user sliding-minute rate limiter with daily quota.
 *
 * Counts requests per user per minute using atomic counters keyed by "userId:minuteBucket"
 * where minuteBucket is [System.currentTimeMillis] / 60_000. Old buckets are evicted on
 * each check to prevent unbounded memory growth.
 *
 * Also enforces a daily request limit keyed by "userId:date".
 */
@ApplicationScoped
class RateLimiter @jakarta.inject.Inject constructor(
    @ConfigProperty(name = "aiwrap.rate-limit.requests-per-minute", defaultValue = "30")
    val requestsPerMinute: Long,
    @ConfigProperty(name = "aiwrap.rate-limit.requests-per-day", defaultValue = "1000")
    val requestsPerDay: Long = 1000L,
) {
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val dailyCounters = ConcurrentHashMap<String, AtomicLong>()

    companion object {
        private val log = Logger.getLogger(RateLimiter::class.java)
    }

    private fun secondsUntilMidnight(): Long {
        val now = LocalDateTime.now()
        val midnight = LocalDate.now().plusDays(1).atStartOfDay()
        return java.time.Duration.between(now, midnight).seconds.coerceAtLeast(0L)
    }

    /** Returns remaining requests allowed in the current minute after this one is counted. */
    fun check(userId: String): Long {
        val bucket = System.currentTimeMillis() / 60_000L
        val key = "$userId:$bucket"

        // Evict counters from previous minutes
        counters.keys.removeIf { k ->
            (k.substringAfterLast(':').toLongOrNull() ?: 0L) < bucket
        }

        val count = counters.getOrPut(key) { AtomicLong(0) }.incrementAndGet()
        if (count > requestsPerMinute) {
            log.warnf("Rate limit exceeded for user=%s count=%d limit=%d", userId, count, requestsPerMinute)
            throw RateLimitExceededException(
                "Rate limit exceeded: maximum $requestsPerMinute requests per minute.",
                limit = requestsPerMinute,
                retryAfterSeconds = 60L,
            )
        }

        // Evict daily counters from previous days
        val today = LocalDate.now()
        dailyCounters.keys.removeIf { k ->
            val keyDate = runCatching { LocalDate.parse(k.substringAfterLast(':')) }.getOrNull()
            keyDate != null && keyDate.isBefore(today)
        }

        val dayKey = "$userId:$today"
        val dayCount = dailyCounters.compute(dayKey) { _, v -> (v ?: AtomicLong(0)).also { it.incrementAndGet() } }?.get() ?: 0
        if (dayCount > requestsPerDay) {
            log.warnf("Daily rate limit exceeded for user=%s count=%d limit=%d", userId, dayCount, requestsPerDay)
            throw RateLimitExceededException(
                "Daily request limit of $requestsPerDay exceeded. Try again tomorrow.",
                limit = requestsPerDay,
                retryAfterSeconds = secondsUntilMidnight(),
            )
        }

        return (requestsPerMinute - count).coerceAtLeast(0L)
    }
}

class RateLimitExceededException(
    message: String,
    val limit: Long,
    val retryAfterSeconds: Long,
) : RuntimeException(message)
