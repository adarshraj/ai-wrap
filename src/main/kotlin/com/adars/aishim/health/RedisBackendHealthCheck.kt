package com.adars.aishim.health

import io.quarkus.redis.datasource.RedisDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Readiness

/**
 * Readiness check for the rate-limiter Redis backend.
 *
 * Reads `aishim.ratelimit.backend` at *runtime* so the probe reflects the actual
 * runtime configuration. Replaces Quarkus's built-in `quarkus.redis.health.enabled`
 * probe, which is a build-time property and therefore can't be flipped per deployment
 * without rebuilding the JAR.
 *
 * Semantics:
 *  - backend=memory  → Redis isn't used; report UP with `redis=not-used`.
 *  - backend=redis   → ping Redis; report UP on success, DOWN with the error otherwise.
 */
@Readiness
@ApplicationScoped
class RedisBackendHealthCheck @Inject constructor(
    @ConfigProperty(name = "aishim.ratelimit.backend", defaultValue = "memory")
    private val backend: String,
    private val redis: Instance<RedisDataSource>,
) : HealthCheck {

    override fun call(): HealthCheckResponse {
        val name = "redis-backend"
        if (backend != "redis") {
            return HealthCheckResponse.named(name)
                .up()
                .withData("backend", backend)
                .withData("redis", "not-used")
                .build()
        }
        return try {
            if (!redis.isResolvable) {
                HealthCheckResponse.named(name)
                    .down()
                    .withData("backend", "redis")
                    .withData("error", "RedisDataSource bean not resolvable")
                    .build()
            } else {
                // Cheap liveness probe: a get on a non-existent key returns null if Redis is up,
                // or throws on connection failure.
                redis.get().value(String::class.java, String::class.java).get("__aishim_health_probe__")
                HealthCheckResponse.named(name)
                    .up()
                    .withData("backend", "redis")
                    .build()
            }
        } catch (e: Exception) {
            HealthCheckResponse.named(name)
                .down()
                .withData("backend", "redis")
                .withData("error", e.message ?: e.javaClass.simpleName)
                .build()
        }
    }
}
