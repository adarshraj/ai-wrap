package com.adars.aiwrap.filter

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger
import org.slf4j.MDC

/**
 * Runs after every HTTP response is ready to be sent.
 *
 * - Logs the final status, method, path, and elapsed time.
 * - Echoes the request ID back to the caller in the `X-Request-Id` response header
 *   so clients can correlate their logs with server logs.
 * - Clears the MDC to prevent request ID leaking into threads reused for other requests.
 */
@Provider
class ResponseLoggingFilter : ContainerResponseFilter {

    private val log: Logger = Logger.getLogger(ResponseLoggingFilter::class.java)

    override fun filter(req: ContainerRequestContext, res: ContainerResponseContext) {
        val requestId = req.getProperty(REQUEST_ID_KEY) as? String ?: ""
        val startTime = req.getProperty(REQUEST_START_KEY) as? Long ?: System.currentTimeMillis()
        val elapsed = System.currentTimeMillis() - startTime

        if (requestId.isNotBlank()) {
            res.headers.putSingle("X-Request-Id", requestId)
        }

        val level = if (res.status >= 500) "ERROR" else "INFO"
        if (level == "ERROR") {
            log.errorf("← %d %s %s %dms", res.status, req.method, req.uriInfo.requestUri.path, elapsed)
        } else {
            log.infof("← %d %s %s %dms", res.status, req.method, req.uriInfo.requestUri.path, elapsed)
        }

        MDC.remove("requestId")
    }
}
