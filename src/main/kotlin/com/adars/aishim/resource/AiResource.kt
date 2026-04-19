package com.adars.aishim.resource

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.adars.aishim.model.AiImageResponse
import com.adars.aishim.model.AiInvokeResponse
import com.adars.aishim.model.AiTemplatesResponse
import com.adars.aishim.model.ImageParams
import com.adars.aishim.model.ModelParams
import com.adars.aishim.provider.AiProvider
import com.adars.aishim.provider.image.ReferenceImage
import com.adars.aishim.service.AiService
import com.adars.aishim.service.AuditService
import com.adars.aishim.service.ModelListService
import com.adars.aishim.service.RateLimitExceededException
import com.adars.aishim.service.RateLimiter
import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException
import jakarta.annotation.security.RolesAllowed
import org.eclipse.microprofile.config.inject.ConfigProperty
import jakarta.inject.Inject
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import io.quarkus.security.identity.SecurityIdentity
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.RestForm
import org.jboss.resteasy.reactive.multipart.FileUpload

/**
 * AI gateway — four endpoints.
 *
 * GET  /ai/templates                  — available prompt templates
 * GET  /ai/providers                  — supported providers and capabilities
 * GET  /ai/providers/{provider}/models — models available from a provider
 * POST /ai                            — unified text + vision (attach a file for vision)
 *
 * Adding new AI features requires only a new .txt file under src/main/resources/prompts/.
 * No code changes to this gateway are needed.
 *
 * Authentication: all endpoints require a valid JWT (Authorization: Bearer <token>).
 */
