package com.adars.aishim.provider.image

import com.adars.aishim.provider.AiProvider
import com.adars.aishim.provider.gemini.GeminiImageGenService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock

class ImageServiceResolverTest {

    private val gemini = mock<GeminiImageGenService>()
    private val resolver = ImageServiceResolver(gemini)

    @Test
    fun `GEMINI resolves to GeminiImageGenService`() {
        assertThat(resolver.resolve(AiProvider.GEMINI)).isSameAs(gemini)
    }

    @Test
    fun `non-image-gen provider OPENAI throws with explanatory message`() {
        // OPENAI's enum entry sets supportsImageGen=false (gpt-image-1 wiring not added yet).
        val ex = assertThrows<UnsupportedOperationException> { resolver.resolve(AiProvider.OPENAI) }
        assertThat(ex.message).contains("OPENAI").contains("image generation")
    }

    @Test
    fun `text-only providers all reject image gen`() {
        val textOnly = listOf(
            AiProvider.DEEPSEEK, AiProvider.ANTHROPIC, AiProvider.AZURE_OPENAI,
            AiProvider.GROQ, AiProvider.OPENROUTER, AiProvider.MISTRAL,
            AiProvider.CEREBRAS, AiProvider.XAI, AiProvider.COHERE,
            AiProvider.OLLAMA, AiProvider.OPENAI_COMPATIBLE,
        )
        textOnly.forEach { p ->
            assertThrows<UnsupportedOperationException>("expected $p to reject image gen") {
                resolver.resolve(p)
            }
        }
    }
}
