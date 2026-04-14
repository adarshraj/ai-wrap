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

// ── Templates / discovery ────────────────────────────────────────────────────

/** Response for GET /ai/templates */
data class AiTemplatesResponse(
    /** All available prompt templates with their variable placeholders. */
    val templates: List<TemplateInfo>,
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
    @JsonProperty("supports_image_gen") val supportsImageGen: Boolean = false,
    /** Whether this provider is currently enabled (API key present and non-disabled). */
    val enabled: Boolean,
)

// ── Model parameters ─────────────────────────────────────────────────────────

/**
 * Per-request LLM tuning knobs. [model] is required — call GET /ai/models?provider=... to discover available models.
 *
 * Example:
 *   { "model": "gpt-4o", "temperature": 0.7, "max_tokens": 4000, "top_p": 0.9, "json_mode": true }
 */
data class ModelParams(
    /** Required. Provider-specific model ID, e.g. "gpt-4o", "gemini-2.5-flash", "deepseek-reasoner". */
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

// ── Response ─────────────────────────────────────────────────────────────────

/**
 * Unified response for POST /ai/chat (text and vision).
 * The [result] is the raw LLM output; the caller is responsible for parsing JSON if needed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AiInvokeResponse(
    /** Raw text returned by the LLM. */
    val result: String,
    val provider: String,
    val model: String?,
    @JsonProperty("processing_time_ms") val processingTimeMs: Long,
    @JsonProperty("input_tokens") val inputTokens: Int? = null,
    @JsonProperty("output_tokens") val outputTokens: Int? = null,
)
