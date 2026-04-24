package com.adars.aishim.config

import io.smallrye.config.ConfigSourceInterceptorContext
import io.smallrye.config.ConfigValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Pure unit tests — no Quarkus context needed because the interceptor is just
 * a function over (name, ConfigValue?) → ConfigValue?.
 */
class BlankAsMissingConfigInterceptorTest {

    private val interceptor = BlankAsMissingConfigInterceptor()

    private fun ctx(name: String, value: String?): ConfigSourceInterceptorContext {
        val cv = if (value == null) null else ConfigValue.builder().withName(name).withValue(value).build()
        return mock<ConfigSourceInterceptorContext>().also {
            whenever(it.proceed(eq(name))).thenReturn(cv)
        }
    }

    @Test
    fun `empty string is treated as missing`() {
        val result = interceptor.getValue(ctx("api-key", ""), "api-key")
        assertThat(result).isNull()
    }

    @Test
    fun `whitespace-only value is treated as missing`() {
        val result = interceptor.getValue(ctx("api-key", "   \t \n"), "api-key")
        assertThat(result).isNull()
    }

    @Test
    fun `actual missing property remains null`() {
        val result = interceptor.getValue(ctx("api-key", null), "api-key")
        assertThat(result).isNull()
    }

    @Test
    fun `non-blank value passes through unchanged`() {
        val result = interceptor.getValue(ctx("api-key", "sk-real-key"), "api-key")
        assertThat(result).isNotNull
        assertThat(result!!.value).isEqualTo("sk-real-key")
    }

    @Test
    fun `value with leading and trailing whitespace passes through`() {
        // Only fully-blank values are dropped — preserving leading/trailing space matters
        // for properties that legitimately encode it (e.g. multi-line values).
        val result = interceptor.getValue(ctx("desc", "  hello  "), "desc")
        assertThat(result).isNotNull
        assertThat(result!!.value).isEqualTo("  hello  ")
    }
}
