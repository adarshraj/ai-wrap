package com.adars.aiwrap.provider

/**
 * Supported AI provider identifiers.
 * Clients pass one of these values in the `provider` field of every request.
 * The gateway routes the call to the corresponding LangChain4j service bean or REST client.
 */
enum class AiProvider(val supportsText: Boolean, val supportsVision: Boolean) {
    OPENAI(supportsText = true,  supportsVision = true),
    GEMINI(supportsText = true,  supportsVision = true),
    DEEPSEEK(supportsText = true,  supportsVision = false),  // text-only
    OLLAMA(supportsText = true,  supportsVision = true),     // depends on model
    PADDLE(supportsText = false, supportsVision = true);     // OCR sidecar — image in, text out

    companion object {
        fun fromString(value: String): AiProvider =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown provider '$value'. Valid values: ${entries.map { it.name.lowercase() }}"
                )
    }
}
