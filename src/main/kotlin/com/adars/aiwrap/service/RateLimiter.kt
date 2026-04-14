package com.adars.aiwrap.service

import com.adars.aiwrap.service.ratelimit.InMemoryRateLimiterBackend
import com.adars.aiwrap.service.ratelimit.RateLimiterBackend
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Any
import jakarta.enterprise.inject.Instance
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Per-user sliding-minute rate limiter with a daily quota.
 *
 * Storage is pluggable via [RateLimiterBackend] — the concrete bean (in-memory or Redis) is
 * chosen at startup by the `aiwrap.ratelimit.backend` config property. The limiter itself is
 * stateless apart from its config values.
 *
 * Two counters are enforced per request:
 *   1. Per-minute bucket (`rl:min:<user>:<epochMinute>`) with a 60 s TTL
 *   2. Per-day bucket (`rl:day:<user>:<yyyy-mm-dd>`) with TTL = seconds until midnight
 */
@ApplicationScoped
class RateLimiter private constructor(
    private val backend: RateLimiterBackend,
    val requestsPerMinute: Long,
    val requestsPerDay: Long,
) {

    /**
     * CDI constructor. [backends] is injected as `@Any Instance<RateLimiterBackend>` so
     * `@LookupIfProperty` on each implementation can filter to the single matching bean
     * at lookup time — direct injection of the interface is ambiguous because both beans
     * exist on the classpath.
     */
    @jakarta.inject.Inject
    constructor(
        @Any backends: Instance<RateLimiterBackend>,
        @ConfigProperty(name = "aiwrap.rate-limit.requests-per-minute", defaultValue = "30")
        requestsPerMinute: Long,
        @ConfigProperty(name = "aiwrap.rate-limit.requests-per-day", defaultValue = "1000")
        requestsPerDay: Long,
    ) : this(backends.get(), requestsPerMinute, requestsPerDay)

    /**
     * Test-friendly secondary constructor that wires an in-memory backend automatically.
     * Production code always uses the CDI constructor.
     */
    constructor(requestsPerMinute: Long, requestsPerDay: Long) :
        this(InMemoryRateLimiterBackend(), requestsPerMinute, requestsPerDay)

    companion object {
        private val log = Logger.getLogger(RateLimiter::class.java)
    }

    private fun secondsUntilMidnight(): Long {
        val now = LocalDateTime.now()
        val midnight = LocalDate.now().plusDays(1).atStartOfDay()
        return java.time.Duration.between(now, midnight).seconds.coerceAtLeast(1L)
    }

    /** Returns remaining requests allowed in the current minute after this one is counted. */
    fun check(userId: String): Long {
        val bucket = System.currentTimeMillis() / 60_000L
        val minuteKey = "rl:min:$userId:$bucket"
        val minuteCount = backend.incrementWithTtl(minuteKey, 60L)
        if (minuteCount > requestsPerMinute) {
            log.warnf("Rate limit exceeded for user=%s count=%d limit=%d", userId, minuteCount, requestsPerMinute)
            throw RateLimitExceededException(
                "Rate limit exceeded: maximum $requestsPerMinute requests per minute.",
                limit = requestsPerMinute,
                retryAfterSeconds = 60L,
            )
        }

        val today = LocalDate.now()
        val dayKey = "rl:day:$userId:$today"
        val dayCount = backend.incrementWithTtl(dayKey, secondsUntilMidnight())
        if (dayCount > requestsPerDay) {
            log.warnf("Daily rate limit exceeded for user=%s count=%d limit=%d", userId, dayCount, requestsPerDay)
            throw RateLimitExceededException(
                "Daily request limit of $requestsPerDay exceeded. Try again tomorrow.",
                limit = requestsPerDay,
                retryAfterSeconds = secondsUntilMidnight(),
            )
        }

        return (requestsPerMinute - minuteCount).coerceAtLeast(0L)
    }
}

class RateLimitExceededException(
    message: String,
    val limit: Long,
    val retryAfterSeconds: Long,
) : RuntimeException(message)
