package com.adars.aishim.filter

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider

/**
 * Adds OWASP-recommended security headers to every HTTP response.
 */
@Provider
class SecurityHeadersFilter : ContainerResponseFilter {
    override fun filter(req: ContainerRequestContext, res: ContainerResponseContext) {
        res.headers.apply {
            putSingle("X-Content-Type-Options", "nosniff")
            putSingle("X-Frame-Options", "DENY")
            putSingle("Referrer-Policy", "strict-origin-when-cross-origin")
            putSingle("X-XSS-Protection", "0")
            putSingle("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        }
    }
}
