package com.adars.aishim.filter

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.PreMatching
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider

/**
 * Rejects HTTP methods other than GET, POST, and OPTIONS with 405 Method Not Allowed.
 * OPTIONS is kept for CORS preflight. All other methods (PUT, DELETE, PATCH, etc.)
 * are explicitly blocked rather than relying on JAX-RS default routing.
 */
@Provider
@PreMatching
class MethodFilter : ContainerRequestFilter {

    private val ALLOWED = setOf("GET", "POST", "OPTIONS", "HEAD")

    override fun filter(ctx: ContainerRequestContext) {
        if (ctx.method !in ALLOWED) {
            ctx.abortWith(
                Response.status(405)
                    .header("Allow", "GET, POST, OPTIONS")
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity("{\"error\":\"Method ${ctx.method} is not allowed.\"}")
                    .build()
            )
        }
    }
}
