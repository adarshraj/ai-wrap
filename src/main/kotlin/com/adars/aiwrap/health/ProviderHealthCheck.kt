package com.adars.aiwrap.health

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Readiness

/**
 * Readiness check that verifies at least one AI provider is configured.
 * Reports UP only when at least one LLM provider has a real API key (not "DISABLED").
 *
 * Exposed at /q/health/ready as the "ai-providers" check.
 */
@Readiness
@ApplicationScoped
class ProviderHealthCheck @jakarta.inject.Inject constructor(
    @ConfigProperty(name = "quarkus.langchain4j.openai.openai-text.api-key", defaultValue = "DISABLED")
    private val openAiKey: String,
    @ConfigProperty(name = "quarkus.langchain4j.ai.gemini.gemini-text.api-key", defaultValue = "DISABLED")
    private val geminiKey: String,
    @ConfigProperty(name = "quarkus.langchain4j.openai.deepseek.api-key", defaultValue = "DISABLED")
    private val deepSeekKey: String,
    @ConfigProperty(name = "quarkus.langchain4j.anthropic.anthropic-text.api-key", defaultValue = "DISABLED")
    private val anthropicKey: String,
    @ConfigProperty(name = "quarkus.langchain4j.azure-openai.azure-openai-text.api-key", defaultValue = "DISABLED")
    private val azureOpenAiKey: String,
) : HealthCheck {

    private fun envKeyPresent(name: String): Boolean =
        !System.getenv(name).isNullOrBlank()

    override fun call(): HealthCheckResponse {
        val openAiEnabled = openAiKey != "DISABLED"
        val geminiEnabled = geminiKey != "DISABLED"
        val deepSeekEnabled = deepSeekKey != "DISABLED"
        val anthropicEnabled = anthropicKey != "DISABLED"
        val azureOpenAiEnabled = azureOpenAiKey != "DISABLED"
        val groqEnabled = envKeyPresent("GROQ_API_KEY")
        val openRouterEnabled = envKeyPresent("OPENROUTER_API_KEY")
        val mistralEnabled = envKeyPresent("MISTRAL_API_KEY")
        val cerebrasEnabled = envKeyPresent("CEREBRAS_API_KEY")
        val xaiEnabled = envKeyPresent("XAI_API_KEY")
        val cohereEnabled = envKeyPresent("COHERE_API_KEY")
        // Ollama is always "available" — it's local; actual reachability is checked at call time
        val anyEnabled = openAiEnabled || geminiEnabled || deepSeekEnabled ||
            anthropicEnabled || azureOpenAiEnabled ||
            groqEnabled || openRouterEnabled || mistralEnabled ||
            cerebrasEnabled || xaiEnabled || cohereEnabled

        return HealthCheckResponse.named("ai-providers")
            .status(anyEnabled)
            .withData("openai", if (openAiEnabled) "enabled" else "disabled")
            .withData("gemini", if (geminiEnabled) "enabled" else "disabled")
            .withData("deepseek", if (deepSeekEnabled) "enabled" else "disabled")
            .withData("anthropic", if (anthropicEnabled) "enabled" else "disabled")
            .withData("azure-openai", if (azureOpenAiEnabled) "enabled" else "disabled")
            .withData("groq", if (groqEnabled) "enabled" else "per-request only")
            .withData("openrouter", if (openRouterEnabled) "enabled" else "per-request only")
            .withData("mistral", if (mistralEnabled) "enabled" else "per-request only")
            .withData("cerebras", if (cerebrasEnabled) "enabled" else "per-request only")
            .withData("xai", if (xaiEnabled) "enabled" else "per-request only")
            .withData("cohere", if (cohereEnabled) "enabled" else "per-request only")
            .withData("ollama", "available (local/cloud)")
            .build()
    }
}
