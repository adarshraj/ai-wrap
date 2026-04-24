package com.adars.aishim.filter

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.MultivaluedHashMap
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SecurityHeadersFilterTest {

    private val filter = SecurityHeadersFilter()

    @Test
    fun `adds all OWASP-recommended security headers`() {
        val headers = MultivaluedHashMap<String, Any>()
        val req = mock<ContainerRequestContext>()
        val res = mock<ContainerResponseContext>().also { whenever(it.headers).thenReturn(headers) }

        filter.filter(req, res)

        assertThat(headers["X-Content-Type-Options"]).containsExactly("nosniff")
        assertThat(headers["X-Frame-Options"]).containsExactly("DENY")
        assertThat(headers["Referrer-Policy"]).containsExactly("strict-origin-when-cross-origin")
        assertThat(headers["X-XSS-Protection"]).containsExactly("0")
        assertThat(headers["Permissions-Policy"]).containsExactly("camera=(), microphone=(), geolocation=()")
    }

    @Test
    fun `existing security header values are overwritten via putSingle`() {
        // Why putSingle rather than add: a misconfigured upstream could set X-Frame-Options=ALLOWALL
        // and we want our value to win, not to be appended alongside.
        val headers = MultivaluedHashMap<String, Any>()
        headers.add("X-Frame-Options", "ALLOWALL")
        val req = mock<ContainerRequestContext>()
        val res = mock<ContainerResponseContext>().also { whenever(it.headers).thenReturn(headers) }

        filter.filter(req, res)

        assertThat(headers["X-Frame-Options"]).containsExactly("DENY")
    }
}
