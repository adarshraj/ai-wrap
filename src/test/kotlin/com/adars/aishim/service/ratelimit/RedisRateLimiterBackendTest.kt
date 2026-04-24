package com.adars.aishim.service.ratelimit

import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.redis.datasource.keys.KeyCommands
import io.quarkus.redis.datasource.value.ValueCommands
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration

/**
 * Covers the INCR+conditional-EXPIRE protocol on all three paths:
 *   1. first increment → EXPIRE is called
 *   2. subsequent increment → EXPIRE is skipped
 *   3. EXPIRE failure on first increment → swallowed, count still returned
 */
class RedisRateLimiterBackendTest {

    private fun wire(): Triple<RedisRateLimiterBackend, ValueCommands<String, Long>, KeyCommands<String>> {
        val ds = mock<RedisDataSource>()
        val value = mock<ValueCommands<String, Long>>()
        val keys = mock<KeyCommands<String>>()
        whenever(ds.value(String::class.java, Long::class.java)).thenReturn(value)
        whenever(ds.key(String::class.java)).thenReturn(keys)
        return Triple(RedisRateLimiterBackend(ds), value, keys)
    }

    @Test
    fun `first increment sets TTL`() {
        val (backend, value, keys) = wire()
        whenever(value.incr("k1")).thenReturn(1L)

        val count = backend.incrementWithTtl("k1", 60L)

        assertEquals(1L, count)
        verify(keys).expire(eq("k1"), eq(Duration.ofSeconds(60L)))
    }

    @Test
    fun `subsequent increment does not reset TTL`() {
        val (backend, value, keys) = wire()
        whenever(value.incr("k2")).thenReturn(2L)

        val count = backend.incrementWithTtl("k2", 60L)

        assertEquals(2L, count)
        verify(keys, never()).expire(any<String>(), any<Duration>())
    }

    @Test
    fun `high count leaves TTL untouched`() {
        val (backend, value, keys) = wire()
        whenever(value.incr("k3")).thenReturn(9_999L)

        val count = backend.incrementWithTtl("k3", 3600L)

        assertEquals(9_999L, count)
        verify(keys, never()).expire(any<String>(), any<Duration>())
    }

    @Test
    fun `EXPIRE failure on first increment is swallowed and count returned`() {
        val (backend, value, keys) = wire()
        whenever(value.incr("k4")).thenReturn(1L)
        doThrow(RuntimeException("redis down"))
            .whenever(keys).expire(eq("k4"), any<Duration>())

        val count = backend.incrementWithTtl("k4", 120L)

        assertEquals(1L, count)
        verify(keys).expire(eq("k4"), eq(Duration.ofSeconds(120L)))
    }

    @Test
    fun `INCR failure propagates to caller`() {
        val (backend, value, _) = wire()
        whenever(value.incr("k5")).thenThrow(RuntimeException("boom"))

        val err = runCatching { backend.incrementWithTtl("k5", 60L) }.exceptionOrNull()
        assertEquals("boom", err?.message)
    }

    @Test
    fun `different TTL values are passed through verbatim`() {
        val (backend, value, keys) = wire()
        whenever(value.incr(any<String>())).thenReturn(1L)

        backend.incrementWithTtl("daily", 86_400L)
        backend.incrementWithTtl("minute", 60L)

        verify(keys).expire(eq("daily"), eq(Duration.ofSeconds(86_400L)))
        verify(keys).expire(eq("minute"), eq(Duration.ofSeconds(60L)))
    }
}
