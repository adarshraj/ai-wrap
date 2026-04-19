package com.adars.aishim.service

import com.adars.aishim.model.AiImageResponse
import com.adars.aishim.model.AiInvokeResponse
import com.adars.aishim.model.AiTemplatesResponse
import com.adars.aishim.model.ImageParams
import com.adars.aishim.model.ModelParams
import com.adars.aishim.model.ProviderInfo
import com.adars.aishim.model.TemplateInfo
import com.adars.aishim.model.ChatMessage as ChatMessageDto
import com.adars.aishim.provider.AiProvider
import com.adars.aishim.provider.ProviderResolver
import com.adars.aishim.provider.image.ImageServiceResolver
import com.adars.aishim.provider.image.ReferenceImage
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.BadRequestException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/** Template names must be safe path components — letters, digits, hyphens, underscores only. */
private val SAFE_TEMPLATE_NAME = Regex("^[a-zA-Z0-9_-]+$")

/** Finds all {placeholder} names in a string. */
private val VARIABLE_REGEX = Regex("""\{([a-zA-Z0-9_]+)}""")

/** Delimiter that separates system-prompt section from user-prompt section in a template file. */
private const val TEMPLATE_DELIMITER = "\n---\n"

/** Parsed representation of a prompt template file. */
private data class ParsedTemplate(
    /** Content before `---` delimiter, or null if no delimiter present. */
    val systemPrompt: String?,
    /** Content after `---` delimiter, or the entire file if no delimiter. */
    val userPrompt: String,
    /** All {placeholder} names found across both sections. */
    val variables: List<String>,
)

/** Resolved prompts ready to send to an LLM (single-turn). */
private data class ResolvedPrompt(val system: String?, val user: String)

