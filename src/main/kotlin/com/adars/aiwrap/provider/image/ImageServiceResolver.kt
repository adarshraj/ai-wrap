package com.adars.aiwrap.provider.image

import com.adars.aiwrap.provider.AiProvider
import com.adars.aiwrap.provider.gemini.GeminiImageGenService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Routes image-gen requests to the correct provider bean.
 *
 * Adding a new provider: implement [ImageGenService], inject it here, and map its
 * [AiProvider] entry in the `when` block. The enum's `supportsImageGen` flag keeps
 * [com.adars.aiwrap.service.AiService] honest about capability checks.
 */
@ApplicationScoped
class ImageServiceResolver @Inject constructor(
    private val geminiImage: GeminiImageGenService,
) {
    fun resolve(provider: AiProvider): ImageGenService {
        if (!provider.supportsImageGen) {
            throw UnsupportedOperationException(
                "$provider does not support image generation. Use GEMINI (today) or OPENAI once added."
            )
        }
        return when (provider) {
            AiProvider.GEMINI -> geminiImage
            else -> throw UnsupportedOperationException(
                "Image generation for $provider is declared supported but no service is wired. " +
                    "Add the implementation in ImageServiceResolver."
            )
        }
    }
}
