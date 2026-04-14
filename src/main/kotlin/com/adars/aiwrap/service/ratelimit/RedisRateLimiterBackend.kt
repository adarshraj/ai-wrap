package com.adars.aiwrap.service.ratelimit

import io.quarkus.arc.lookup.LookupIfProperty
import io.quarkus.redis.datasource.RedisDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Duration

/**
 * Redis-backed counter for multi-replica deployments. Selected when
 * `aiwrap.ratelimit.backend=redis`. The Quarkus Redis datasource is configured via
 * standard `quarkus.redis.*` properties (host, password, TLS, etc).
 *
 * Protocol: `INCR` followed by a conditional `EXPIRE` — the TTL is set only on the first
 * increment (count == 1), so subsequent increments within the same window do not slide the
 * expiry. On any Redis failure the call throws; the caller treats this as a 500.
 */
@ApplicationScoped
@LookupIfProperty(name = "aiwrap.ratelimit.backend", stringValue = "redis")
class RedisRateLimiterBackend @Inject constructor(
    private val redis: RedisDataSource,
) : RateLimiterBackend {

    private val value = redis.value(String::class.java, Long::class.java)
    private val keyCommands = redis.key(String::class.java)

    override fun incrementWithTtl(key: String, ttlSeconds: Long): Long {
        val count = value.incr(key)
        if (count == 1L) {
            try {
                keyCommands.expire(key, Duration.ofSeconds(ttlSeconds))
            } catch (e: Exception) {
                log.warnf("Failed to set TTL on rate-limit key %s: %s", key, e.message)
            }
        }
        return count
    }

    companion object {
        private val log: Logger = Logger.getLogger(RedisRateLimiterBackend::class.java)
    }
}