@ApplicationScoped
class AiService @Inject constructor(
    private val resolver: ProviderResolver,
    private val imageResolver: ImageServiceResolver,
    @ConfigProperty(name = "quarkus.langchain4j.openai.openai-text.api-key", defaultValue = "DISABLED")
    private val openAiApiKey: String,
    @ConfigProperty(name = "quarkus.langchain4j.ai.gemini.gemini-text.api-key", defaultValue = "DISABLED")
    private val geminiApiKey: String,
    @ConfigProperty(name = "quarkus.langchain4j.openai.deepseek.api-key", defaultValue = "DISABLED")
    private val deepSeekApiKey: String,
    @ConfigProperty(name = "quarkus.langchain4j.anthropic.anthropic-text.api-key", defaultValue = "DISABLED")
    private val anthropicApiKey: String,
    @ConfigProperty(name = "quarkus.langchain4j.azure-openai.azure-openai-text.api-key", defaultValue = "DISABLED")
    private val azureOpenAiApiKey: String,
    @ConfigProperty(name = "aishim.max-prompt-chars", defaultValue = "200000")
    private val maxPromptChars: Int,
    private val registry: io.micrometer.core.instrument.MeterRegistry,
) {
    companion object {
        private val log: Logger = Logger.getLogger(AiService::class.java)

        /** In-memory template cache — templates are immutable classpath resources. */
        private val templateCache = ConcurrentHashMap<String, ParsedTemplate>()
    }

    // ── Text invoke ───────────────────────────────────────────────────────────

    fun invoke(
        rawPrompt: String?,
        templateName: String?,
        variables: Map<String, String>,
        systemPromptOverride: String?,
        multiTurnMessages: List<ChatMessageDto>?,
        provider: AiProvider,
        modelParams: ModelParams?,
        apiKey: String?,
    ): AiInvokeResponse {
        if (modelParams?.model.isNullOrBlank()) throw BadRequestException(
            "model_params.model is required. Call GET /ai/models?provider=${provider.name.lowercase()} to discover available models."
        )

        val messages = buildMessageList(rawPrompt, templateName, variables, systemPromptOverride, multiTurnMessages)

        val start = System.currentTimeMillis()
        val result = try {
            resolver.textFunction(provider, modelParams, apiKey)(messages)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            registry.counter("ai.requests.total", "provider", provider.name.lowercase(), "type", "text", "status", "failure").increment()
            registry.timer("ai.request.duration", "provider", provider.name.lowercase(), "type", "text").record(elapsed, java.util.concurrent.TimeUnit.MILLISECONDS)
            throw e
        }
        val elapsed = System.currentTimeMillis() - start

        registry.counter("ai.requests.total", "provider", provider.name.lowercase(), "type", "text", "status", "success").increment()
        registry.timer("ai.request.duration", "provider", provider.name.lowercase(), "type", "text").record(elapsed, java.util.concurrent.TimeUnit.MILLISECONDS)

        val source = when {
            !multiTurnMessages.isNullOrEmpty() -> "multi-turn(${multiTurnMessages.size})"
            rawPrompt != null -> "raw"
            else -> "template:$templateName"
        }
        log.infof("invoke source=%s provider=%s model=%s system=%s elapsed=%dms",
            source, provider, modelParams?.model ?: "default",
            if (messages.any { it is SystemMessage }) "yes" else "no", elapsed)

        return AiInvokeResponse(
            result = result.text.trim(),
            provider = provider.name.lowercase(),
            model = modelParams?.model,
            processingTimeMs = elapsed,
            inputTokens = result.inputTokens,
            outputTokens = result.outputTokens,
        )
    }

    // ── Vision invoke ─────────────────────────────────────────────────────────

    fun invokeVision(
        rawPrompt: String?,
        templateName: String?,
        variables: Map<String, String>,
        systemPromptOverride: String?,
        imageBytes: ByteArray,
        mimeType: String,
        provider: AiProvider,
        modelParams: ModelParams?,
        apiKey: String?,
    ): AiInvokeResponse {
        if (modelParams?.model.isNullOrBlank()) throw BadRequestException(
            "model_params.model is required. Call GET /ai/models?provider=${provider.name.lowercase()} to discover available models."
        )

        val start = System.currentTimeMillis()

        val (resultText, inputTokens, outputTokens) = try {
            val resolved = resolvePrompt(rawPrompt, systemPromptOverride, templateName, variables)
            val base64 = Base64.getEncoder().encodeToString(imageBytes)
            val providerResult = resolver.visionFunction(provider, resolved.system, modelParams, apiKey)(resolved.user, base64, mimeType)
            Triple(providerResult.text, providerResult.inputTokens, providerResult.outputTokens)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            registry.counter("ai.requests.total", "provider", provider.name.lowercase(), "type", "vision", "status", "failure").increment()
            registry.timer("ai.request.duration", "provider", provider.name.lowercase(), "type", "vision").record(elapsed, java.util.concurrent.TimeUnit.MILLISECONDS)
            throw e
        }

        val elapsed = System.currentTimeMillis() - start

        registry.counter("ai.requests.total", "provider", provider.name.lowercase(), "type", "vision", "status", "success").increment()
        registry.timer("ai.request.duration", "provider", provider.name.lowercase(), "type", "vision").record(elapsed, java.util.concurrent.TimeUnit.MILLISECONDS)

        log.infof("invokeVision source=%s provider=%s model=%s elapsed=%dms",
            if (rawPrompt != null) "raw" else "template:$templateName",
            provider, modelParams?.model ?: "default", elapsed)

        return AiInvokeResponse(
            result = resultText.trim(),
            provider = provider.name.lowercase(),
            model = modelParams?.model,
            processingTimeMs = elapsed,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
        )
    }

    // ── Image generation ──────────────────────────────────────────────────────

    fun generateImage(
        rawPrompt: String?,
        templateName: String?,
        variables: Map<String, String>,
        systemPromptOverride: String?,
        referenceImages: List<ReferenceImage>,
        provider: AiProvider,
        params: ImageParams?,
        apiKey: String?,
    ): AiImageResponse {
        if (!provider.supportsImageGen) throw BadRequestException(
            "Provider '${provider.name.lowercase()}' does not support image generation. " +
                "Currently supported: gemini."
        )
        if (params?.model.isNullOrBlank()) {
            // Gemini image gen has a sensible default so this is a soft warning, not a hard error —
            // the service falls back to aishim.image.gemini.default-model.
            log.debugf("image_params.model not supplied — provider default will be used")
        }

        val resolved = resolvePrompt(rawPrompt, systemPromptOverride, templateName, variables)
        val service = imageResolver.resolve(provider)

        val start = System.currentTimeMillis()
        val result = try {
            service.generate(resolved.user, resolved.system, params, referenceImages, apiKey)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            registry.counter("ai.requests.total", "provider", provider.name.lowercase(), "type", "image", "status", "failure").increment()
            registry.timer("ai.request.duration", "provider", provider.name.lowercase(), "type", "image").record(elapsed, java.util.concurrent.TimeUnit.MILLISECONDS)
            throw e
        }
        val elapsed = System.currentTimeMillis() - start

        registry.counter("ai.requests.total", "provider", provider.name.lowercase(), "type", "image", "status", "success").increment()
        registry.timer("ai.request.duration", "provider", provider.name.lowercase(), "type", "image").record(elapsed, java.util.concurrent.TimeUnit.MILLISECONDS)

        log.infof("generateImage source=%s provider=%s model=%s images=%d elapsed=%dms",
            if (rawPrompt != null) "raw" else "template:$templateName",
            provider, params?.model ?: "default", result.images.size, elapsed)

        return AiImageResponse(
            provider = provider.name.lowercase(),
            model = params?.model,
            processingTimeMs = elapsed,
            inputTokens = result.inputTokens,
            outputTokens = result.outputTokens,
            images = result.images,
            warnings = result.warnings,
        )
    }

    // ── Meta / discovery ─────────────────────────────────────────────────────

    fun templates(): AiTemplatesResponse {
        return AiTemplatesResponse(templates = listTemplates())
    }

    // ── Providers ─────────────────────────────────────────────────────────────

    fun providers(): List<ProviderInfo> = listOf(
        ProviderInfo(id = "openai", supportsText = true, supportsVision = true, supportsImageGen = false, enabled = openAiApiKey != "DISABLED"),
        ProviderInfo(id = "gemini", supportsText = true, supportsVision = true, supportsImageGen = true, enabled = geminiApiKey != "DISABLED"),
        ProviderInfo(id = "deepseek", supportsText = true, supportsVision = false, enabled = deepSeekApiKey != "DISABLED"),
        ProviderInfo(id = "anthropic", supportsText = true, supportsVision = true, enabled = anthropicApiKey != "DISABLED"),
        ProviderInfo(id = "azure_openai", supportsText = true, supportsVision = true, enabled = azureOpenAiApiKey != "DISABLED"),
        ProviderInfo(id = "groq", supportsText = true, supportsVision = true, enabled = true),
        ProviderInfo(id = "openrouter", supportsText = true, supportsVision = true, enabled = true),
        ProviderInfo(id = "mistral", supportsText = true, supportsVision = true, enabled = true),
        ProviderInfo(id = "cerebras", supportsText = true, supportsVision = false, enabled = true),
        ProviderInfo(id = "xai", supportsText = true, supportsVision = false, enabled = true),
        ProviderInfo(id = "cohere", supportsText = true, supportsVision = false, enabled = true),
        ProviderInfo(id = "ollama", supportsText = true, supportsVision = true, enabled = true),
    )

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds the final LangChain4j message list for a text request.
     *
     * Multi-turn mode (when [multiTurnMessages] is non-empty):
     *   - Converts [ChatMessageDto] list to LangChain4j messages.
     *   - If [systemPromptOverride] is provided, it replaces any existing system message.
     *   - Each message's content is checked by [PromptGuard] and [ContentGuard].
     *
     * Single-turn mode:
     *   - Delegates to [resolvePrompt] using [rawPrompt] / [templateName] + [variables].
     */
    private fun buildMessageList(
        rawPrompt: String?,
        templateName: String?,
        variables: Map<String, String>,
        systemPromptOverride: String?,
        multiTurnMessages: List<ChatMessageDto>?,
    ): List<ChatMessage> {
        if (!multiTurnMessages.isNullOrEmpty()) {
            multiTurnMessages.forEach { msg ->
                PromptGuard.assertSafe(msg.content, "messages[${msg.role}]")
                ContentGuard.assertSafe(msg.content)
            }

            // Enforce total size limit across all message content (including system prompt override)
            val totalChars = multiTurnMessages.sumOf { it.content.length } + (systemPromptOverride?.length ?: 0)
            if (totalChars > maxPromptChars) throw BadRequestException(
                "Total message content too large: $totalChars chars exceeds $maxPromptChars limit. Raise AI_WRAP_MAX_PROMPT_CHARS to allow longer conversations."
            )

            val l4j = mutableListOf<ChatMessage>()
            if (!systemPromptOverride.isNullOrBlank()) {
                PromptGuard.assertSafe(systemPromptOverride, "system_prompt")
                ContentGuard.assertSafe(systemPromptOverride)
                l4j.add(SystemMessage.from(systemPromptOverride))
                multiTurnMessages
                    .filter { it.role.lowercase() != "system" }
                    .forEach { l4j.add(it.toLangChain4j()) }
            } else {
                multiTurnMessages.forEach { l4j.add(it.toLangChain4j()) }
            }
            return l4j
        }

        val resolved = resolvePrompt(rawPrompt, systemPromptOverride, templateName, variables)
        return buildList {
            if (!resolved.system.isNullOrBlank()) add(SystemMessage.from(resolved.system))
            add(UserMessage.from(resolved.user))
        }
    }

    /**
     * Resolves the final system + user prompt pair for single-turn requests.
     *
     * Priority for system prompt:
     *   1. [systemPromptOverride] from the request (highest)
     *   2. System section in the template file (content before `---`)
     *   3. None
     *
     * [rawPrompt] bypasses templates; [templateName] + [variables] are used otherwise.
     * All user-supplied values are scanned by [PromptGuard] and [ContentGuard].
     */
    private fun resolvePrompt(
        rawPrompt: String?,
        systemPromptOverride: String?,
        templateName: String?,
        variables: Map<String, String>,
    ): ResolvedPrompt {
        val (templateSystem, userPrompt) = if (!rawPrompt.isNullOrBlank()) {
            if (rawPrompt.length > maxPromptChars) throw BadRequestException(
                "Prompt too large: ${rawPrompt.length} chars exceeds $maxPromptChars limit. Raise AI_WRAP_MAX_PROMPT_CHARS to allow longer prompts."
            )
            PromptGuard.assertSafe(rawPrompt, "prompt")
            Pair(null, rawPrompt)
        } else {
            if (templateName.isNullOrBlank()) throw BadRequestException(
                "Provide either 'prompt' (raw text), 'template' (name of a prompts/*.txt file), or 'messages' (multi-turn history)."
            )
            val parsed = loadTemplate(templateName)
            val totalVariableLength = variables.values.sumOf { it.length }
            if (totalVariableLength > maxPromptChars) throw BadRequestException(
                "Variable values too large: combined $totalVariableLength chars exceeds $maxPromptChars limit. Raise AI_WRAP_MAX_PROMPT_CHARS to allow longer prompts."
            )
            variables.forEach { (key, value) -> PromptGuard.assertSafe(value, key) }
            val user = substituteVariables(parsed.userPrompt, variables)
            val sys = parsed.systemPrompt?.let { substituteVariables(it, variables) }
            Pair(sys, user)
        }

        val finalSystem = systemPromptOverride?.takeIf { it.isNotBlank() } ?: templateSystem

        ContentGuard.assertSafe(userPrompt)
        if (finalSystem != null) ContentGuard.assertSafe(finalSystem)

        return ResolvedPrompt(system = finalSystem, user = userPrompt)
    }

    /**
     * Loads and parses a template from `prompts/{name}.txt` on the classpath.
     * Template format:
     *   Optional system prompt text
     *   ---
     *   User prompt text with {placeholders}
     *
     * If no `---` delimiter is present, the entire file is the user prompt.
     * Results are cached after first load.
     */
    private fun loadTemplate(name: String): ParsedTemplate {
        if (!SAFE_TEMPLATE_NAME.matches(name)) throw BadRequestException(
            "Invalid template name '$name'. Only letters, digits, hyphens, and underscores are allowed."
        )
        return templateCache.getOrPut(name) {
            val raw = AiService::class.java.classLoader
                .getResourceAsStream("prompts/$name.txt")
                ?.bufferedReader()?.readText()
                ?: throw BadRequestException("Template '$name' not found. Add prompts/$name.txt to the classpath.")
            parseTemplate(raw)
        }
    }

    private fun parseTemplate(raw: String): ParsedTemplate {
        val delimiterIndex = raw.indexOf(TEMPLATE_DELIMITER)
        val (system, user) = if (delimiterIndex >= 0) {
            val sys = raw.substring(0, delimiterIndex).trim().takeIf { it.isNotBlank() }
            val usr = raw.substring(delimiterIndex + TEMPLATE_DELIMITER.length).trim()
            sys to usr
        } else {
            null to raw.trim()
        }
        val allContent = listOfNotNull(system, user).joinToString("\n")
        val variables = VARIABLE_REGEX.findAll(allContent)
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()
        return ParsedTemplate(systemPrompt = system, userPrompt = user, variables = variables)
    }

    private fun substituteVariables(template: String, variables: Map<String, String>): String {
        var result = template
        variables.forEach { (key, value) -> result = result.replace("{$key}", value) }
        return result
    }

    private fun ChatMessageDto.toLangChain4j(): ChatMessage = when (role.lowercase()) {
        "system" -> SystemMessage.from(content)
        "assistant" -> AiMessage.from(content)
        else -> UserMessage.from(content)
    }

    /**
     * Scans the classpath `prompts/` directory and returns TemplateInfo for each .txt file.
     * Works in both dev mode (filesystem) and production (fat JAR).
     */
    private fun listTemplates(): List<TemplateInfo> {
        val url = AiService::class.java.classLoader.getResource("prompts") ?: return emptyList()
        val names: List<String> = try {
            when (url.protocol) {
                "file" -> File(url.toURI()).listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".txt") }
                    ?.map { it.nameWithoutExtension }
                    ?.sorted() ?: emptyList()
                "jar" -> {
                    val jarPath = url.path.substringBefore("!").removePrefix("file:")
                    JarFile(jarPath).use { jar ->
                        jar.entries().asSequence()
                            .filter { !it.isDirectory && it.name.startsWith("prompts/") && it.name.endsWith(".txt") }
                            .map { it.name.removePrefix("prompts/").removeSuffix(".txt") }
                            .sorted().toList()
                    }
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            log.warnf("Could not list templates: %s", e.message)
            emptyList()
        }

        return names.map { name ->
            try {
                val parsed = loadTemplate(name)
                TemplateInfo(name = name, variables = parsed.variables, hasSystemPrompt = parsed.systemPrompt != null)
            } catch (e: Exception) {
                TemplateInfo(name = name, variables = emptyList(), hasSystemPrompt = false)
            }
        }
    }

}
