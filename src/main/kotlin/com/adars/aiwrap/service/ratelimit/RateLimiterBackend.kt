package com.adars.aiwrap.service.ratelimit

/**
 * Storage backend for [com.adars.aiwrap.service.RateLimiter].
 *
 * Implementations provide an atomic "increment-and-return-count" primitive with a TTL.
 * Selecting a backend at startup is driven by the `aiwrap.ratelimit.backend` config property:
 *
 * - `memory` (default) — in-process counters. Fine for single-replica deployments and local dev.
 * - `redis` — Quarkus Redis client. Use this whenever more than one ai-wrap replica is running
 *   so that counters are shared across pods.
 */
interface RateLimiterBackend {
    /**
     * Atomically increments the counter stored at [key], setting its TTL to [ttlSeconds] on first
     * creation, and returns the new value.
     */
    fun incrementWithTtl(key: String, ttlSeconds: Long): Long
}
