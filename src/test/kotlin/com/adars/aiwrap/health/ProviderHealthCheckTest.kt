package com.adars.aiwrap.health

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test

/**
 * Verifies the custom ai-providers health check at /q/health/ready.
 * In the test profile all provider API keys are set to "test" (not "DISABLED"),
 * so the check should report UP with all providers enabled.
 */
@QuarkusTest
class ProviderHealthCheckTest {

    @Test
    fun `readiness endpoint is reachable`() {
        given().`when`().get("/q/health/ready").then().statusCode(200)
    }

    @Test
    fun `ai-providers check is present in readiness response`() {
        given().`when`().get("/q/health/ready").then()
            .statusCode(200)
            .body("checks.name", hasItem("ai-providers"))
    }

    @Test
    fun `ai-providers check reports UP when keys are configured`() {
        given().`when`().get("/q/health/ready").then()
            .statusCode(200)
            .body("checks.find{it.name=='ai-providers'}.status", equalTo("UP"))
    }

    @Test
    fun `ai-providers check reports all providers enabled in test profile`() {
        given().`when`().get("/q/health/ready").then()
            .statusCode(200)
            .body("checks.find{it.name=='ai-providers'}.data.openai", equalTo("enabled"))
            .body("checks.find{it.name=='ai-providers'}.data.gemini", equalTo("enabled"))
            .body("checks.find{it.name=='ai-providers'}.data.deepseek", equalTo("enabled"))
    }
}
