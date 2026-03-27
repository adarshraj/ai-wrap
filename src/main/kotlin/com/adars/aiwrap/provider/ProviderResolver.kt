package com.adars.aiwrap.provider

import com.adars.aiwrap.model.ModelParams
import com.adars.aiwrap.model.ProviderResult
import com.adars.aiwrap.provider.deepseek.DeepSeekTextService
import com.adars.aiwrap.provider.gemini.GeminiOcrService
import com.adars.aiwrap.provider.gemini.GeminiTextService
import com.adars.aiwrap.provider.openai.OpenAiOcrService
import com.adars.aiwrap.provider.openai.OpenAiTextService
import dev.langchain4j.data.message.ChatMessage
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Dispatches to the correct provider bean based on [AiProvider].
 *
 * Ollama support is currently disabled (dependency commented out in pom.xml).
 * To enable: uncomment the Ollama dependency in pom.xml, re-enable the Ollama service files,
 * and add the Ollama cases back to this resolver.
 */
@ApplicationScoped
class ProviderResolver @Inject constructor(
    private val openAiOcr: OpenAiOcrService,
    private val openAiText: OpenAiTextService,
    private val geminiOcr: GeminiOcrService,
    private val geminiText: GeminiTextService,
    private val deepSeekText: DeepSeekTextService,
) {
    // ── Known base URLs for OpenAI-compatible free-tier providers ────────────
    private val GROQ_URL       = "https://api.groq.com/openai/v1"
    private val OPENROUTER_URL = "https://openrouter.ai/api/v1"
    private val MISTRAL_URL    = "https://api.mistral.ai/v1"
    private val CEREBRAS_URL   = "https://api.cerebras.ai/v1"

    /**
     * Returns params with [knownUrl] injected as base_url if the caller didn't already supply one.
     * This lets the caller override the URL while named providers still work with zero config.
     */
    private fun withBaseUrl(params: ModelParams?, knownUrl: String): ModelParams =
        (params ?: ModelParams()).let { if (it.baseUrl != null) it else it.copy(baseUrl = knownUrl) }

    /**
     * Returns a function (prompt, imageBase64, mimeType) -> ProviderResult for vision-capable providers.
     * [systemPrompt] and [params] apply to the request; nulls use configured defaults.
     * PADDLE is handled directly by AiService via the REST sidecar client.
     */
    fun visionFunction(
        provider: AiProvider,
        systemPrompt: String?,
        params: ModelParams?,
        apiKey: String? = null,
    ): (prompt: String, imageBase64: String, mimeType: String) -> ProviderResult =
        when (provider) {
            AiProvider.OPENAI -> { prompt, b64, mime -> openAiOcr.invokeVision(prompt, b64, mime, systemPrompt, params, apiKey) }
            AiProvider.GEMINI -> { prompt, b64, mime -> geminiOcr.invokeVision(prompt, b64, mime, systemPrompt, params, apiKey) }
            AiProvider.GROQ -> { prompt, b64, mime -> openAiOcr.invokeVision(prompt, b64, mime, systemPrompt, withBaseUrl(params, GROQ_URL), apiKey) }
            AiProvider.OPENROUTER -> { prompt, b64, mime -> openAiOcr.invokeVision(prompt, b64, mime, systemPrompt, withBaseUrl(params, OPENROUTER_URL), apiKey) }
            AiProvider.MISTRAL -> { prompt, b64, mime -> openAiOcr.invokeVision(prompt, b64, mime, systemPrompt, withBaseUrl(params, MISTRAL_URL), apiKey) }
            AiProvider.OPENAI_COMPATIBLE -> { prompt, b64, mime -> openAiOcr.invokeVision(prompt, b64, mime, systemPrompt, params, apiKey) }
            AiProvider.OLLAMA -> throw UnsupportedOperationException(
                "Ollama support is not enabled. Enable the quarkus-langchain4j-ollama dependency in pom.xml."
            )
            AiProvider.CEREBRAS -> throw UnsupportedOperationException(
                "Cerebras does not support vision/image input. Use GROQ, OPENAI, GEMINI, or OPENROUTER."
            )
            AiProvider.DEEPSEEK -> throw UnsupportedOperationException(
                "DeepSeek does not support vision/image input. Use OPENAI, GEMINI, or PADDLE."
            )
            AiProvider.PADDLE -> throw UnsupportedOperationException(
                "PADDLE is handled directly by AiService via the REST sidecar client."
            )
        }

    /**
     * Returns a function (messages) -> ProviderResult for text-only providers.
     * [params] and [apiKey] apply to the request; nulls use configured defaults.
     * The [messages] list should already contain the system message (if any) as the first element.
     */
    fun textFunction(
        provider: AiProvider,
        params: ModelParams?,
        apiKey: String? = null,
    ): (messages: List<ChatMessage>) -> ProviderResult =
        when (provider) {
            AiProvider.OPENAI -> { msgs -> openAiText.chat(msgs, params, apiKey) }
            AiProvider.GEMINI -> { msgs -> geminiText.chat(msgs, params, apiKey) }
            AiProvider.DEEPSEEK -> { msgs -> deepSeekText.chat(msgs, params, apiKey) }
            AiProvider.GROQ -> { msgs -> openAiText.chat(msgs, withBaseUrl(params, GROQ_URL), apiKey) }
            AiProvider.OPENROUTER -> { msgs -> openAiText.chat(msgs, withBaseUrl(params, OPENROUTER_URL), apiKey) }
            AiProvider.MISTRAL -> { msgs -> openAiText.chat(msgs, withBaseUrl(params, MISTRAL_URL), apiKey) }
            AiProvider.CEREBRAS -> { msgs -> openAiText.chat(msgs, withBaseUrl(params, CEREBRAS_URL), apiKey) }
            AiProvider.OPENAI_COMPATIBLE -> { msgs -> openAiText.chat(msgs, params, apiKey) }
            AiProvider.OLLAMA -> throw UnsupportedOperationException(
                "Ollama support is not enabled. Enable the quarkus-langchain4j-ollama dependency in pom.xml."
            )
            AiProvider.PADDLE -> throw UnsupportedOperationException(
                "PADDLE is an OCR-only provider and cannot handle text prompts."
            )
        }
}
