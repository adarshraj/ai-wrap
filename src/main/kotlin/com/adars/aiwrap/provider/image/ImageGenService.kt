package com.adars.aiwrap.provider.image

import com.adars.aiwrap.model.ImageParams
import com.adars.aiwrap.model.ImageProviderResult

/**
 * Contract every image-gen provider implements. New providers (OpenAI `gpt-image-1`,
 * Azure DALL·E, Stability, etc.) implement this interface and are routed through
 * [ImageServiceResolver] — no changes to [com.adars.aiwrap.service.AiService] or the
 * REST layer are needed to add one.
 */
interface ImageGenService {
    /**
     * Generate one or more images from [prompt]. [systemPrompt] is used as a style/persona
     * prefix when the provider supports it. [referenceImages] enable edit/variation flows
     * (currently used by Gemini multi-image prompting).
     */
    fun generate(
        prompt: String,
        systemPrompt: String? = null,
        params: ImageParams? = null,
        referenceImages: List<ReferenceImage> = emptyList(),
        apiKey: String? = null,
    ): ImageProviderResult
}

/** A reference image supplied as input to an edit/variation request. */
data class ReferenceImage(val bytes: ByteArray, val mimeType: String)
