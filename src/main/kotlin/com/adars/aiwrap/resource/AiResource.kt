package com.adars.aiwrap.resource

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.adars.aiwrap.model.AiInvokeRequest
import com.adars.aiwrap.model.AiInvokeResponse
import com.adars.aiwrap.model.AiMetaResponse
import com.adars.aiwrap.model.ModelParams
import com.adars.aiwrap.provider.AiProvider
import com.adars.aiwrap.service.AiService
import com.adars.aiwrap.service.AuditService
import com.adars.aiwrap.service.RateLimitExceededException
import com.adars.aiwrap.service.RateLimiter
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
 * AI gateway — three endpoints.
 *
 * GET  /ai/meta          — discovery: available templates, providers, capabilities
 * POST /ai/invoke        — text: loads template (or raw prompt), calls the provider
 * POST /ai/invoke/vision — vision: same, but also attaches an image/PDF file
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
    private val rateLimiter: RateLimiter,
    private val securityIdentity: SecurityIdentity,
    private val objectMapper: ObjectMapper,
    private val auditService: AuditService,
    @ConfigProperty(name = "aiwrap.max-upload-bytes", defaultValue = "20971520")
    private val maxUploadBytes: Long,
) {
    companion object {
        private val log: Logger = Logger.getLogger(AiResource::class.java)
    }

    /** GET /ai/meta — discovery endpoint. See AiMetaResponse for full schema. */
    @Operation(summary = "Discovery — available templates and provider capabilities")
    @APIResponse(responseCode = "200", description = "Templates and provider list",
        content = [Content(schema = Schema(implementation = AiMetaResponse::class))])
    @GET
    @Path("/meta")
    @RolesAllowed("**")
    fun meta(): Response = Response.ok(aiService.meta())
        .header("Cache-Control", "public, max-age=300")
        .build()

    /**
     * POST /ai/invoke — text-only AI invocation.
     *
     * Body (JSON):
     * {
     *   "provider":      "openai" | "gemini" | "deepseek" | "ollama",
     *   "prompt":        "raw prompt text",          // use this OR template
     *   "template":      "insights-qa",              // maps to prompts/insights-qa.txt
     *   "variables":     { "question": "...", "context": "..." },
     *   "system_prompt": "You are a tax advisor.",   // optional, overrides template system section
     *   "model_params":  { "model": "gpt-4o", "temperature": 0.7, "max_tokens": 2000 },
     *   "api_key":       "sk-..."                    // optional, informational
     * }
     */
    @Operation(summary = "Text invocation — prompt or template to LLM response")
    @APIResponses(
        APIResponse(responseCode = "200", description = "LLM response"),
        APIResponse(responseCode = "400", description = "Bad request — unknown provider, invalid template, or blocked content"),
        APIResponse(responseCode = "401", description = "Unauthorized — missing or invalid JWT"),
        APIResponse(responseCode = "429", description = "Rate limit exceeded"),
        APIResponse(responseCode = "500", description = "Provider error"),
    )
    @POST
    @Path("/invoke")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("**")
    fun invoke(request: AiInvokeRequest): Response {
        val userId = securityIdentity.principal?.name ?: "unknown"
        log.debugf("invoke — user=%s provider=%s template=%s", userId, request.provider, request.template)

        return try {
            val remaining = rateLimiter.check(userId)
            val provider = AiProvider.fromString(request.provider)
            val result: AiInvokeResponse = aiService.invoke(
                request.prompt, request.template, request.variables,
                request.systemPrompt, request.messages, provider, request.modelParams, request.apiKey,
            )
            auditService.record(userId, "text", result.provider, request.template, result.model, result.processingTimeMs, true,
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
            log.errorf(e, "invoke failed — user=%s template=%s", userId, request.template)
            auditService.record(userId, "text", request.provider, request.template, null, 0, false, e.javaClass.simpleName,
                inputTokens = null, outputTokens = null)
            Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(errorBody(e.message)).build()
        }
    }

    /**
     * POST /ai/invoke/vision — vision AI invocation (image or PDF + prompt).
     *
     * Multipart form fields:
     *   file          — image (JPEG/PNG/WebP/BMP/HEIC) or PDF, max 20 MB
     *   provider      — "openai" | "gemini" | "paddle"
     *   prompt        — raw prompt text (use this OR template)
     *   template      — template name, e.g. "ocr-receipt-structured"
     *   variables     — JSON string, e.g. '{"hint":"grocery"}'  (optional)
     *   system_prompt — optional system prompt string
     *   model_params  — JSON string, e.g. '{"model":"gpt-4o","temperature":0.1}'
     *   api_key       — optional, informational
     *
     * For PADDLE: prompt/template/system_prompt are ignored; raw OCR text is returned.
     */
    @Operation(summary = "Vision invocation — image or PDF with prompt to LLM response")
    @APIResponses(
        APIResponse(responseCode = "200", description = "LLM or OCR response"),
        APIResponse(responseCode = "400", description = "Bad request"),
        APIResponse(responseCode = "401", description = "Unauthorized"),
        APIResponse(responseCode = "413", description = "File too large"),
        APIResponse(responseCode = "429", description = "Rate limit exceeded"),
        APIResponse(responseCode = "500", description = "Provider error"),
    )
    @POST
    @Path("/invoke/vision")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed("**")
    fun invokeVision(
        @RestForm("file") file: FileUpload,
        @RestForm("provider") provider: String,
        @RestForm("prompt") prompt: String?,
        @RestForm("template") template: String?,
        @RestForm("variables") variablesJson: String?,
        @RestForm("system_prompt") systemPrompt: String?,
        @RestForm("model_params") modelParamsJson: String?,
        @RestForm("api_key") apiKey: String?,
    ): Response {
        val userId = securityIdentity.principal?.name ?: "unknown"
        log.debugf("invokeVision — user=%s provider=%s template=%s", userId, provider, template)

        return try {
            val remaining = rateLimiter.check(userId)
            if (file.size() > maxUploadBytes) {
                return Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE)
                    .entity(errorBody("File too large: ${file.size() / 1_048_576}MB exceeds ${maxUploadBytes / 1_048_576}MB limit. " +
                            "Raise AI_WRAP_MAX_UPLOAD_BYTES to allow larger files."))
                    .build()
            }
            val aiProvider = AiProvider.fromString(provider)
            val imageBytes = file.uploadedFile().toFile().readBytes()
            val mimeType = file.contentType() ?: "image/jpeg"
            val variables: Map<String, String> = if (!variablesJson.isNullOrBlank())
                objectMapper.readValue(variablesJson) else emptyMap()
            val modelParams: ModelParams? = if (!modelParamsJson.isNullOrBlank())
                objectMapper.readValue(modelParamsJson) else null

            val result: AiInvokeResponse = aiService.invokeVision(
                prompt, template, variables, systemPrompt,
                imageBytes, mimeType, aiProvider, modelParams, apiKey,
            )
            auditService.record(userId, "vision", result.provider, template, result.model, result.processingTimeMs, true,
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
            log.errorf(e, "invokeVision failed — user=%s template=%s", userId, template)
            auditService.record(userId, "vision", provider, template, null, 0, false, e.javaClass.simpleName,
                inputTokens = null, outputTokens = null)
            Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(errorBody(e.message)).build()
        }
    }

    private fun errorBody(message: String?) = mapOf("error" to (message ?: "Unknown error"))
}
