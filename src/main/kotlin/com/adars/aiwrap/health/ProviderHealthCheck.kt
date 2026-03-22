package com.adars.aiwrap.health

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Readiness

/**
 * Readiness check that verifies at least one AI provider is configured.
 * Reports UP only when at least one LLM provider has a real API key (not "DISABLED")
 * or PaddleOCR sidecar is enabled.
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
    @ConfigProperty(name = "aiwrap.paddle-ocr.enabled", defaultValue = "false")
    private val paddleEnabled: Boolean,
) : HealthCheck {

    override fun call(): HealthCheckResponse {
        val openAiEnabled = openAiKey != "DISABLED"
        val geminiEnabled = geminiKey != "DISABLED"
        val deepSeekEnabled = deepSeekKey != "DISABLED"
        val anyEnabled = openAiEnabled || geminiEnabled || deepSeekEnabled || paddleEnabled

        return HealthCheckResponse.named("ai-providers")
            .status(anyEnabled)
            .withData("openai", if (openAiEnabled) "enabled" else "disabled")
            .withData("gemini", if (geminiEnabled) "enabled" else "disabled")
            .withData("deepseek", if (deepSeekEnabled) "enabled" else "disabled")
            .withData("paddle-ocr", if (paddleEnabled) "enabled" else "disabled")
            .build()
    }
}
