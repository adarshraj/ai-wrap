package com.adars.aishim.service

import com.adars.aishim.provider.AiProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for the input-validation and dispatch logic that doesn't require an
 * upstream HTTP call. Network-bound paths (the actual /models fetches) are covered
 * separately in Tier 3 with WireMock.
 */
class ModelListServiceTest {

    private fun build(
        openAiKey: String = "DISABLED",
        geminiKey: String = "DISABLED",
        anthropicKey: String = "DISABLED",
        deepSeekKey: String = "DISABLED",
    ) = ModelListService(
        objectMapper = ObjectMapper(),
        openAiApiKey = openAiKey,
        openAiBaseUrl = "https://api.openai.com/v1",
        geminiApiKey = geminiKey,
        anthropicApiKey = anthropicKey,
        deepSeekApiKey = deepSeekKey,
        deepSeekBaseUrl = "https://api.deepseek.com/v1",
        ollamaBaseUrl = "http://localhost:11434/v1",
    )

    @Test
    fun `AZURE_OPENAI is unsupported by design`() {
        // Azure model availability is deployment-specific — there is no global catalog endpoint.
        val ex = assertThrows<UnsupportedOperationException> {
            build().listModels(AiProvider.AZURE_OPENAI, apiKey = "k")
        }
        assertThat(ex.message).contains("Azure OpenAI")
    }

    @Test
    fun `OPENAI_COMPATIBLE without base_url is rejected with helpful message`() {
        val ex = assertThrows<IllegalArgumentException> {
            build().listModels(AiProvider.OPENAI_COMPATIBLE, apiKey = "k")
        }
        assertThat(ex.message).contains("base_url")
    }

    @Test
    fun `missing api key throws IllegalArgumentException for OpenAI`() {
        // Both server-config key (DISABLED) and request key (null) absent — guard fires.
        val ex = assertThrows<IllegalArgumentException> {
            build(openAiKey = "DISABLED").listModels(AiProvider.OPENAI, apiKey = null)
        }
        assertThat(ex.message).contains("openai").contains("API key")
    }

    @Test
    fun `blank request api key falls back to server key and still rejects DISABLED`() {
        val ex = assertThrows<IllegalArgumentException> {
            build(openAiKey = "DISABLED").listModels(AiProvider.OPENAI, apiKey = "")
        }
        assertThat(ex.message).contains("openai")
    }

    @Test
    fun `Anthropic without key is rejected before any HTTP attempt`() {
        assertThrows<IllegalArgumentException> {
            build().listModels(AiProvider.ANTHROPIC, apiKey = null)
        }
    }

    @Test
    fun `Gemini without key is rejected before any HTTP attempt`() {
        assertThrows<IllegalArgumentException> {
            build().listModels(AiProvider.GEMINI, apiKey = null)
        }
    }

    @Test
    fun `DeepSeek without key is rejected`() {
        assertThrows<IllegalArgumentException> {
            build().listModels(AiProvider.DEEPSEEK, apiKey = null)
        }
    }

    @Test
    fun `free-tier providers without env key and without request key are rejected`() {
        // GROQ/OPENROUTER/MISTRAL/CEREBRAS/XAI/COHERE all have no server-side key by default.
        val freeTier = listOf(
            AiProvider.GROQ, AiProvider.OPENROUTER, AiProvider.MISTRAL,
            AiProvider.CEREBRAS, AiProvider.XAI, AiProvider.COHERE,
        )
        freeTier.forEach { p ->
            // Cannot guarantee env vars are unset in CI, so just assert *some* exception type is reasonable
            // when no key is supplied. If the env var happens to be set, the call would attempt HTTP and
            // fail with a different exception type. We accept either signal.
            val key = System.getenv("${p.name}_API_KEY")
            if (key.isNullOrBlank()) {
                assertThrows<IllegalArgumentException>("expected $p to reject missing key") {
                    build().listModels(p, apiKey = null)
                }
            }
        }
    }
}
