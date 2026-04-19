package com.adars.aishim.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Per-request knobs for image generation. [model] is required — providers without image-gen
 * support reject the request up front. Fields unsupported by a given provider are ignored
 * and surfaced as `warnings` on the response.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ImageParams(
    /** Required. Provider-specific model ID, e.g. "gemini-2.5-flash-image", "gpt-image-1", "dall-e-3". */
    val model: String? = null,
    /** Output dimensions. Format `WxH` for consistency across providers (e.g. "1024x1024"). */
    val size: String? = null,
    /** Number of images to generate. Clamped to `aishim.image.max-count`. */
    val count: Int? = null,
    /** OpenAI-family only — "standard" | "hd". */
    val quality: String? = null,
    /** OpenAI-family only — "vivid" | "natural". */
    val style: String? = null,
    /** "b64" (default) to return base64 bytes, "url" if the provider supports hosted URLs. */
    @JsonProperty("response_format") val responseFormat: String? = null,
    /** Reproducibility seed (provider-dependent). */
    val seed: Long? = null,
    /** Negative prompt (ignored by providers that don't support it). */
    @JsonProperty("negative_prompt") val negativePrompt: String? = null,
    /** Per-request timeout in seconds for providers that support dynamic timeouts. */
    @JsonProperty("timeout_seconds") val timeoutSeconds: Int? = null,
)

/** A single generated image. Exactly one of [base64] or [url] is populated. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class GeneratedImage(
    val base64: String? = null,
    val url: String? = null,
    @JsonProperty("mime_type") val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    @JsonProperty("size_bytes") val sizeBytes: Int,
    @JsonProperty("revised_prompt") val revisedPrompt: String? = null,
    val seed: Long? = null,
    @JsonProperty("finish_reason") val finishReason: String? = null,
)

/** Internal provider result for image-gen calls. */
data class ImageProviderResult(
    val images: List<GeneratedImage>,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val warnings: List<String> = emptyList(),
)

/** Response envelope for POST /ai/image. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AiImageResponse(
    val provider: String,
    val model: String?,
    @JsonProperty("processing_time_ms") val processingTimeMs: Long,
    @JsonProperty("input_tokens") val inputTokens: Int? = null,
    @JsonProperty("output_tokens") val outputTokens: Int? = null,
    val images: List<GeneratedImage>,
    val warnings: List<String> = emptyList(),
)
