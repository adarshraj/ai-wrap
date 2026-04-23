package com.adars.aishim.health

import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.redis.datasource.value.ValueCommands
import jakarta.enterprise.inject.Instance
import org.eclipse.microprofile.health.HealthCheckResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for RedisBackendHealthCheck. Covers every branch without needing a real
 * Redis: mocks RedisDataSource via `Instance<T>` so we can simulate resolvable/unresolvable
 * beans and successful / failing pings.
 *
 * The integration-style path (property-driven branch with a wired container) is covered
 * separately in RedisBackendHealthCheckRedisDownIT.
 */
class RedisBackendHealthCheckTest {

    private fun mockInstance(bean: RedisDataSource?, resolvable: Boolean = bean != null): Instance<RedisDataSource> {
        val inst = mock<Instance<RedisDataSource>>()
        whenever(inst.isResolvable).thenReturn(resolvable)
        if (bean != null) whenever(inst.get()).thenReturn(bean)
        return inst
    }

    @Test
    fun `memory backend reports UP without touching Redis`() {
        val redis = mock<RedisDataSource>()
        val check = RedisBackendHealthCheck(backend = "memory", redis = mockInstance(redis))

        val r = check.call()

        assertEquals(HealthCheckResponse.Status.UP, r.status)
        assertEquals("redis-backend", r.name)
        val data = r.data.orElseThrow()
        assertEquals("memory", data["backend"])
        assertEquals("not-used", data["redis"])
        // Critical: memory path must not touch Redis at all — no value() / get() calls.
        verify(redis, never()).value(any<Class<String>>(), any<Class<String>>())
    }

    @Test
    fun `unknown backend string also reports UP without touching Redis`() {
        // Defensive: any value other than "redis" is treated as not-using-Redis so a typo
        // in config doesn't wedge the pod at readiness time.
        val redis = mock<RedisDataSource>()
        val check = RedisBackendHealthCheck(backend = "typo", redis = mockInstance(redis))

        val r = check.call()

        assertEquals(HealthCheckResponse.Status.UP, r.status)
        val data = r.data.orElseThrow()
        assertEquals("typo", data["backend"])
        assertEquals("not-used", data["redis"])
        verify(redis, never()).value(any<Class<String>>(), any<Class<String>>())
    }

    @Test
    fun `redis backend with successful ping reports UP`() {
        val valueCmds = mock<ValueCommands<String, String>>()
        whenever(valueCmds.get(eq("__aishim_health_probe__"))).thenReturn(null)
        val redis = mock<RedisDataSource>()
        whenever(redis.value(eq(String::class.java), eq(String::class.java))).thenReturn(valueCmds)

        val check = RedisBackendHealthCheck(backend = "redis", redis = mockInstance(redis))

        val r = check.call()

        assertEquals(HealthCheckResponse.Status.UP, r.status)
        assertEquals("redis", r.data.orElseThrow()["backend"])
        verify(valueCmds).get(eq("__aishim_health_probe__"))
    }

    @Test
    fun `redis backend with unresolvable bean reports DOWN`() {
        // Simulates the pathological case where the classpath has the extension but
        // the datasource bean wasn't produced (e.g. config mis-binding). We should
        // degrade to DOWN with a clear reason rather than crash the probe.
        val check = RedisBackendHealthCheck(backend = "redis", redis = mockInstance(bean = null, resolvable = false))

        val r = check.call()

        assertEquals(HealthCheckResponse.Status.DOWN, r.status)
        val data = r.data.orElseThrow()
        assertEquals("redis", data["backend"])
        val err = data["error"]?.toString() ?: ""
        assertTrue(err.contains("not resolvable"), "expected 'not resolvable' in error, got: $err")
    }

    @Test
    fun `redis backend with ping exception reports DOWN with error message`() {
        val valueCmds = mock<ValueCommands<String, String>>()
        // Must be an unchecked exception: ValueCommands.get doesn't declare any, so
        // Mockito rejects checked throwables here. At runtime the Redis client wraps
        // connection failures in a RuntimeException subclass anyway (verified by the
        // integration test), so this still covers the real error path.
        whenever(valueCmds.get(any<String>())).doThrow(RuntimeException("Connection refused"))
        val redis = mock<RedisDataSource>()
        whenever(redis.value(eq(String::class.java), eq(String::class.java))).thenReturn(valueCmds)

        val check = RedisBackendHealthCheck(backend = "redis", redis = mockInstance(redis))

        val r = check.call()

        assertEquals(HealthCheckResponse.Status.DOWN, r.status)
        val data = r.data.orElseThrow()
        assertEquals("redis", data["backend"])
        assertEquals("Connection refused", data["error"])
    }

    @Test
    fun `ping exception without message falls back to exception class name`() {
        // Some runtime exceptions arrive with a null message. The probe must still
        // surface something meaningful rather than letting the response builder throw.
        val valueCmds = mock<ValueCommands<String, String>>()
        whenever(valueCmds.get(any<String>())).doThrow(RuntimeException())
        val redis = mock<RedisDataSource>()
        whenever(redis.value(eq(String::class.java), eq(String::class.java))).thenReturn(valueCmds)

        val check = RedisBackendHealthCheck(backend = "redis", redis = mockInstance(redis))

        val r = check.call()

        assertEquals(HealthCheckResponse.Status.DOWN, r.status)
        val err = r.data.orElseThrow()["error"]
        assertNotNull(err)
        assertEquals("RuntimeException", err)
    }

    @Test
    fun `response name is stable across branches`() {
        // A consumer (dashboard, alert rule) keys off the check name. If it ever
        // drifts silently, their filters go blind. Pin it in a test.
        val redis = mock<RedisDataSource>()
        val memoryResp = RedisBackendHealthCheck("memory", mockInstance(redis)).call()
        val unresolvedResp = RedisBackendHealthCheck("redis", mockInstance(bean = null, resolvable = false)).call()
        assertEquals("redis-backend", memoryResp.name)
        assertEquals("redis-backend", unresolvedResp.name)
    }
}
