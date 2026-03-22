package com.adars.aiwrap.provider.paddle

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.jboss.resteasy.reactive.RestForm
import java.io.File

/**
 * Typed REST client for the PaddleOCR Python/FastAPI sidecar.
 * URL is configured via quarkus.rest-client.paddle-ocr.url in application.properties.
 *
 * Uses java.io.File for multipart upload — the correct client-side type in Quarkus REST client.
 * (FileUpload is the server-side receiving type; File is the client-side sending type.)
 */
@RegisterRestClient(configKey = "paddle-ocr")
@Path("/")
interface PaddleOcrClient {

    @POST
    @Path("/ocr")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    fun processImage(@RestForm("file") file: File): PaddleOcrResult
}

data class PaddleOcrResult(
    val text: String,
    val lines: List<OcrLine>,
    val confidence: Double,
    val provider: String = "paddle"
)

data class OcrLine(
    val text: String,
    val confidence: Double,
    val bbox: List<List<Double>>
)
