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
    ANTHROPIC(supportsText = true, supportsVision = true),    // Claude models
    AZURE_OPENAI(supportsText = true, supportsVision = true), // Azure-hosted OpenAI models
    OLLAMA(supportsText = true,  supportsVision = true),     // depends on model
    // OpenAI-compatible free-tier providers
    GROQ(supportsText = true,    supportsVision = true),     // Llama 4 Scout supports vision
    OPENROUTER(supportsText = true, supportsVision = true),  // routes to many models; some support vision
    MISTRAL(supportsText = true, supportsVision = true),     // Mistral Small 3.1 supports vision
    CEREBRAS(supportsText = true, supportsVision = false),   // text-only (fastest inference)
    XAI(supportsText = true, supportsVision = false),        // xAI Grok models (OpenAI-compatible)
    COHERE(supportsText = true, supportsVision = false),     // Cohere Command models (OpenAI-compatible)
    /** Any OpenAI-compatible endpoint — caller must supply base_url in model_params. */
    OPENAI_COMPATIBLE(supportsText = true, supportsVision = true);

    companion object {
        fun fromString(value: String): AiProvider =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown provider '$value'. Valid values: ${entries.map { it.name.lowercase() }}"
                )
    }
}
