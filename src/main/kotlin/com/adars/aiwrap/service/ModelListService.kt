package com.adars.aiwrap.service

import com.adars.aiwrap.provider.AiProvider
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Fetches available models from upstream AI providers.
 *
 * Most providers expose an OpenAI-compatible GET /v1/models endpoint.
 * For Anthropic and Gemini we use their native listing APIs.
 * Azure OpenAI is skipped — model availability depends on deployment, not a catalog.
 */
@ApplicationScoped
class ModelListService @Inject constructor(
    private val objectMapper: ObjectMapper,

    @ConfigProperty(name = "quarkus.langchain4j.openai.openai-text.api-key", defaultValue = "DISABLED")
    private val openAiApiKey: String,
    @ConfigProperty(name = "quarkus.langchain4j.openai.openai-text.base-url", defaultValue = "https://api.openai.com/v1")
    private val openAiBaseUrl: String,

    @ConfigProperty(name = "quarkus.langchain4j.ai.gemini.gemini-text.api-key", defaultValue = "DISABLED")
    private val geminiApiKey: String,

    @ConfigProperty(name = "quarkus.langchain4j.anthropic.anthropic-text.api-key", defaultValue = "DISABLED")
    private val anthropicApiKey: String,

    @ConfigProperty(name = "quarkus.langchain4j.openai.deepseek.api-key", defaultValue = "DISABLED")
    private val deepSeekApiKey: String,
    @ConfigProperty(name = "quarkus.langchain4j.openai.deepseek.base-url", defaultValue = "https://api.deepseek.com")
    private val deepSeekBaseUrl: String,
) {
    companion object {
        private val log: Logger = Logger.getLogger(ModelListService::class.java)
        private val HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        private const val TIMEOUT_SECONDS = 15L

        // Known base URLs (mirrors ProviderResolver)
        private const val GROQ_URL = "https://api.groq.com/openai/v1"
        private const val OPENROUTER_URL = "https://openrouter.ai/api/v1"
        private const val MISTRAL_URL = "https://api.mistral.ai/v1"
        private const val CEREBRAS_URL = "https://api.cerebras.ai/v1"
        private const val XAI_URL = "https://api.x.ai/v1"
        private const val COHERE_URL = "https://api.cohere.com/compatibility/v1"
        private const val ANTHROPIC_URL = "https://api.anthropic.com/v1"
        private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta"
    }

    /**
     * List models for the given provider.
     * [apiKey] from the request overrides the server-configured key.
     */
    fun listModels(provider: AiProvider, apiKey: String?): ModelListResponse {
        return when (provider) {
            AiProvider.OPENAI -> fetchOpenAiCompatible(openAiBaseUrl, apiKey ?: openAiApiKey, provider)
            AiProvider.DEEPSEEK -> fetchOpenAiCompatible(deepSeekBaseUrl, apiKey ?: deepSeekApiKey, provider)
            AiProvider.GROQ -> fetchOpenAiCompatible(GROQ_URL, apiKey ?: envKey("GROQ_API_KEY"), provider)
            AiProvider.OPENROUTER -> fetchOpenAiCompatible(OPENROUTER_URL, apiKey ?: envKey("OPENROUTER_API_KEY"), provider)
            AiProvider.MISTRAL -> fetchOpenAiCompatible(MISTRAL_URL, apiKey ?: envKey("MISTRAL_API_KEY"), provider)
            AiProvider.CEREBRAS -> fetchOpenAiCompatible(CEREBRAS_URL, apiKey ?: envKey("CEREBRAS_API_KEY"), provider)
            AiProvider.XAI -> fetchOpenAiCompatible(XAI_URL, apiKey ?: envKey("XAI_API_KEY"), provider)
            AiProvider.COHERE -> fetchOpenAiCompatible(COHERE_URL, apiKey ?: envKey("COHERE_API_KEY"), provider)
            AiProvider.ANTHROPIC -> fetchAnthropic(apiKey ?: anthropicApiKey)
            AiProvider.GEMINI -> fetchGemini(apiKey ?: geminiApiKey)
            AiProvider.AZURE_OPENAI -> throw UnsupportedOperationException(
                "Azure OpenAI model listing is not supported — models depend on your deployment configuration."
            )
            AiProvider.OLLAMA -> throw UnsupportedOperationException(
                "Ollama support is not enabled. Enable the quarkus-langchain4j-ollama dependency in pom.xml."
            )
            AiProvider.OPENAI_COMPATIBLE -> throw IllegalArgumentException(
                "OPENAI_COMPATIBLE requires a base_url — use the provider-specific endpoint or pass base_url in model_params."
            )
        }
    }

    /** GET {baseUrl}/models with Bearer auth — works for OpenAI, Groq, OpenRouter, Mistral, Cerebras, xAI, Cohere, DeepSeek. */
    private fun fetchOpenAiCompatible(baseUrl: String, apiKey: String?, provider: AiProvider): ModelListResponse {
        requireKey(apiKey, provider)
        val url = "${baseUrl.trimEnd('/')}/models"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .GET()
            .build()
        val body = execute(request, provider)
        val upstream = objectMapper.readValue(body, OpenAiModelsResponse::class.java)
        return ModelListResponse(
            provider = provider.name.lowercase(),
            models = upstream.data.map { m ->
                ModelInfo(
                    id = m.id,
                    name = m.id,
                    created = m.created,
                    ownedBy = m.ownedBy,
                )
            }.sortedBy { it.id },
        )
    }

    /** GET /v1/models with x-api-key and anthropic-version headers. */
    private fun fetchAnthropic(apiKey: String?): ModelListResponse {
        requireKey(apiKey, AiProvider.ANTHROPIC)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$ANTHROPIC_URL/models"))
            .header("x-api-key", apiKey!!)
            .header("anthropic-version", "2023-06-01")
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .GET()
            .build()
        val body = execute(request, AiProvider.ANTHROPIC)
        val upstream = objectMapper.readValue(body, AnthropicModelsResponse::class.java)
        return ModelListResponse(
            provider = "anthropic",
            models = upstream.data.map { m ->
                ModelInfo(
                    id = m.id,
                    name = m.displayName ?: m.id,
                    created = m.createdAt,
                    ownedBy = null,
                )
            }.sortedBy { it.id },
        )
    }

    /** GET /v1beta/models?key=... — Gemini uses query-param auth. */
    private fun fetchGemini(apiKey: String?): ModelListResponse {
        requireKey(apiKey, AiProvider.GEMINI)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$GEMINI_URL/models?key=$apiKey"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .GET()
            .build()
        val body = execute(request, AiProvider.GEMINI)
        val upstream = objectMapper.readValue(body, GeminiModelsResponse::class.java)
        return ModelListResponse(
            provider = "gemini",
            models = upstream.models.map { m ->
                ModelInfo(
                    id = m.name.removePrefix("models/"),
                    name = m.displayName ?: m.name.removePrefix("models/"),
                    created = null,
                    ownedBy = null,
                )
            }.sortedBy { it.id },
        )
    }

    private fun execute(request: HttpRequest, provider: AiProvider): String {
        val response = HTTP.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            log.warnf("Model list failed for %s — HTTP %d: %s", provider, response.statusCode(), response.body().take(500))
            throw RuntimeException("Upstream ${provider.name.lowercase()} returned HTTP ${response.statusCode()}")
        }
        return response.body()
    }

    private fun requireKey(apiKey: String?, provider: AiProvider) {
        if (apiKey.isNullOrBlank() || apiKey == "DISABLED") {
            throw IllegalArgumentException(
                "No API key available for ${provider.name.lowercase()}. Supply api_key in the request or configure a server-wide key."
            )
        }
    }

    private fun envKey(name: String): String? = System.getenv(name)
}

// ── Response DTOs ────────────────────────────────────────────────────────────

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ModelListResponse(
    val provider: String,
    val models: List<ModelInfo>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ModelInfo(
    val id: String,
    val name: String?,
    val created: Long?,
    @JsonProperty("owned_by") val ownedBy: String?,
)

// ── Upstream response shapes (for Jackson deserialization) ───────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class OpenAiModelsResponse(val data: List<OpenAiModelEntry> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class OpenAiModelEntry(
    val id: String,
    val created: Long? = null,
    @JsonProperty("owned_by") val ownedBy: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class AnthropicModelsResponse(val data: List<AnthropicModelEntry> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class AnthropicModelEntry(
    val id: String,
    @JsonProperty("display_name") val displayName: String? = null,
    @JsonProperty("created_at") val createdAt: Long? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class GeminiModelsResponse(val models: List<GeminiModelEntry> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class GeminiModelEntry(
    val name: String,
    @JsonProperty("displayName") val displayName: String? = null,
)
