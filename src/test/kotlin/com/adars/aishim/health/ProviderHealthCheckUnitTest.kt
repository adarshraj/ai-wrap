package com.adars.aishim.health

import org.eclipse.microprofile.health.HealthCheckResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure-unit tests that construct ProviderHealthCheck directly — @QuarkusTest
 * variants run against the augmented bean, which bypasses JaCoCo instrumentation
 * on this class. Exercise every enabled/disabled branch for the five configured
 * providers. Env-var branches are asserted against whatever the host env has,
 * so the test is stable regardless of CI environment.
 */
class ProviderHealthCheckUnitTest {

    private fun check(
        openAi: String = "DISABLED",
        gemini: String = "DISABLED",
        deepSeek: String = "DISABLED",
        anthropic: String = "DISABLED",
        azure: String = "DISABLED",
    ) = ProviderHealthCheck(openAi, gemini, deepSeek, anthropic, azure).call()

    private fun hostEnvHasAnyLlmKey(): Boolean =
        listOf("GROQ_API_KEY", "OPENROUTER_API_KEY", "MISTRAL_API_KEY",
               "CEREBRAS_API_KEY", "XAI_API_KEY", "COHERE_API_KEY")
            .any { !System.getenv(it).isNullOrBlank() }

    @Test
    fun `all disabled — status follows host env-var providers`() {
        val r = check()
        val expectedUp = hostEnvHasAnyLlmKey()
        assertEquals(
            if (expectedUp) HealthCheckResponse.Status.UP else HealthCheckResponse.Status.DOWN,
            r.status,
        )
        assertEquals("ai-providers", r.name)
        val data = r.data.orElseThrow()
        assertEquals("disabled", data["openai"])
        assertEquals("disabled", data["gemini"])
        assertEquals("disabled", data["deepseek"])
        assertEquals("disabled", data["anthropic"])
        assertEquals("disabled", data["azure-openai"])
        assertEquals("available (local/cloud)", data["ollama"])
    }

    @Test
    fun `openai enabled lifts status to UP`() {
        val r = check(openAi = "sk-real-key")
        assertEquals(HealthCheckResponse.Status.UP, r.status)
        assertEquals("enabled", r.data.orElseThrow()["openai"])
    }

    @Test
    fun `gemini enabled lifts status to UP`() {
        val r = check(gemini = "AIza-real")
        assertEquals(HealthCheckResponse.Status.UP, r.status)
        assertEquals("enabled", r.data.orElseThrow()["gemini"])
    }

    @Test
    fun `deepseek enabled lifts status to UP`() {
        val r = check(deepSeek = "dsk-real")
        assertEquals(HealthCheckResponse.Status.UP, r.status)
        assertEquals("enabled", r.data.orElseThrow()["deepseek"])
    }

    @Test
    fun `anthropic enabled lifts status to UP`() {
        val r = check(anthropic = "sk-ant-real")
        assertEquals(HealthCheckResponse.Status.UP, r.status)
        assertEquals("enabled", r.data.orElseThrow()["anthropic"])
    }

    @Test
    fun `azure-openai enabled lifts status to UP`() {
        val r = check(azure = "azure-real")
        assertEquals(HealthCheckResponse.Status.UP, r.status)
        assertEquals("enabled", r.data.orElseThrow()["azure-openai"])
    }

    @Test
    fun `all five configured providers enabled simultaneously`() {
        val r = check("k1", "k2", "k3", "k4", "k5")
        assertEquals(HealthCheckResponse.Status.UP, r.status)
        val data = r.data.orElseThrow()
        listOf("openai", "gemini", "deepseek", "anthropic", "azure-openai")
            .forEach { assertEquals("enabled", data[it], "key=$it") }
    }

    @Test
    fun `env-driven providers match host environment`() {
        val data = check().data.orElseThrow()
        mapOf(
            "groq" to "GROQ_API_KEY",
            "openrouter" to "OPENROUTER_API_KEY",
            "mistral" to "MISTRAL_API_KEY",
            "cerebras" to "CEREBRAS_API_KEY",
            "xai" to "XAI_API_KEY",
            "cohere" to "COHERE_API_KEY",
        ).forEach { (name, envVar) ->
            val expected = if (System.getenv(envVar).isNullOrBlank()) "per-request only" else "enabled"
            assertEquals(expected, data[name], "provider=$name")
        }
    }

    @Test
    fun `ollama is always available regardless of other providers`() {
        assertEquals("available (local/cloud)", check().data.orElseThrow()["ollama"])
        assertEquals(
            "available (local/cloud)",
            check(openAi = "k").data.orElseThrow()["ollama"],
        )
    }

    @Test
    fun `response name is ai-providers`() {
        assertEquals("ai-providers", check().name)
    }
}
