package com.adars.aishim.provider.gemini

import com.adars.aishim.model.GeneratedImage
import com.adars.aishim.model.ImageParams
import com.adars.aishim.model.ImageProviderResult
import com.adars.aishim.provider.image.ImageGenService
import com.adars.aishim.provider.image.ReferenceImage
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.Bulkhead
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * Google Gemini image generation via the REST `generateContent` endpoint.
 *
 * LangChain4j 1.7 does not expose a typed binding for Gemini image output, so we call
 * `https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` directly
 * with `responseModalities: [TEXT, IMAGE]` and parse `candidates[].content.parts[].inlineData`.
 *
 * Fault tolerance mirrors [GeminiTextService]: `@Bulkhead` for concurrency, `@CircuitBreaker`
 * to open after sustained failures, `@Retry` with jitter for transient errors.
 */
@ApplicationScoped
class GeminiImageGenService @Inject constructor(
    @ConfigProperty(name = "quarkus.langchain4j.ai.gemini.gemini-text.api-key", defaultValue = "DISABLED")
    private val apiKey: String,
    @ConfigProperty(name = "aishim.image.gemini.endpoint", defaultValue = "https://generativelanguage.googleapis.com/v1beta")
    private val endpoint: String,
    @ConfigProperty(name = "aishim.image.gemini.default-model", defaultValue = "gemini-2.5-flash-image")
    private val defaultModel: String,
    @ConfigProperty(name = "aishim.image.max-count", defaultValue = "4")
    private val maxCount: Int,
    private val objectMapper: ObjectMapper,
) : ImageGenService {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    @Bulkhead(value = 10, waitingTaskQueue = 5)
    @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.5, delay = 30_000)
    @Retry(maxRetries = 2, delay = 500, jitter = 200)
    override fun generate(
        prompt: String,
        systemPrompt: String?,
        params: ImageParams?,
        referenceImages: List<ReferenceImage>,
        apiKey: String?,
    ): ImageProviderResult {
        if (apiKey != null) {
            log.warn("Per-request api_key is not supported for Gemini image gen — using the server-configured key.")
        }
        if (this.apiKey == "DISABLED") {
            throw IllegalStateException("Gemini image generation requires GEMINI_API_KEY to be set.")
        }

        val warnings = mutableListOf<String>()
        val model = params?.model?.takeIf { it.isNotBlank() } ?: defaultModel
        val count = (params?.count ?: 1).coerceIn(1, maxCount).also {
            if (params?.count != null && params.count > maxCount) {
                warnings += "count clamped from ${params.count} to $maxCount"
            }
        }
        if (params?.style != null) warnings += "style ignored by gemini"
        if (params?.quality != null) warnings += "quality ignored by gemini"
        if (params?.negativePrompt != null) warnings += "negative_prompt ignored by gemini"

        val body = buildRequestBody(prompt, systemPrompt, referenceImages, count)
        val url = "$endpoint/models/$model:generateContent?key=${this.apiKey}"
        val timeout = Duration.ofSeconds((params?.timeoutSeconds ?: 120).toLong())
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() >= 400) {
            val snippet = response.body().take(500)
            throw RuntimeException("Gemini image API returned ${response.statusCode()}: $snippet")
        }

        return parseResponse(response.body(), warnings)
    }

    private fun buildRequestBody(
        prompt: String,
        systemPrompt: String?,
        referenceImages: List<ReferenceImage>,
        count: Int,
    ): String {
        val root = objectMapper.createObjectNode()
        val contents = root.putArray("contents")
        val userContent = contents.addObject()
        val parts = userContent.putArray("parts")

        val combinedPrompt = if (systemPrompt.isNullOrBlank()) prompt else "$systemPrompt\n\n$prompt"
        parts.addObject().put("text", combinedPrompt)

        referenceImages.forEach { ref ->
            val inline = parts.addObject().putObject("inlineData")
            inline.put("mimeType", ref.mimeType)
            inline.put("data", Base64.getEncoder().encodeToString(ref.bytes))
        }

        val generationConfig = root.putObject("generationConfig")
        val modalities = generationConfig.putArray("responseModalities")
        modalities.add("TEXT")
        modalities.add("IMAGE")
        if (count > 1) generationConfig.put("candidateCount", count)

        return objectMapper.writeValueAsString(root)
    }

    private fun parseResponse(json: String, warnings: MutableList<String>): ImageProviderResult {
        val node: JsonNode = objectMapper.readTree(json)
        val candidates = node.path("candidates")
        val images = mutableListOf<GeneratedImage>()

        candidates.forEach { candidate ->
            val finishReason = candidate.path("finishReason").asText(null)
            val parts = candidate.path("content").path("parts")
            parts.forEach { part ->
                val inline = part.path("inlineData")
                if (!inline.isMissingNode && inline.has("data")) {
                    val base64 = inline.path("data").asText()
                    val mimeType = inline.path("mimeType").asText("image/png")
                    val decodedLen = runCatching { Base64.getDecoder().decode(base64).size }.getOrDefault(-1)
                    images += GeneratedImage(
                        base64 = base64,
                        url = null,
                        mimeType = mimeType,
                        width = null,
                        height = null,
                        sizeBytes = decodedLen.coerceAtLeast(0),
                        revisedPrompt = null,
                        seed = null,
                        finishReason = finishReason,
                    )
                }
            }
        }

        if (images.isEmpty()) warnings += "gemini returned no image data"

        val usage = node.path("usageMetadata")
        val inputTokens = usage.path("promptTokenCount").takeIf { it.isInt }?.asInt()
        val outputTokens = usage.path("candidatesTokenCount").takeIf { it.isInt }?.asInt()

        return ImageProviderResult(
            images = images,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            warnings = warnings.toList(),
        )
    }

    companion object {
        private val log: Logger = Logger.getLogger(GeminiImageGenService::class.java)
    }
}
