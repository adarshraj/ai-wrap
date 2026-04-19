package com.adars.aishim.provider.openai

import com.adars.aishim.model.ModelParams
import com.adars.aishim.model.ProviderResult
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.ChatRequestParameters
import dev.langchain4j.model.chat.request.ResponseFormat
import dev.langchain4j.model.chat.request.ResponseFormatType
import dev.langchain4j.model.openai.OpenAiChatModel
import io.quarkiverse.langchain4j.ModelName
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.faulttolerance.Bulkhead
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import java.time.Duration

@ApplicationScoped
class OpenAiTextService {

    @Inject
    @field:ModelName("openai-text")
    lateinit var model: ChatModel

    @ConfigProperty(name = "quarkus.langchain4j.openai.openai-text.base-url", defaultValue = "https://api.openai.com/v1")
    lateinit var baseUrl: String

    @ConfigProperty(name = "quarkus.langchain4j.openai.openai-text.chat-model.model-name", defaultValue = "gpt-4o-mini")
    lateinit var defaultModelId: String

    @Bulkhead(value = 10, waitingTaskQueue = 5)
    @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.5, delay = 30000)
    @Retry(maxRetries = 2, delay = 500, jitter = 200)
    fun chat(
        messages: List<ChatMessage>,
        params: ModelParams? = null,
        apiKey: String? = null,
    ): ProviderResult {
        val activeModel = if (!apiKey.isNullOrBlank()) buildDynamic(apiKey, params) else model
        val request = ChatRequest.builder()
            .messages(messages)
            .parameters(buildParams(params))
            .build()
        val response = activeModel.chat(request)
        return ProviderResult(
            text = response.aiMessage().text() ?: "",
            inputTokens = response.tokenUsage()?.inputTokenCount(),
            outputTokens = response.tokenUsage()?.outputTokenCount(),
        )
    }

    private fun buildDynamic(apiKey: String, params: ModelParams?): ChatModel =
        OpenAiChatModel.builder()
            .apiKey(apiKey)
            .baseUrl(params?.baseUrl ?: baseUrl)  // per-request URL overrides server default
            .modelName(params?.model ?: defaultModelId)
            .timeout(Duration.ofSeconds(params?.timeoutSeconds?.toLong() ?: 120))
            .logRequests(false)
            .logResponses(false)
            .build()

    private fun buildParams(params: ModelParams?): ChatRequestParameters {
        val b = ChatRequestParameters.builder()
        params?.model?.let { b.modelName(it) }
        params?.temperature?.let { b.temperature(it) }
        params?.maxTokens?.let { b.maxOutputTokens(it) }
        params?.topP?.let { b.topP(it) }
        params?.stop?.let { b.stopSequences(it) }
        params?.frequencyPenalty?.let { b.frequencyPenalty(it) }
        params?.presencePenalty?.let { b.presencePenalty(it) }
        if (params?.jsonMode == true) {
            b.responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON).build())
        }
        return b.build()
    }
}
