package com.adars.aishim.service.ratelimit

import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process counter backend. Selected when `aishim.ratelimit.backend=memory` (the default).
 *
 * Each entry stores the count plus an absolute expiry timestamp (ms). Expired entries are swept
 * lazily on every call to avoid unbounded memory growth. Single-replica only — if you run more
 * than one ai-wrap pod behind a load balancer, switch to [RedisRateLimiterBackend] so counters
 * are shared.
 */
@ApplicationScoped
@LookupIfProperty(name = "aishim.ratelimit.backend", stringValue = "memory", lookupIfMissing = true)
class InMemoryRateLimiterBackend : RateLimiterBackend {

    private data class Entry(val count: AtomicLong, val expiresAtMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    override fun incrementWithTtl(key: String, ttlSeconds: Long): Long {
        val now = System.currentTimeMillis()
        sweepExpired(now)
        val entry = entries.compute(key) { _, existing ->
            if (existing == null || existing.expiresAtMs <= now) {
                Entry(AtomicLong(0), now + ttlSeconds * 1000L)
            } else existing
        }!!
        return entry.count.incrementAndGet()
    }

    private fun sweepExpired(now: Long) {
        entries.entries.removeIf { it.value.expiresAtMs <= now }
    }
}