@Tag(name = "AI", description = "Vendor-agnostic AI invocation")
@Path("/ai")
@Produces(MediaType.APPLICATION_JSON)
class AiResource @Inject constructor(
    private val aiService: AiService,
    private val modelListService: ModelListService,
    private val rateLimiter: RateLimiter,
    private val securityIdentity: SecurityIdentity,
    private val objectMapper: ObjectMapper,
    private val auditService: AuditService,
    @ConfigProperty(name = "aishim.max-upload-bytes", defaultValue = "20971520")
    private val maxUploadBytes: Long,
) {
    companion object {
        private val log: Logger = Logger.getLogger(AiResource::class.java)
    }

    /** GET /ai/templates — list available prompt templates. */
    @Operation(summary = "Templates — available prompt templates and their variables")
    @APIResponse(responseCode = "200", description = "Template list",
        content = [Content(schema = Schema(implementation = AiTemplatesResponse::class))])
    @GET
    @Path("/templates")
    @RolesAllowed("**")
    fun templates(): Response = Response.ok(aiService.templates())
        .header("Cache-Control", "public, max-age=300")
        .build()

    /** GET /ai/providers — list all supported providers and their capabilities. */
    @Operation(summary = "List providers — supported AI providers and their capabilities")
    @APIResponse(responseCode = "200", description = "Provider list")
    @GET
    @Path("/providers")
    @RolesAllowed("**")
    fun providers(): Response = Response.ok(aiService.providers())
        .header("Cache-Control", "public, max-age=300")
        .build()

    /**
     * GET /ai/providers/{provider}/models — list models available from a provider.
     *
     * Proxies the upstream provider's model listing API and returns a unified response.
     * Pass the provider API key via the `X-Provider-Api-Key` header to avoid key leakage.
     */
    @Operation(summary = "List models — available models for a given provider")
    @APIResponses(
        APIResponse(responseCode = "200", description = "Model list"),
        APIResponse(responseCode = "400", description = "Bad request — unknown provider or missing API key"),
        APIResponse(responseCode = "401", description = "Unauthorized"),
        APIResponse(responseCode = "500", description = "Upstream provider error"),
    )
    @GET
    @Path("/providers/{provider}/models")
    @RolesAllowed("**")
    fun models(
        @PathParam("provider") provider: String,
        @HeaderParam("X-Provider-Api-Key") apiKey: String?,
    ): Response {
        return try {
            val aiProvider = AiProvider.fromString(provider)
            val result = modelListService.listModels(aiProvider, apiKey)
            Response.ok(result)
                .header("Cache-Control", "public, max-age=60")
                .build()
        } catch (e: IllegalArgumentException) {
            Response.status(Response.Status.BAD_REQUEST).entity(errorBody(e.message)).build()
        } catch (e: UnsupportedOperationException) {
            Response.status(Response.Status.BAD_REQUEST).entity(errorBody(e.message)).build()
        } catch (e: Exception) {
            log.errorf(e, "models failed — provider=%s", provider)
            Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(errorBody(e.message)).build()
        }
    }

    /**
     * POST /ai — unified AI invocation (text and vision).
     *
     * Multipart form fields:
     *   provider      — required: "openai" | "gemini" | "anthropic" | "ollama" | ...
     *   prompt        — raw prompt text (use this OR template)
     *   template      — template name from the classpath prompts directory (optional)
     *   variables     — JSON string, e.g. '{"question":"...", "context":"..."}'  (optional)
     *   system_prompt — optional system prompt string
     *   messages      — JSON string, multi-turn history (optional)
     *   model_params  — JSON string, e.g. '{"model":"gpt-4o","temperature":0.7}' (model is required)
     *   api_key       — optional per-request provider API key
     *   file          — optional image (JPEG/PNG/WebP/BMP/HEIC) or PDF for vision requests
     *
     * If `file` is attached → vision request. Otherwise → text request.
     */
    @Operation(summary = "AI — unified text and vision invocation")
    @APIResponses(
        APIResponse(responseCode = "200", description = "LLM response"),
        APIResponse(responseCode = "400", description = "Bad request — unknown provider, invalid template, or blocked content"),
        APIResponse(responseCode = "401", description = "Unauthorized — missing or invalid JWT"),
        APIResponse(responseCode = "413", description = "File too large"),
        APIResponse(responseCode = "429", description = "Rate limit exceeded"),
        APIResponse(responseCode = "500", description = "Provider error"),
    )
    @POST
    @Path("/")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed("**")
    fun chat(
        @RestForm("provider") providerStr: String,
        @RestForm("prompt") prompt: String?,
        @RestForm("template") template: String?,
        @RestForm("variables") variablesJson: String?,
        @RestForm("system_prompt") systemPrompt: String?,
        @RestForm("messages") messagesJson: String?,
        @RestForm("model_params") modelParamsJson: String?,
        @RestForm("api_key") apiKey: String?,
        @RestForm("file") file: FileUpload?,
    ): Response {
        val userId = securityIdentity.principal?.name ?: "unknown"
        val isVision = file != null && file.size() > 0
        val type = if (isVision) "vision" else "text"
        log.debugf("chat — user=%s provider=%s type=%s template=%s", userId, providerStr, type, template)

        return try {
            val remaining = rateLimiter.check(userId)
            val provider = AiProvider.fromString(providerStr)
            val variables: Map<String, String> = if (!variablesJson.isNullOrBlank())
                objectMapper.readValue(variablesJson) else emptyMap()
            val modelParams: ModelParams? = if (!modelParamsJson.isNullOrBlank())
                objectMapper.readValue(modelParamsJson) else null
            val messages: List<com.adars.aishim.model.ChatMessage>? = if (!messagesJson.isNullOrBlank())
                objectMapper.readValue(messagesJson) else null

            val result: AiInvokeResponse = if (isVision) {
                if (file!!.size() > maxUploadBytes) {
                    return Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE)
                        .entity(errorBody("File too large: ${file.size() / 1_048_576}MB exceeds ${maxUploadBytes / 1_048_576}MB limit. " +
                                "Raise AI_WRAP_MAX_UPLOAD_BYTES to allow larger files."))
                        .build()
                }
                val imageBytes = file.uploadedFile().toFile().readBytes()
                val mimeType = file.contentType() ?: "image/jpeg"
                aiService.invokeVision(
                    prompt, template, variables, systemPrompt,
                    imageBytes, mimeType, provider, modelParams, apiKey,
                )
            } else {
                aiService.invoke(
                    prompt, template, variables, systemPrompt, messages,
                    provider, modelParams, apiKey,
                )
            }

            auditService.record(userId, type, result.provider, template, result.model, result.processingTimeMs, true,
                inputTokens = result.inputTokens, outputTokens = result.outputTokens)
            Response.ok(result)
                .header("X-RateLimit-Limit", rateLimiter.requestsPerMinute)
                .header("X-RateLimit-Remaining", remaining)
                .build()
        } catch (e: BadRequestException) {
            Response.status(Response.Status.BAD_REQUEST).entity(errorBody(e.message)).build()
        } catch (e: IllegalArgumentException) {
            Response.status(Response.Status.BAD_REQUEST).entity(errorBody(e.message)).build()
        } catch (e: UnsupportedOperationException) {
            Response.status(Response.Status.BAD_REQUEST).entity(errorBody(e.message)).build()
        } catch (e: RateLimitExceededException) {
            Response.status(429)
                .entity(errorBody(e.message))
                .header("X-RateLimit-Limit", e.limit)
                .header("X-RateLimit-Remaining", 0)
                .header("Retry-After", e.retryAfterSeconds)
                .build()
        } catch (e: BulkheadException) {
            Response.status(503).entity(errorBody("Server is busy — too many concurrent requests to this provider. Try again shortly.")).build()
        } catch (e: Exception) {
            log.errorf(e, "chat failed — user=%s type=%s template=%s", userId, type, template)
            auditService.record(userId, type, providerStr, template, null, 0, false, e.javaClass.simpleName,
                inputTokens = null, outputTokens = null)
            Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(errorBody(e.message)).build()
        }
    }

    /**
     * POST /ai/image — image generation.
     *
     * Multipart form fields:
     *   provider      — required: "gemini" (more providers coming)
     *   prompt        — raw prompt text (use this OR template)
     *   template      — template name from the classpath prompts directory (optional)
     *   variables     — JSON string of template variables (optional)
     *   system_prompt — optional style/persona prefix
     *   image_params  — JSON string, e.g. '{"model":"gemini-2.5-flash-image","size":"1024x1024"}'
     *   api_key       — optional per-request provider API key
     *   reference     — optional image uploads for edit/variation flows (repeatable)
     */
    @Operation(summary = "AI — image generation")
    @APIResponses(
        APIResponse(responseCode = "200", description = "Generated images",
            content = [Content(schema = Schema(implementation = AiImageResponse::class))]),
        APIResponse(responseCode = "400", description = "Bad request — unknown provider, invalid template, blocked content, or unsupported capability"),
        APIResponse(responseCode = "401", description = "Unauthorized — missing or invalid JWT"),
        APIResponse(responseCode = "413", description = "Reference file too large"),
        APIResponse(responseCode = "429", description = "Rate limit exceeded"),
        APIResponse(responseCode = "500", description = "Provider error"),
    )
    @POST
    @Path("/image")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed("**")
    fun image(
        @RestForm("provider") providerStr: String,
        @RestForm("prompt") prompt: String?,
        @RestForm("template") template: String?,
        @RestForm("variables") variablesJson: String?,
        @RestForm("system_prompt") systemPrompt: String?,
        @RestForm("image_params") imageParamsJson: String?,
        @RestForm("api_key") apiKey: String?,
        @RestForm("reference") references: List<FileUpload>?,
    ): Response {
        val userId = securityIdentity.principal?.name ?: "unknown"
        log.debugf("image — user=%s provider=%s template=%s refs=%d",
            userId, providerStr, template, references?.size ?: 0)

        return try {
            val remaining = rateLimiter.check(userId)
            val provider = AiProvider.fromString(providerStr)
            val variables: Map<String, String> = if (!variablesJson.isNullOrBlank())
                objectMapper.readValue(variablesJson) else emptyMap()
            val imageParams: ImageParams? = if (!imageParamsJson.isNullOrBlank())
                objectMapper.readValue(imageParamsJson) else null

            val refImages = (references ?: emptyList())
                .filter { it.size() > 0 }
                .map { file ->
                    if (file.size() > maxUploadBytes) {
                        throw TooLargeException(
                            "Reference file too large: ${file.size() / 1_048_576}MB exceeds ${maxUploadBytes / 1_048_576}MB limit. " +
                                "Raise AI_WRAP_MAX_UPLOAD_BYTES to allow larger files."
                        )
                    }
                    ReferenceImage(
                        bytes = file.uploadedFile().toFile().readBytes(),
                        mimeType = file.contentType() ?: "image/png",
                    )
                }

            val result = aiService.generateImage(
                prompt, template, variables, systemPrompt,
                refImages, provider, imageParams, apiKey,
            )

            auditService.record(userId, "image", result.provider, template, result.model, result.processingTimeMs, true,
                inputTokens = result.inputTokens, outputTokens = result.outputTokens)

            Response.ok(result)
                .header("X-RateLimit-Limit", rateLimiter.requestsPerMinute)
                .header("X-RateLimit-Remaining", remaining)
                .build()
        } catch (e: TooLargeException) {
            Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE).entity(errorBody(e.message)).build()
        } catch (e: BadRequestException) {
            Response.status(Response.Status.BAD_REQUEST).entity(errorBody(e.message)).build()
        } catch (e: IllegalArgumentException) {
            Response.status(Response.Status.BAD_REQUEST).entity(errorBody(e.message)).build()
        } catch (e: UnsupportedOperationException) {
            Response.status(Response.Status.BAD_REQUEST).entity(errorBody(e.message)).build()
        } catch (e: RateLimitExceededException) {
            Response.status(429)
                .entity(errorBody(e.message))
                .header("X-RateLimit-Limit", e.limit)
                .header("X-RateLimit-Remaining", 0)
                .header("Retry-After", e.retryAfterSeconds)
                .build()
        } catch (e: BulkheadException) {
            Response.status(503).entity(errorBody("Server is busy — too many concurrent image requests. Try again shortly.")).build()
        } catch (e: Exception) {
            log.errorf(e, "image failed — user=%s template=%s", userId, template)
            auditService.record(userId, "image", providerStr, template, null, 0, false, e.javaClass.simpleName,
                inputTokens = null, outputTokens = null)
            Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(errorBody(e.message)).build()
        }
    }

    private fun errorBody(message: String?) = mapOf("error" to (message ?: "Unknown error"))

    private class TooLargeException(message: String) : RuntimeException(message)
}
