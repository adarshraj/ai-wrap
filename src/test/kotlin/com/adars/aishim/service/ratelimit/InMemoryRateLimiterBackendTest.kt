package com.adars.aishim.service.ratelimit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class InMemoryRateLimiterBackendTest {

    @Test
    fun `first increment returns 1`() {
        val backend = InMemoryRateLimiterBackend()
        assertThat(backend.incrementWithTtl("alice:minute", 60)).isEqualTo(1)
    }

    @Test
    fun `subsequent increments accumulate within window`() {
        val backend = InMemoryRateLimiterBackend()
        repeat(4) { backend.incrementWithTtl("alice:minute", 60) }
        assertThat(backend.incrementWithTtl("alice:minute", 60)).isEqualTo(5)
    }

    @Test
    fun `different keys are isolated`() {
        val backend = InMemoryRateLimiterBackend()
        backend.incrementWithTtl("alice:minute", 60)
        backend.incrementWithTtl("alice:minute", 60)
        assertThat(backend.incrementWithTtl("bob:minute", 60)).isEqualTo(1)
    }

    @Test
    fun `expired window resets the counter`() {
        // 0-second TTL = entry is born already expired, so each call sees a fresh window.
        val backend = InMemoryRateLimiterBackend()
        val first = backend.incrementWithTtl("alice", 0)
        Thread.sleep(5)
        val second = backend.incrementWithTtl("alice", 0)
        assertThat(first).isEqualTo(1)
        assertThat(second).isEqualTo(1)
    }

    @Test
    fun `expired entries are swept lazily on next call`() {
        // With ttl=0 the entry is expired immediately. Sweep happens at the start of each
        // call, so requesting a different key should clear the stale one.
        val backend = InMemoryRateLimiterBackend()
        backend.incrementWithTtl("ghost", 0)
        Thread.sleep(2)
        backend.incrementWithTtl("anyone", 60) // triggers sweep

        // Re-asking ghost should also start fresh (1, not 2).
        assertThat(backend.incrementWithTtl("ghost", 60)).isEqualTo(1)
    }

    @Test
    fun `concurrent increments produce exact total`() {
        // Ensures the AtomicLong + ConcurrentHashMap.compute combo is race-free.
        val backend = InMemoryRateLimiterBackend()
        val pool = Executors.newFixedThreadPool(16)
        val iterations = 5000
        val maxObserved = AtomicLong(0)
        try {
            val futures = (1..iterations).map {
                pool.submit {
                    val v = backend.incrementWithTtl("hot", 60)
                    maxObserved.updateAndGet { prev -> if (v > prev) v else prev }
                }
            }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertThat(maxObserved.get()).isEqualTo(iterations.toLong())
    }
}
