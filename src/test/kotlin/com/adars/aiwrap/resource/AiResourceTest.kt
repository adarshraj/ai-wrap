package com.adars.aiwrap.resource

import com.adars.aiwrap.model.AiInvokeResponse
import com.adars.aiwrap.model.AiMetaResponse
import com.adars.aiwrap.service.AiService
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files

@QuarkusTest
class AiResourceTest {

    @InjectMock
    lateinit var aiService: AiService

    private val stubResponse = AiInvokeResponse(
        result = "stub response",
        provider = "openai",
        model = "gpt-4o-mini",
        processingTimeMs = 42,
    )

    @BeforeEach
    fun setup() {
        whenever(aiService.meta()).thenReturn(AiMetaResponse(templates = emptyList(), providers = emptyList()))
        whenever(
            aiService.invoke(anyOrNull(), anyOrNull(), any(), anyOrNull(), anyOrNull(), any(), anyOrNull(), anyOrNull())
        ).thenReturn(stubResponse)
        whenever(
            aiService.invokeVision(anyOrNull(), anyOrNull(), any(), anyOrNull(), any(), any(), any(), anyOrNull(), anyOrNull())
        ).thenReturn(stubResponse)
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    @Test
    fun `meta without auth returns 401`() {
        given().`when`().get("/ai/meta").then().statusCode(401)
    }

    @Test
    fun `invoke without auth returns 401`() {
        given()
            .contentType("application/json")
            .body("""{"provider":"openai","prompt":"Hello"}""")
            .`when`().post("/ai/invoke")
            .then().statusCode(401)
    }

    // ── Meta ──────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "testUser", roles = ["user"])
    fun `meta returns 200`() {
        given().`when`().get("/ai/meta").then().statusCode(200)
    }

    @Test
    @TestSecurity(user = "testUser", roles = ["user"])
    fun `meta response contains X-Request-Id header`() {
        given().`when`().get("/ai/meta").then()
            .statusCode(200)
            .header("X-Request-Id", notNullValue())
    }

    // ── Invoke ────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "testUser", roles = ["user"])
    fun `invoke with raw prompt returns 200 and result`() {
        given()
            .contentType("application/json")
            .body("""{"provider":"openai","prompt":"Summarise my expenses"}""")
            .`when`().post("/ai/invoke")
            .then()
            .statusCode(200)
            .body("result", equalTo("stub response"))
            .body("provider", equalTo("openai"))
            .body("processing_time_ms", notNullValue())
    }

    @Test
    @TestSecurity(user = "testUser", roles = ["user"])
    fun `invoke with unknown provider returns 400`() {
        given()
            .contentType("application/json")
            .body("""{"provider":"unknown_llm","prompt":"Hello"}""")
            .`when`().post("/ai/invoke")
            .then()
            .statusCode(400)
            .body("error", notNullValue())
    }

    @Test
    @TestSecurity(user = "testUser", roles = ["user"])
    fun `invoke returns X-Request-Id header`() {
        given()
            .contentType("application/json")
            .body("""{"provider":"openai","prompt":"Hello"}""")
            .`when`().post("/ai/invoke")
            .then()
            .statusCode(200)
            .header("X-Request-Id", notNullValue())
    }

    // ── Security headers ──────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "testUser", roles = ["user"])
    fun `responses include security headers`() {
        given().`when`().get("/ai/meta").then()
            .statusCode(200)
            .header("X-Content-Type-Options", "nosniff")
            .header("X-Frame-Options", "DENY")
            .header("Referrer-Policy", "strict-origin-when-cross-origin")
    }

    // ── Cache-Control ─────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "testUser", roles = ["user"])
    fun `meta returns cache-control header`() {
        given().`when`().get("/ai/meta").then()
            .statusCode(200)
            .header("Cache-Control", org.hamcrest.Matchers.containsString("max-age=300"))
    }

    // ── Rate limit headers ────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "testUser", roles = ["user"])
    fun `invoke returns X-RateLimit headers on success`() {
        given()
            .contentType("application/json")
            .body("""{"provider":"openai","prompt":"Hello"}""")
            .`when`().post("/ai/invoke")
            .then()
            .statusCode(200)
            .header("X-RateLimit-Limit", notNullValue())
            .header("X-RateLimit-Remaining", notNullValue())
    }

    // ── Vision invoke ─────────────────────────────────────────────────────────

    @Test
    fun `vision invoke without auth returns 401`() {
        val imageFile = File.createTempFile("test-img", ".jpg").also { it.writeBytes(ByteArray(100) { 0xFF.toByte() }) }
        given()
            .multiPart("file", imageFile, "image/jpeg")
            .multiPart("provider", "openai")
            .multiPart("prompt", "What is in this image?")
            .`when`().post("/ai/invoke/vision")
            .then().statusCode(401)
        imageFile.delete()
    }

    @Test
    @TestSecurity(user = "testUser", roles = ["user"])
    fun `vision invoke returns 200 and result`() {
        val imageFile = File.createTempFile("test-img", ".jpg").also { it.writeBytes(ByteArray(100) { 0xFF.toByte() }) }
        given()
            .multiPart("file", imageFile, "image/jpeg")
            .multiPart("provider", "openai")
            .multiPart("prompt", "What is in this image?")
            .`when`().post("/ai/invoke/vision")
            .then()
            .statusCode(200)
            .body("result", equalTo("stub response"))
            .body("provider", equalTo("openai"))
        imageFile.delete()
    }

    @Test
    @TestSecurity(user = "testUser", roles = ["user"])
    fun `vision invoke with unknown provider returns 400`() {
        val imageFile = File.createTempFile("test-img", ".jpg").also { it.writeBytes(ByteArray(100) { 0xFF.toByte() }) }
        given()
            .multiPart("file", imageFile, "image/jpeg")
            .multiPart("provider", "unknown_llm")
            .multiPart("prompt", "What is this?")
            .`when`().post("/ai/invoke/vision")
            .then()
            .statusCode(400)
            .body("error", notNullValue())
        imageFile.delete()
    }

    @Test
    @TestSecurity(user = "testUser", roles = ["user"])
    fun `vision invoke returns X-Request-Id header`() {
        val imageFile = File.createTempFile("test-img", ".jpg").also { it.writeBytes(ByteArray(100) { 0xFF.toByte() }) }
        given()
            .multiPart("file", imageFile, "image/jpeg")
            .multiPart("provider", "openai")
            .multiPart("prompt", "Describe this.")
            .`when`().post("/ai/invoke/vision")
            .then()
            .statusCode(200)
            .header("X-Request-Id", notNullValue())
        imageFile.delete()
    }

    // ── Token usage ───────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "testUser", roles = ["user"])
    fun `invoke response includes token usage when provided`() {
        val responseWithTokens = AiInvokeResponse(
            result = "answer", provider = "openai", model = "gpt-4o-mini",
            processingTimeMs = 100, inputTokens = 50, outputTokens = 80,
        )
        whenever(
            aiService.invoke(anyOrNull(), anyOrNull(), any(), anyOrNull(), anyOrNull(), any(), anyOrNull(), anyOrNull())
        ).thenReturn(responseWithTokens)

        given()
            .contentType("application/json")
            .body("""{"provider":"openai","prompt":"Hello"}""")
            .`when`().post("/ai/invoke")
            .then()
            .statusCode(200)
            .body("input_tokens", equalTo(50))
            .body("output_tokens", equalTo(80))
    }
}
