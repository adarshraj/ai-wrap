package com.adars.aishim.provider.gemini

import com.adars.aishim.model.ModelParams
import com.adars.aishim.model.ProviderResult
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.ChatRequestParameters
import dev.langchain4j.model.chat.request.ResponseFormat
import dev.langchain4j.model.chat.request.ResponseFormatType
import io.quarkiverse.langchain4j.ModelName
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Bulkhead
import org.eclipse.microprofile.faulttolerance.CircuitBreaker
import org.eclipse.microprofile.faulttolerance.Retry
import org.jboss.logging.Logger

@ApplicationScoped
class GeminiTextService {

    @Inject
    @field:ModelName("gemini-text")
    lateinit var model: ChatModel

    @Bulkhead(value = 10, waitingTaskQueue = 5)
    @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.5, delay = 30000)
    @Retry(maxRetries = 2, delay = 500, jitter = 200)
    fun chat(
        messages: List<ChatMessage>,
        params: ModelParams? = null,
        apiKey: String? = null,
    ): ProviderResult {
        if (!apiKey.isNullOrBlank()) {
            log.warn("Per-request api_key is not supported for Gemini — using the server-configured key.")
        }
        val request = ChatRequest.builder()
            .messages(messages)
            .parameters(buildParams(params))
            .build()
        val response = model.chat(request)
        return ProviderResult(
            text = response.aiMessage().text() ?: "",
            inputTokens = response.tokenUsage()?.inputTokenCount(),
            outputTokens = response.tokenUsage()?.outputTokenCount(),
        )
    }

    private fun buildParams(params: ModelParams?): ChatRequestParameters {
        if (params?.frequencyPenalty != null || params?.presencePenalty != null) {
            log.warn("Gemini does not support frequency_penalty or presence_penalty — they will be ignored.")
        }
        val b = ChatRequestParameters.builder()
        params?.model?.let { b.modelName(it) }
        params?.temperature?.let { b.temperature(it) }
        params?.maxTokens?.let { b.maxOutputTokens(it) }
        params?.topP?.let { b.topP(it) }
        params?.stop?.let { b.stopSequences(it) }
        if (params?.jsonMode == true) {
            b.responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON).build())
        }
        return b.build()
    }

    companion object {
        private val log: Logger = Logger.getLogger(GeminiTextService::class.java)
    }
}
