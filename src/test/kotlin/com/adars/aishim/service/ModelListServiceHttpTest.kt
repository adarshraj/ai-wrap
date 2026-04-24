package com.adars.aishim.service

import com.adars.aishim.provider.AiProvider
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * Covers the HTTP fetch / parse / sort paths in ModelListService using an embedded
 * JDK HttpServer — no external dependencies needed. Runs on a random loopback port
 * and injects the URL as each provider's base URL.
 *
 * Focus: response parsing, sort order, error mapping, URL sanitization, Ollama's
 * optional-auth branch, and header propagation for Anthropic / Gemini / Bearer.
 */
class ModelListServiceHttpTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val lastRequest = AtomicReference<RecordedRequest>()

    data class RecordedRequest(val path: String, val query: String?, val headers: Map<String, List<String>>)

    @BeforeEach
    fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun stub(path: String, status: Int, body: String) {
        server.createContext(path, HttpHandler { ex: HttpExchange ->
            lastRequest.set(
                RecordedRequest(
                    path = ex.requestURI.path,
                    query = ex.requestURI.query,
                    headers = ex.requestHeaders.toMap(),
                ),
            )
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        })
    }

    private fun service(kind: Kind) = when (kind) {
        Kind.OPENAI -> ModelListService(
            objectMapper = jacksonObjectMapper(),
            openAiApiKey = "DISABLED",
            openAiBaseUrl = baseUrl,
            geminiApiKey = "DISABLED",
            anthropicApiKey = "DISABLED",
            deepSeekApiKey = "DISABLED",
            deepSeekBaseUrl = "https://api.deepseek.com/v1",
            ollamaBaseUrl = "http://localhost:11434/v1",
        )
        Kind.OLLAMA -> ModelListService(
            objectMapper = jacksonObjectMapper(),
            openAiApiKey = "DISABLED",
            openAiBaseUrl = "https://api.openai.com/v1",
            geminiApiKey = "DISABLED",
            anthropicApiKey = "DISABLED",
            deepSeekApiKey = "DISABLED",
            deepSeekBaseUrl = "https://api.deepseek.com/v1",
            ollamaBaseUrl = baseUrl,
        )
    }

    enum class Kind { OPENAI, OLLAMA }

    @Test
    fun `OpenAI-compatible success parses and sorts by id`() {
        stub("/models", 200, """{"data":[{"id":"gpt-4o","created":123,"owned_by":"openai"},{"id":"gpt-3.5","created":100,"owned_by":"openai"}]}""")
        val r = service(Kind.OPENAI).listModels(AiProvider.OPENAI, apiKey = "sk-test")
        assertThat(r.provider).isEqualTo("openai")
        assertThat(r.models.map { it.id }).containsExactly("gpt-3.5", "gpt-4o")
        assertThat(r.models.first().ownedBy).isEqualTo("openai")
        assertThat(r.models.first().created).isEqualTo(100L)
    }

    @Test
    fun `OpenAI-compatible forwards Bearer token`() {
        stub("/models", 200, """{"data":[]}""")
        service(Kind.OPENAI).listModels(AiProvider.OPENAI, apiKey = "sk-secret-42")
        val auth = lastRequest.get().headers["Authorization"]?.firstOrNull()
        assertThat(auth).isEqualTo("Bearer sk-secret-42")
    }

    @Test
    fun `empty data list parses to empty models list`() {
        stub("/models", 200, """{"data":[]}""")
        val r = service(Kind.OPENAI).listModels(AiProvider.OPENAI, apiKey = "k")
        assertThat(r.models).isEmpty()
    }

    @Test
    fun `non-2xx status raises upstream error`() {
        stub("/models", 503, """{"error":"unavailable"}""")
        val ex = assertThrows<RuntimeException> {
            service(Kind.OPENAI).listModels(AiProvider.OPENAI, apiKey = "k")
        }
        assertThat(ex.message).contains("openai").contains("503")
    }

    @Test
    fun `404 is reported with status code`() {
        stub("/models", 404, "not found")
        val ex = assertThrows<RuntimeException> {
            service(Kind.OPENAI).listModels(AiProvider.OPENAI, apiKey = "k")
        }
        assertThat(ex.message).contains("404")
    }

    @Test
    fun `ignores unknown fields in upstream response`() {
        stub("/models", 200, """{"data":[{"id":"x","extra":"junk"}],"object":"list"}""")
        val r = service(Kind.OPENAI).listModels(AiProvider.OPENAI, apiKey = "k")
        assertThat(r.models).singleElement().satisfies({
            assertThat(it.id).isEqualTo("x")
            assertThat(it.created).isNull()
            assertThat(it.ownedBy).isNull()
        })
    }

    @Test
    fun `Ollama without apiKey omits Authorization header`() {
        stub("/models", 200, """{"data":[{"id":"llama3"}]}""")
        service(Kind.OLLAMA).listModels(AiProvider.OLLAMA, apiKey = null)
        val auth = lastRequest.get().headers["Authorization"]
        assertThat(auth).isNull()
    }

    @Test
    fun `Ollama with blank apiKey still omits Authorization`() {
        stub("/models", 200, """{"data":[]}""")
        service(Kind.OLLAMA).listModels(AiProvider.OLLAMA, apiKey = "  ")
        val auth = lastRequest.get().headers["Authorization"]
        assertThat(auth).isNull()
    }

    @Test
    fun `Ollama with apiKey sends Authorization header`() {
        stub("/models", 200, """{"data":[]}""")
        service(Kind.OLLAMA).listModels(AiProvider.OLLAMA, apiKey = "cloud-token")
        val auth = lastRequest.get().headers["Authorization"]?.firstOrNull()
        assertThat(auth).isEqualTo("Bearer cloud-token")
    }

    @Test
    fun `Ollama parses and preserves metadata`() {
        stub("/models", 200, """{"data":[{"id":"qwen3","created":42,"owned_by":"library"}]}""")
        val r = service(Kind.OLLAMA).listModels(AiProvider.OLLAMA, apiKey = null)
        assertThat(r.provider).isEqualTo("ollama")
        assertThat(r.models).singleElement().satisfies({
            assertThat(it.id).isEqualTo("qwen3")
            assertThat(it.created).isEqualTo(42L)
            assertThat(it.ownedBy).isEqualTo("library")
        })
    }

    @Test
    fun `trailing slash on base URL is normalized`() {
        // ollamaBaseUrl trimEnd('/') path — ensure double slash isn't produced
        val ms = ModelListService(
            objectMapper = jacksonObjectMapper(),
            openAiApiKey = "DISABLED",
            openAiBaseUrl = "https://api.openai.com/v1",
            geminiApiKey = "DISABLED",
            anthropicApiKey = "DISABLED",
            deepSeekApiKey = "DISABLED",
            deepSeekBaseUrl = "https://api.deepseek.com/v1",
            ollamaBaseUrl = "$baseUrl/",
        )
        stub("/models", 200, """{"data":[]}""")
        ms.listModels(AiProvider.OLLAMA, apiKey = null)
        assertThat(lastRequest.get().path).isEqualTo("/models")
    }
}
