package com.adars.aishim.filter

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MethodFilterTest {

    private val filter = MethodFilter()

    private fun ctx(method: String): ContainerRequestContext =
        mock<ContainerRequestContext>().also { whenever(it.method).thenReturn(method) }

    @Test
    fun `GET is allowed`() {
        val c = ctx("GET")
        filter.filter(c)
        verify(c, never()).abortWith(org.mockito.kotlin.any())
    }

    @Test
    fun `POST is allowed`() {
        val c = ctx("POST")
        filter.filter(c)
        verify(c, never()).abortWith(org.mockito.kotlin.any())
    }

    @Test
    fun `OPTIONS is allowed for CORS preflight`() {
        val c = ctx("OPTIONS")
        filter.filter(c)
        verify(c, never()).abortWith(org.mockito.kotlin.any())
    }

    @Test
    fun `HEAD is allowed`() {
        val c = ctx("HEAD")
        filter.filter(c)
        verify(c, never()).abortWith(org.mockito.kotlin.any())
    }

    @Test
    fun `PUT is rejected with 405 and Allow header`() {
        val c = ctx("PUT")
        val captor = argumentCaptor<Response>()
        filter.filter(c)
        verify(c).abortWith(captor.capture())

        val resp = captor.firstValue
        assertThat(resp.status).isEqualTo(405)
        assertThat(resp.getHeaderString("Allow")).isEqualTo("GET, POST, OPTIONS")
        assertThat(resp.entity.toString()).contains("PUT").contains("not allowed")
    }

    @Test
    fun `DELETE is rejected with 405`() {
        val c = ctx("DELETE")
        filter.filter(c)
        verify(c).abortWith(org.mockito.kotlin.any())
    }

    @Test
    fun `PATCH is rejected with 405`() {
        val c = ctx("PATCH")
        filter.filter(c)
        verify(c).abortWith(org.mockito.kotlin.any())
    }

    @Test
    fun `unknown verb is rejected with 405`() {
        val c = ctx("TRACE")
        filter.filter(c)
        verify(c).abortWith(org.mockito.kotlin.any())
    }
}
