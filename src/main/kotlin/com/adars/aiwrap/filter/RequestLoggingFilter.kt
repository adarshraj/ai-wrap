package com.adars.aiwrap.filter

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.PreMatching
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger
import org.slf4j.MDC
import java.util.UUID

internal const val REQUEST_ID_KEY = "aiwrap.requestId"
internal const val REQUEST_START_KEY = "aiwrap.startTime"

/**
 * Runs before every HTTP request.
 *
 * - Generates a short unique request ID (8 hex chars).
 * - Puts it in SLF4J MDC so every log line emitted during this request automatically
 *   includes the ID (via the `%X{requestId}` token in the log format).
 * - Stores it and the start timestamp in request properties for the response filter.
 */
@Provider
@PreMatching
class RequestLoggingFilter : ContainerRequestFilter {

    private val log: Logger = Logger.getLogger(RequestLoggingFilter::class.java)

    override fun filter(ctx: ContainerRequestContext) {
        val requestId = UUID.randomUUID().toString().replace("-", "").take(8)
        val startTime = System.currentTimeMillis()

        MDC.put("requestId", requestId)
        ctx.setProperty(REQUEST_ID_KEY, requestId)
        ctx.setProperty(REQUEST_START_KEY, startTime)

        log.infof("→ %s %s", ctx.method, ctx.uriInfo.requestUri.path)
    }
}
