package com.adars.aiwrap.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

// ── Provider result (internal) ────────────────────────────────────────────────

/** Text output plus token usage from a provider call. tokenUsage may be null if the provider doesn't report it. */
data class ProviderResult(
    val text: String,
    val inputTokens: Int?,
    val outputTokens: Int?,
)

// ── Meta / discovery ─────────────────────────────────────────────────────────

/** Response for GET /ai/meta */
data class AiMetaResponse(
    /** All available prompt templates with their variable placeholders. */
    val templates: List<TemplateInfo>,
    /** All configured providers and their capabilities. */
    val providers: List<ProviderInfo>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TemplateInfo(
    /** Template name — pass this as the `template` field in requests. */
    val name: String,
    /** All {placeholder} names found in this template's user-prompt section. */
    val variables: List<String>,
    /**
     * Whether this template defines a system prompt (content before the `---` delimiter).
     * If true, the system prompt is automatically applied; it can be overridden per-request
     * via the `system_prompt` field.
     */
    @JsonProperty("has_system_prompt") val hasSystemPrompt: Boolean,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProviderInfo(
    /** Value to pass in the `provider` field of requests. */
    val id: String,
    @JsonProperty("supports_text") val supportsText: Boolean,
    @JsonProperty("supports_vision") val supportsVision: Boolean,
    /** Default model configured in application.properties. Null for PADDLE (not an LLM). */
    @JsonProperty("default_model") val defaultModel: String?,
    /** Whether this provider is currently enabled (API key present and non-disabled). */
    val enabled: Boolean,
)

// ── Model parameters ─────────────────────────────────────────────────────────

/**
 * Optional per-request LLM tuning knobs.
 * Omitted fields fall back to the defaults configured in application.properties.
 *
 * Example:
 *   { "model": "gpt-4o", "temperature": 0.7, "max_tokens": 4000, "top_p": 0.9, "json_mode": true }
 */
data class ModelParams(
    /** Provider-specific model ID, e.g. "gpt-4o", "gemini-2.5-flash", "deepseek-reasoner". */
    val model: String? = null,
    /** Sampling temperature 0.0 – 2.0. Lower = more deterministic, higher = more creative. */
    val temperature: Double? = null,
    /** Maximum tokens in the response. */
    @JsonProperty("max_tokens") val maxTokens: Int? = null,
    /** Nucleus sampling — considers tokens with cumulative probability mass of top_p. */
    @JsonProperty("top_p") val topP: Double? = null,
    /** Stop sequences — model stops generating when any of these strings are emitted. */
    val stop: List<String>? = null,
    /** Penalises tokens based on their existing frequency in the output so far (OpenAI/DeepSeek). */
    @JsonProperty("frequency_penalty") val frequencyPenalty: Double? = null,
    /** Penalises tokens that have appeared at all in the output so far (OpenAI/DeepSeek). */
    @JsonProperty("presence_penalty") val presencePenalty: Double? = null,
    /** When true, instructs the model to respond with a valid JSON object. */
    @JsonProperty("json_mode") val jsonMode: Boolean? = null,
    /**
     * Per-request timeout in seconds for providers that support dynamic client construction
     * (OpenAI, DeepSeek). For Gemini, the timeout is fixed at startup via application.properties
     * and cannot be overridden at request time.
     */
    @JsonProperty("timeout_seconds") val timeoutSeconds: Int? = null,
    /**
     * Custom base URL for OpenAI-compatible providers (e.g. "https://api.groq.com/openai/v1").
     * Overrides the server-configured default for OPENAI and all OpenAI-compatible providers.
     * Required when [provider] is OPENAI_COMPATIBLE.
     */
    @JsonProperty("base_url") val baseUrl: String? = null,
)

// ── Multi-turn chat ───────────────────────────────────────────────────────────

/**
 * A single message in a multi-turn conversation.
 *
 * [role] must be one of: "user", "assistant", "system".
 * [content] is the plain-text message content.
 */
data class ChatMessage(
    val role: String,
    val content: String,
)

// ── Text invoke ───────────────────────────────────────────────────────────────

/**
 * Body for POST /ai/invoke (text-only).
 *
 * **Single-turn**: supply [prompt] (raw text) OR [template] + [variables].
 * **Multi-turn**: supply [messages] — a full conversation history with role/content pairs.
 * When [messages] is provided it takes precedence over [prompt]/[template].
 *
 * [system_prompt] overrides the system persona for either mode; if a template already
 * defines a system section (content before `---`), this field overrides it.
 */
data class AiInvokeRequest(
    /** One of: openai, gemini, deepseek, ollama */
    val provider: String,
    /**
     * Raw prompt sent directly to the LLM as the user message. Takes precedence over [template].
     * Ignored when [messages] is provided.
     */
    val prompt: String? = null,
    /**
     * Template file name without extension, e.g. "insights-qa".
     * Used only when [prompt] and [messages] are not provided. Must be [a-zA-Z0-9_-]+.
     */
    val template: String? = null,
    /** Key → value pairs matching the {placeholders} in the template. Ignored when [prompt] is provided. */
    val variables: Map<String, String> = emptyMap(),
    /**
     * Optional system prompt — sets the AI's role, persona, or constraints for this request.
     * Overrides the system section in the template (if any).
     * In multi-turn mode, replaces any existing system message at the start of the [messages] list.
     */
    @JsonProperty("system_prompt") val systemPrompt: String? = null,
    /**
     * Full conversation history for multi-turn chat. Each item has a [role] (user/assistant/system)
     * and [content]. When provided, takes precedence over [prompt] and [template].
     * Include all previous turns; the service forwards them to the LLM as-is.
     */
    val messages: List<ChatMessage>? = null,
    /** Optional per-request model/temperature/token overrides. Defaults come from application.properties. */
    @JsonProperty("model_params") val modelParams: ModelParams? = null,
    /** Optional per-request API key for the chosen provider. Overrides the server-configured key. */
    @JsonProperty("api_key") val apiKey: String? = null,
) {
    override fun toString(): String =
        "AiInvokeRequest(provider=$provider, template=$template, prompt=${prompt?.take(50)}, " +
        "api_key=${if (apiKey != null) "[REDACTED]" else "null"})"
}

// ── Shared response ───────────────────────────────────────────────────────────

/**
 * Unified response for both /ai/invoke and /ai/invoke/vision.
 * The [result] is the raw LLM output; the caller is responsible for parsing JSON if needed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AiInvokeResponse(
    /** Raw text returned by the LLM (or raw OCR text for PADDLE). */
    val result: String,
    val provider: String,
    val model: String?,
    @JsonProperty("processing_time_ms") val processingTimeMs: Long,
    @JsonProperty("input_tokens") val inputTokens: Int? = null,
    @JsonProperty("output_tokens") val outputTokens: Int? = null,
)
