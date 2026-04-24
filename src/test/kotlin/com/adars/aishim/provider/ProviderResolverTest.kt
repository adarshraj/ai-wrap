package com.adars.aishim.provider

import com.adars.aishim.model.ModelParams
import com.adars.aishim.model.ProviderResult
import com.adars.aishim.provider.anthropic.AnthropicOcrService
import com.adars.aishim.provider.anthropic.AnthropicTextService
import com.adars.aishim.provider.azure.AzureOpenAiOcrService
import com.adars.aishim.provider.azure.AzureOpenAiTextService
import com.adars.aishim.provider.deepseek.DeepSeekTextService
import com.adars.aishim.provider.gemini.GeminiOcrService
import com.adars.aishim.provider.gemini.GeminiTextService
import com.adars.aishim.provider.openai.OpenAiOcrService
import com.adars.aishim.provider.openai.OpenAiTextService
import dev.langchain4j.data.message.UserMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.nullableArgumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ProviderResolverTest {

    private lateinit var openAiOcr: OpenAiOcrService
    private lateinit var openAiText: OpenAiTextService
    private lateinit var geminiOcr: GeminiOcrService
    private lateinit var geminiText: GeminiTextService
    private lateinit var deepSeekText: DeepSeekTextService
    private lateinit var anthropicOcr: AnthropicOcrService
    private lateinit var anthropicText: AnthropicTextService
    private lateinit var azureOcr: AzureOpenAiOcrService
    private lateinit var azureText: AzureOpenAiTextService
    private lateinit var resolver: ProviderResolver

    private val stubResult = ProviderResult(text = "ok", inputTokens = 1, outputTokens = 1)
    private val ollamaUrl = "http://ollama.test/v1"

    @BeforeEach
    fun setup() {
        openAiOcr = mock()
        openAiText = mock()
        geminiOcr = mock()
        geminiText = mock()
        deepSeekText = mock()
        anthropicOcr = mock()
        anthropicText = mock()
        azureOcr = mock()
        azureText = mock()
        whenever(openAiOcr.invokeVision(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(stubResult)
        whenever(geminiOcr.invokeVision(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(stubResult)
        whenever(anthropicOcr.invokeVision(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(stubResult)
        whenever(azureOcr.invokeVision(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(stubResult)
        whenever(openAiText.chat(any(), anyOrNull(), anyOrNull())).thenReturn(stubResult)
        whenever(geminiText.chat(any(), anyOrNull(), anyOrNull())).thenReturn(stubResult)
        whenever(deepSeekText.chat(any(), anyOrNull(), anyOrNull())).thenReturn(stubResult)
        whenever(anthropicText.chat(any(), anyOrNull(), anyOrNull())).thenReturn(stubResult)
        whenever(azureText.chat(any(), anyOrNull(), anyOrNull())).thenReturn(stubResult)

        resolver = ProviderResolver(
            openAiOcr, openAiText, geminiOcr, geminiText, deepSeekText,
            anthropicOcr, anthropicText, azureOcr, azureText,
        ).also { it.ollamaBaseUrl = ollamaUrl }
    }

    // ── Text dispatch ────────────────────────────────────────────────────────

    @Test
    fun `text dispatches OPENAI to openAiText with no base-url override`() {
        resolver.textFunction(AiProvider.OPENAI, ModelParams(model = "gpt-4o"), "k")(listOf(UserMessage.from("hi")))

        val captor = nullableArgumentCaptor<ModelParams>()
        verify(openAiText).chat(any(), captor.capture(), eq("k"))
        assertThat(captor.firstValue?.baseUrl).isNull()
    }

    @Test
    fun `text dispatches GEMINI to geminiText`() {
        resolver.textFunction(AiProvider.GEMINI, null, null)(listOf(UserMessage.from("hi")))
        verify(geminiText).chat(any(), anyOrNull(), anyOrNull())
        verify(openAiText, never()).chat(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `text dispatches DEEPSEEK to deepSeekText`() {
        resolver.textFunction(AiProvider.DEEPSEEK, null, null)(listOf(UserMessage.from("hi")))
        verify(deepSeekText).chat(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `text dispatches ANTHROPIC to anthropicText`() {
        resolver.textFunction(AiProvider.ANTHROPIC, null, null)(listOf(UserMessage.from("hi")))
        verify(anthropicText).chat(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `text dispatches AZURE_OPENAI to azureText`() {
        resolver.textFunction(AiProvider.AZURE_OPENAI, null, null)(listOf(UserMessage.from("hi")))
        verify(azureText).chat(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `text GROQ routes to openAiText with Groq base URL`() {
        resolver.textFunction(AiProvider.GROQ, null, "k")(listOf(UserMessage.from("hi")))
        val captor = nullableArgumentCaptor<ModelParams>()
        verify(openAiText).chat(any(), captor.capture(), eq("k"))
        assertThat(captor.firstValue?.baseUrl).isEqualTo("https://api.groq.com/openai/v1")
    }

    @Test
    fun `text OPENROUTER routes through openAiText with OpenRouter base URL`() {
        resolver.textFunction(AiProvider.OPENROUTER, null, "k")(listOf(UserMessage.from("hi")))
        val captor = nullableArgumentCaptor<ModelParams>()
        verify(openAiText).chat(any(), captor.capture(), eq("k"))
        assertThat(captor.firstValue?.baseUrl).isEqualTo("https://openrouter.ai/api/v1")
    }

    @Test
    fun `text MISTRAL routes through openAiText with Mistral base URL`() {
        resolver.textFunction(AiProvider.MISTRAL, null, "k")(listOf(UserMessage.from("hi")))
        val captor = nullableArgumentCaptor<ModelParams>()
        verify(openAiText).chat(any(), captor.capture(), eq("k"))
        assertThat(captor.firstValue?.baseUrl).isEqualTo("https://api.mistral.ai/v1")
    }

    @Test
    fun `text CEREBRAS routes through openAiText with Cerebras base URL`() {
        resolver.textFunction(AiProvider.CEREBRAS, null, "k")(listOf(UserMessage.from("hi")))
        val captor = nullableArgumentCaptor<ModelParams>()
        verify(openAiText).chat(any(), captor.capture(), eq("k"))
        assertThat(captor.firstValue?.baseUrl).isEqualTo("https://api.cerebras.ai/v1")
    }

    @Test
    fun `text XAI routes through openAiText with xAI base URL`() {
        resolver.textFunction(AiProvider.XAI, null, "k")(listOf(UserMessage.from("hi")))
        val captor = nullableArgumentCaptor<ModelParams>()
        verify(openAiText).chat(any(), captor.capture(), eq("k"))
        assertThat(captor.firstValue?.baseUrl).isEqualTo("https://api.x.ai/v1")
    }

    @Test
    fun `text COHERE routes through openAiText with Cohere base URL`() {
        resolver.textFunction(AiProvider.COHERE, null, "k")(listOf(UserMessage.from("hi")))
        val captor = nullableArgumentCaptor<ModelParams>()
        verify(openAiText).chat(any(), captor.capture(), eq("k"))
        assertThat(captor.firstValue?.baseUrl).isEqualTo("https://api.cohere.com/compatibility/v1")
    }

    @Test
    fun `text OLLAMA injects ollama base URL and default api key`() {
        resolver.textFunction(AiProvider.OLLAMA, null, null)(listOf(UserMessage.from("hi")))
        val params = nullableArgumentCaptor<ModelParams>()
        val key = nullableArgumentCaptor<String>()
        verify(openAiText).chat(any(), params.capture(), key.capture())
        assertThat(params.firstValue?.baseUrl).isEqualTo(ollamaUrl)
        assertThat(key.firstValue).isEqualTo("ollama")
    }

    @Test
    fun `text OLLAMA preserves caller-supplied api key`() {
        resolver.textFunction(AiProvider.OLLAMA, null, "user-key")(listOf(UserMessage.from("hi")))
        val key = nullableArgumentCaptor<String>()
        verify(openAiText).chat(any(), anyOrNull(), key.capture())
        assertThat(key.firstValue).isEqualTo("user-key")
    }

    @Test
    fun `text OPENAI_COMPATIBLE without base_url throws`() {
        assertThrows<IllegalArgumentException> {
            resolver.textFunction(AiProvider.OPENAI_COMPATIBLE, ModelParams(model = "x"), "k")
        }
    }

    @Test
    fun `text OPENAI_COMPATIBLE with base_url routes to openAiText untouched`() {
        val params = ModelParams(model = "x", baseUrl = "https://my.endpoint/v1")
        resolver.textFunction(AiProvider.OPENAI_COMPATIBLE, params, "k")(listOf(UserMessage.from("hi")))
        val captor = nullableArgumentCaptor<ModelParams>()
        verify(openAiText).chat(any(), captor.capture(), eq("k"))
        assertThat(captor.firstValue?.baseUrl).isEqualTo("https://my.endpoint/v1")
    }

    @Test
    fun `text caller-supplied base_url is preserved over provider default`() {
        val params = ModelParams(model = "x", baseUrl = "https://override.example/v1")
        resolver.textFunction(AiProvider.GROQ, params, "k")(listOf(UserMessage.from("hi")))
        val captor = nullableArgumentCaptor<ModelParams>()
        verify(openAiText).chat(any(), captor.capture(), eq("k"))
        assertThat(captor.firstValue?.baseUrl).isEqualTo("https://override.example/v1")
    }

    // ── Vision dispatch ──────────────────────────────────────────────────────

    @Test
    fun `vision dispatches OPENAI to openAiOcr`() {
        resolver.visionFunction(AiProvider.OPENAI, null, null, "k")("p", "b64", "image/png")
        verify(openAiOcr, atLeastOnce()).invokeVision(eq("p"), eq("b64"), eq("image/png"), anyOrNull(), anyOrNull(), eq("k"))
    }

    @Test
    fun `vision dispatches GEMINI to geminiOcr`() {
        resolver.visionFunction(AiProvider.GEMINI, "sys", null, null)("p", "b64", "image/png")
        verify(geminiOcr).invokeVision(eq("p"), eq("b64"), eq("image/png"), eq("sys"), anyOrNull(), anyOrNull())
    }

    @Test
    fun `vision dispatches ANTHROPIC to anthropicOcr`() {
        resolver.visionFunction(AiProvider.ANTHROPIC, null, null, null)("p", "b64", "image/png")
        verify(anthropicOcr).invokeVision(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `vision dispatches AZURE_OPENAI to azureOcr`() {
        resolver.visionFunction(AiProvider.AZURE_OPENAI, null, null, null)("p", "b64", "image/png")
        verify(azureOcr).invokeVision(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `vision GROQ routes through openAiOcr with Groq base URL`() {
        resolver.visionFunction(AiProvider.GROQ, null, null, "k")("p", "b64", "image/png")
        val captor = nullableArgumentCaptor<ModelParams>()
        verify(openAiOcr).invokeVision(any(), any(), any(), anyOrNull(), captor.capture(), eq("k"))
        assertThat(captor.firstValue?.baseUrl).isEqualTo("https://api.groq.com/openai/v1")
    }

    @Test
    fun `vision OLLAMA injects ollama URL and default key`() {
        resolver.visionFunction(AiProvider.OLLAMA, null, null, null)("p", "b64", "image/png")
        val params = nullableArgumentCaptor<ModelParams>()
        val key = nullableArgumentCaptor<String>()
        verify(openAiOcr).invokeVision(any(), any(), any(), anyOrNull(), params.capture(), key.capture())
        assertThat(params.firstValue?.baseUrl).isEqualTo(ollamaUrl)
        assertThat(key.firstValue).isEqualTo("ollama")
    }

    @Test
    fun `vision OPENAI_COMPATIBLE without base_url throws`() {
        assertThrows<IllegalArgumentException> {
            resolver.visionFunction(AiProvider.OPENAI_COMPATIBLE, null, ModelParams(model = "x"), "k")
        }
    }

    @Test
    fun `vision DEEPSEEK rejected with helpful message`() {
        val ex = assertThrows<UnsupportedOperationException> {
            resolver.visionFunction(AiProvider.DEEPSEEK, null, null, null)
        }
        assertThat(ex.message).contains("DeepSeek").contains("vision")
    }

    @Test
    fun `vision CEREBRAS rejected`() {
        assertThrows<UnsupportedOperationException> {
            resolver.visionFunction(AiProvider.CEREBRAS, null, null, null)
        }
    }

    @Test
    fun `vision XAI rejected`() {
        assertThrows<UnsupportedOperationException> {
            resolver.visionFunction(AiProvider.XAI, null, null, null)
        }
    }

    @Test
    fun `vision COHERE rejected`() {
        assertThrows<UnsupportedOperationException> {
            resolver.visionFunction(AiProvider.COHERE, null, null, null)
        }
    }
}
