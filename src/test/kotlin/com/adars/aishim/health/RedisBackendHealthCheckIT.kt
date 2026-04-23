package com.adars.aishim.health

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.junit.QuarkusTestProfile
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test

/**
 * Integration-style tests for the readiness endpoint wiring. Two scenarios:
 *
 *  1. Default test profile (backend=memory) — asserts RedisBackendHealthCheck is
 *     registered, reports UP, and crucially that the old Quarkus-extension probe
 *     ("Redis connection health check") is NOT in the response. This is the
 *     regression guard for the bug we just fixed: build-time flag ignored so the
 *     extension probe would sneak back in.
 *
 *  2. Redis profile pointed at an unreachable port — asserts the endpoint returns
 *     503, the redis-backend check is DOWN with a sensible error, and no other
 *     Redis probe is interfering.
 */
@QuarkusTest
class RedisBackendHealthCheckDefaultProfileTest {

    @Test
    fun `redis-backend check is present and UP in memory mode`() {
        given().`when`().get("/q/health/ready").then()
            .statusCode(200)
            .body("checks.name", hasItem("redis-backend"))
            .body("checks.find{it.name=='redis-backend'}.status", equalTo("UP"))
            .body("checks.find{it.name=='redis-backend'}.data.backend", equalTo("memory"))
            .body("checks.find{it.name=='redis-backend'}.data.redis", equalTo("not-used"))
    }

    @Test
    fun `built-in Quarkus redis probe is disabled so only our custom check runs`() {
        // Regression guard: the fix hinges on quarkus.redis.health.enabled=false
        // being hardcoded. If someone re-introduces the env-var indirection and
        // the flag flips back on at build time, this test fails.
        given().`when`().get("/q/health/ready").then()
            .statusCode(200)
            .body("checks.name", not(hasItem("Redis connection health check")))
    }
}

@QuarkusTest
@TestProfile(RedisBackendHealthCheckRedisDownTest.RedisUnreachableProfile::class)
class RedisBackendHealthCheckRedisDownTest {

    class RedisUnreachableProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "aishim.ratelimit.backend" to "redis",
            // Port 1 is in the reserved range and nothing should be listening there —
            // produces a deterministic ConnectException instead of depending on a real
            // "empty" port that might be racy in CI.
            "quarkus.redis.hosts" to "redis://localhost:1",
            // Speed up the failure path so the test doesn't wait on the default Redis
            // client timeout when the connection is refused.
            "quarkus.redis.timeout" to "500ms",
        )
    }

    @Test
    fun `redis-backend reports DOWN and endpoint returns 503 when Redis unreachable`() {
        given().`when`().get("/q/health/ready").then()
            .statusCode(503)
            .body("status", equalTo("DOWN"))
            .body("checks.find{it.name=='redis-backend'}.status", equalTo("DOWN"))
            .body("checks.find{it.name=='redis-backend'}.data.backend", equalTo("redis"))
            .body("checks.find{it.name=='redis-backend'}.data.error", containsString("")) // error present, contents vary
    }

    @Test
    fun `redis-down response still omits the legacy built-in probe`() {
        given().`when`().get("/q/health/ready").then()
            .statusCode(503)
            .body("checks.name", not(hasItem("Redis connection health check")))
    }
}
