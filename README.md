# AI Wrap

A vendor-agnostic AI gateway. Any app that needs LLM or OCR functionality calls two HTTP endpoints — AI Wrap handles provider routing, prompt templating, security guards, rate limiting, reliability, and response normalisation. No AI SDK code lives in your client.

**Providers**: OpenAI · Google Gemini · DeepSeek · PaddleOCR (sidecar) · Ollama (optional, disabled by default)

**Stack**: Kotlin 2.3 · Quarkus 3.32 · Java 25 · LangChain4j 1.7 · SmallRye JWT / Fault Tolerance / Health / OpenAPI · Micrometer + Prometheus

---

## Table of Contents

1. [Architecture](#architecture)
2. [Features](#features)
3. [Prerequisites](#prerequisites)
4. [Environment Variables](#environment-variables)
5. [Local Development](#local-development)
6. [Building the Application](#building-the-application)
7. [PaddleOCR Sidecar](#paddleocr-sidecar)
8. [Running with Docker Compose](#running-with-docker-compose)
9. [API Reference](#api-reference)
10. [Security](#security)
11. [Rate Limiting](#rate-limiting)
12. [Reliability](#reliability)
13. [Observability](#observability)
14. [Prompt Templates](#prompt-templates)
15. [Provider Configuration Guide](#provider-configuration-guide)
16. [Enabling Ollama](#enabling-ollama)
17. [Connecting Your App](#connecting-your-app)
18. [CI / CD](#ci--cd)
19. [Project Structure](#project-structure)

---

## Architecture

```
Your App (any client)
        │  Authorization: Bearer <jwt>
        │  POST /ai/invoke          (text)
        │  POST /ai/invoke/vision   (image / PDF + text)
        ▼
  ai-wrap  (Kotlin + Quarkus 3.32, port 8090)
        │
        │  PromptGuard → ContentGuard → RateLimiter
        │
        ├── LangChain4j ──► OpenAI   (gpt-4o-mini, vision + text)
        │     @Bulkhead    ──► Gemini  (gemini-2.0-flash, vision + text)
        │     @CircuitBreaker ──► DeepSeek (deepseek-chat, text only)
        │     @Retry       ──► Ollama  (llava / llama3.2, optional)
        │
        └── REST client ──► paddle-ocr sidecar (Python/FastAPI, port 8091)
```

AI Wrap is **stateless** — no database. It:

- Routes requests to the right AI provider
- Loads and fills prompt templates from classpath `.txt` files
- Runs **PromptGuard** (injection detection) and **ContentGuard** (harmful-intent detection)
- Enforces per-user per-minute and per-day rate limits
- Applies fault tolerance on every provider call (bulkhead → circuit breaker → retry)
- Returns a normalised response including token usage

Adding new AI functionality requires only a new `.txt` template file — no Kotlin changes.

---

## Features

### Core
- **Three endpoints**: `GET /ai/meta` (discovery), `POST /ai/invoke` (text), `POST /ai/invoke/vision` (image/PDF)
- **Multi-turn chat**: pass a full `messages` array for conversation history
- **Prompt templates**: server-side `.txt` files with `{placeholder}` substitution and an optional system-prompt section
- **Per-request model overrides**: model, temperature, max_tokens, top_p, stop, frequency_penalty, presence_penalty, json_mode, timeout_seconds
- **Token usage**: `input_tokens` and `output_tokens` returned in every response

### Security
- **JWT authentication** (ES256) on all endpoints via SmallRye JWT — tokens issued by [auth-service](https://github.com/adarshraj/auth-service)
- **PromptGuard**: blocks prompt injection patterns (jailbreak, DAN mode, role injection, ChatML/LLaMA tokens, etc.)
- **ContentGuard**: blocks harmful-intent prompts (weapons, malware, violence, fraud, drug synthesis, etc.)
- **Max prompt length**: configurable character limit (default 200,000) on raw prompts and template variables
- **API key masking**: `api_key` values are never logged
- **OWASP security headers**: `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, `X-XSS-Protection`, `Permissions-Policy` on every response
- **Request body size limit**: configurable (default 50 MB) at the HTTP layer
- **File upload size limit**: configurable (default 20 MB) enforced in application code

### Rate Limiting
- **Per-user per-minute limit** (default 30 req/min)
- **Per-user per-day limit** (default 1,000 req/day)
- **Rate limit response headers**: `X-RateLimit-Limit`, `X-RateLimit-Remaining` on every invoke response
- **429 headers**: `Retry-After` (60 s for minute limit; seconds until midnight for daily limit)
- **503 on bulkhead rejection**: distinct from rate limiting

### Reliability
- **`@Bulkhead`**: max 10 concurrent calls per provider, queue of 5 (rejects excess with 503)
- **`@CircuitBreaker`**: opens after 5 requests with >50% failure rate, resets after 30 s
- **`@Retry`**: 2 retries with 500 ms delay and 200 ms jitter on transient errors
- **Per-request timeout**: `timeout_seconds` in `model_params` (OpenAI/DeepSeek only)
- **Graceful shutdown**: 30 s drain window for in-flight requests on SIGTERM

### Observability
- **Prometheus metrics** at `/q/metrics`: `ai.requests.total` (by provider/type/status) and `ai.request.duration`
- **Structured audit log**: one JSON line per invocation written to `logs/audit.log` (rotating, max 10 MB × 5 backups)
- **Request ID**: 8-hex-char ID generated per request, propagated through MDC and returned as `X-Request-Id` response header
- **Health endpoints**: `/q/health`, `/q/health/live`, `/q/health/ready` (custom readiness check reports per-provider status)
- **Swagger UI** at `/q/swagger-ui`

### Infrastructure
- **Maven wrapper** (`./mvnw`) — no Maven installation required
- **Dockerfile** (`src/main/docker/Dockerfile.jvm`) — non-root user, `UseContainerSupport`, `MaxRAMPercentage=75.0`, `HEALTHCHECK`
- **`.dockerignore`** — excludes `target/`, `.git/`, test sources for fast builds
- **GitHub Actions CI** — build and test on every push/PR

---

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Java (JDK) | 25 | Run / build the Quarkus app |
| Docker | 24+ | Container runtime |
| Docker Compose | v2 | Multi-service orchestration |
| Python | 3.11+ | PaddleOCR sidecar (local dev only, optional) |

> Maven is bundled via the Maven wrapper (`./mvnw`). Python is not needed if you run the sidecar via Docker.

---

## Environment Variables

Copy `.env.example` to `.env` and fill in the values you need.

```bash
cp .env.example .env
```

### Required

| Variable | Description |
|----------|-------------|
| `AUTH_SERVICE_URL` | Base URL of your [auth-service](https://github.com/adarshraj/auth-service) instance (e.g. `https://auth.example.com`). Used to fetch the ES256 public key from `/.well-known/jwks.json` and validate the token issuer. Defaults to `http://localhost:8703`. |

### AI Provider Keys (at least one required)

| Variable | Default model |
|----------|---------------|
| `OPENAI_API_KEY` | `gpt-4o-mini` |
| `GEMINI_API_KEY` | `gemini-2.0-flash` |
| `DEEPSEEK_API_KEY` | `deepseek-chat` |

If a key is not set it defaults to `DISABLED`. The server starts normally; requests to that provider fail at call time, not at startup.

### Model Overrides (optional)

| Variable | Description |
|----------|-------------|
| `OPENAI_MODEL` | Override default OpenAI model |
| `GEMINI_MODEL` | Override default Gemini text model |
| `GEMINI_VISION_MODEL` | Override default Gemini vision model |
| `DEEPSEEK_MODEL` | Override default DeepSeek model |

### Rate Limiting

| Variable | Default | Description |
|----------|---------|-------------|
| `AI_WRAP_RATE_LIMIT_RPM` | `30` | Max requests per user per minute |
| `AI_WRAP_RATE_LIMIT_RPD` | `1000` | Max requests per user per day |

### Request / Upload Limits

| Variable | Default | Description |
|----------|---------|-------------|
| `AI_WRAP_MAX_BODY_SIZE` | `50M` | Max HTTP request body (raise for large PDF imports) |
| `AI_WRAP_MAX_UPLOAD_BYTES` | `20971520` | Max file upload size in bytes (20 MB) |
| `AI_WRAP_MAX_PROMPT_CHARS` | `200000` | Max characters in a raw prompt or combined template variables |

### Server

| Variable | Default | Description |
|----------|---------|-------------|
| `AI_WRAP_ALLOWED_ORIGIN` | `http://localhost:5173` | CORS allowed origin |
| `LOG_DIR` | `logs` | Directory for `audit.log` |

### PaddleOCR (optional)

| Variable | Default | Description |
|----------|---------|-------------|
| `PADDLE_OCR_ENABLED` | `false` | Set `true` to enable the PaddleOCR sidecar |
| `PADDLE_OCR_URL` | `http://paddle-ocr:8091` | URL of the sidecar |

### Ollama (disabled by default)

| Variable | Default | Description |
|----------|---------|-------------|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server URL |
| `OLLAMA_VISION_MODEL` | `llava` | Vision model name |
| `OLLAMA_TEXT_MODEL` | `llama3.2` | Text model name |

---

## Local Development

### 1. Set environment variables

```bash
export AUTH_SERVICE_URL=http://localhost:8703  # auth-service must be running
export GEMINI_API_KEY=your-gemini-key          # or OPENAI_API_KEY / DEEPSEEK_API_KEY
```

### 2. Start in dev mode

```bash
./mvnw quarkus:dev
```

Quarkus starts on `http://localhost:8090` with live reload. Changes to `.kt`, `.properties`, or `.txt` template files trigger an automatic rebuild.

### 3. Verify startup

```bash
curl http://localhost:8090/q/health
# → { "status": "UP" }
```

### 4. Open Swagger UI

```
http://localhost:8090/q/swagger-ui
```

### 5. Discover available templates and providers

```bash
curl -H "Authorization: Bearer <jwt>" http://localhost:8090/ai/meta
```

### 6. Test a text request

```bash
curl -s -X POST http://localhost:8090/ai/invoke \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "gemini",
    "template": "insights-qa",
    "variables": {
      "question": "What was my biggest expense?",
      "context": "Food: ₹5000, Transport: ₹2000, Entertainment: ₹1000."
    }
  }'
```

Obtain a JWT from auth-service: `POST http://localhost:8703/auth/login` with your credentials.

---

## Building the Application

### Run tests

```bash
./mvnw test
```

### Build a JAR (production)

```bash
./mvnw package -DskipTests
```

Output: `target/quarkus-app/quarkus-run.jar`

### Run the JAR directly

```bash
AUTH_SERVICE_URL=http://localhost:8703 \
GEMINI_API_KEY=your-key \
java -jar target/quarkus-app/quarkus-run.jar
```

### Build a Docker image

```bash
./mvnw package -DskipTests
docker build -f src/main/docker/Dockerfile.jvm -t ai-wrap:latest .
```

The image uses Eclipse Temurin 25 JRE, runs as a non-root user, and includes a `HEALTHCHECK` via `/q/health/live`.

---

## PaddleOCR Sidecar

The sidecar is a FastAPI service that runs PaddleOCR locally. Only needed when `PADDLE_OCR_ENABLED=true`.

**Supported input formats**: JPEG, PNG, WebP, BMP, HEIC/HEIF, PDF (max 20 MB per file)

For PDF files each page is rendered at 150 DPI and OCR'd; results are concatenated. HEIC is converted transparently via `pillow-heif`.

### Running locally (without Docker)

```bash
cd paddle-ocr-sidecar
python -m venv .venv
source .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r requirements.txt  # downloads PaddleOCR models (~500 MB on first run)
uvicorn main:app --host 0.0.0.0 --port 8091
```

Then tell AI Wrap where to find it:

```bash
export PADDLE_OCR_URL=http://localhost:8091
export PADDLE_OCR_ENABLED=true
```

### Sidecar API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Returns `{ "status": "ok", "provider": "paddle" }` |
| `/ocr` | POST | Multipart `file` field — returns OCR text and bounding boxes |

---

## Running with Docker Compose

### 1. Create the shared external network

```bash
docker network create ai-wrap-network
```

### 2. Configure environment

```bash
cp .env.example .env
# Fill in AUTH_SERVICE_URL and at least one AI provider key
```

### 3. Build and start

Without OCR (default):
```bash
docker compose up --build -d
```

With PaddleOCR sidecar:
```bash
docker compose --profile ocr up --build -d
```

### 4. Verify

```bash
curl http://localhost:8090/q/health
```

### 5. View logs

```bash
docker compose logs -f ai-wrap
docker compose --profile ocr logs -f paddle-ocr
```

### Docker Compose services

| Service | Port | Description |
|---------|------|-------------|
| `ai-wrap` | `8090` (host) | Quarkus application |
| `paddle-ocr` | `8091` (internal only) | Python OCR sidecar |

**Networks:**
- `wrap-internal` — private bridge between `ai-wrap` and `paddle-ocr`
- `ai-wrap-network` — external network your calling apps join to reach AI Wrap

---

## API Reference

All endpoints require `Authorization: Bearer <jwt>` (ES256, issued by [auth-service](https://github.com/adarshraj/auth-service)).

Interactive documentation is available at `/q/swagger-ui`.

---

### `GET /ai/meta`

Discovery endpoint — returns all available templates and providers.

Response is cached for 5 minutes (`Cache-Control: public, max-age=300`).

**Response:**
```json
{
  "templates": [
    {
      "name": "insights-qa",
      "variables": ["context", "question"],
      "has_system_prompt": true
    },
    {
      "name": "ocr-receipt-structured",
      "variables": [],
      "has_system_prompt": false
    }
  ],
  "providers": [
    { "id": "openai",   "supports_text": true,  "supports_vision": true,  "default_model": "gpt-4o-mini",      "enabled": true  },
    { "id": "gemini",   "supports_text": true,  "supports_vision": true,  "default_model": "gemini-2.0-flash", "enabled": true  },
    { "id": "deepseek", "supports_text": true,  "supports_vision": false, "default_model": "deepseek-chat",    "enabled": false },
    { "id": "ollama",   "supports_text": true,  "supports_vision": true,  "default_model": null,               "enabled": false },
    { "id": "paddle",   "supports_text": false, "supports_vision": true,  "default_model": null,               "enabled": false }
  ]
}
```

---

### `POST /ai/invoke`

Text-only AI request. Supply one of:

- **`prompt`** — raw text sent directly to the LLM
- **`template` + `variables`** — server-side template filled with your values
- **`messages`** — full multi-turn conversation history (takes precedence over prompt/template)

**Request body:**
```json
{
  "provider": "gemini",
  "prompt": "Summarise this text: ...",
  "template": "insights-qa",
  "variables": { "question": "...", "context": "..." },
  "system_prompt": "You are a concise tax advisor.",
  "messages": [
    { "role": "system",    "content": "You are a helpful assistant." },
    { "role": "user",      "content": "What is compound interest?" },
    { "role": "assistant", "content": "Compound interest is..." },
    { "role": "user",      "content": "Give me an example with ₹10,000." }
  ],
  "model_params": {
    "model": "gpt-4o",
    "temperature": 0.3,
    "max_tokens": 2000,
    "top_p": 0.9,
    "stop": ["###"],
    "frequency_penalty": 0.2,
    "presence_penalty": 0.1,
    "json_mode": true,
    "timeout_seconds": 60
  },
  "api_key": "sk-..."
}
```

All fields except `provider` are optional. Priority: `messages` > `prompt` > `template`.

**Response:**
```json
{
  "result": "Your biggest expense was Shopping at ₹10,000 (40% of total).",
  "provider": "gemini",
  "model": "gpt-4o",
  "processing_time_ms": 912,
  "input_tokens": 312,
  "output_tokens": 48
}
```

`model`, `input_tokens`, and `output_tokens` are omitted when not available.

**Response headers:**

| Header | Description |
|--------|-------------|
| `X-Request-Id` | 8-char hex ID for log correlation |
| `X-RateLimit-Limit` | Configured per-minute limit |
| `X-RateLimit-Remaining` | Requests remaining in the current minute |

**Error responses:**

| Status | Cause |
|--------|-------|
| `400` | Unknown provider, missing prompt/template, injection detected, harmful content, prompt too long |
| `401` | Missing or invalid JWT |
| `429` | Per-minute or per-day rate limit exceeded (`Retry-After` header included) |
| `503` | Provider bulkhead full — too many concurrent requests |
| `500` | Provider error |

---

### `POST /ai/invoke/vision`

Vision AI request (image or PDF + text prompt). Always single-turn.

**Request:** `multipart/form-data`

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `file` | binary | Yes | Image (JPEG/PNG/WebP/BMP/HEIC) or PDF, max 20 MB |
| `provider` | string | Yes | `openai`, `gemini`, or `paddle` |
| `prompt` | string | No* | Raw prompt text |
| `template` | string | No* | Template name (e.g. `ocr-receipt-structured`) |
| `variables` | JSON string | No | Template variable overrides |
| `system_prompt` | string | No | System persona override |
| `model_params` | JSON string | No | Same fields as text invoke |
| `api_key` | string | No | Per-request provider API key |

\* Either `prompt` or `template` is required for non-PADDLE providers. For `paddle`, both are ignored — raw OCR text is returned.

**Response:** same shape as `/ai/invoke`.

---

### `ModelParams` fields

| Field | Type | Providers | Description |
|-------|------|-----------|-------------|
| `model` | string | All | Provider-specific model ID (e.g. `gpt-4o`, `gemini-2.5-flash`, `deepseek-reasoner`) |
| `temperature` | number | All | 0.0–2.0, lower = more deterministic |
| `max_tokens` | integer | All | Maximum response length in tokens |
| `top_p` | number | All | Nucleus sampling probability mass |
| `stop` | string[] | All | Stop sequences |
| `frequency_penalty` | number | OpenAI, DeepSeek | Penalise frequent tokens |
| `presence_penalty` | number | OpenAI, DeepSeek | Penalise any repeated tokens |
| `json_mode` | boolean | All | Force valid JSON output |
| `timeout_seconds` | integer | OpenAI, DeepSeek | Per-request timeout override (Gemini uses server-configured timeout) |

All fields are optional. Omitted fields use the defaults from `application.properties`.

---

### Health Endpoints

| Path | Description |
|------|-------------|
| `GET /q/health` | Combined liveness + readiness |
| `GET /q/health/live` | Liveness probe |
| `GET /q/health/ready` | Readiness — reports per-provider enabled/disabled status |

Readiness reports DOWN only if **no** provider is enabled. Individual provider status is included in the response detail.

---

### Observability Endpoints

| Path | Description |
|------|-------------|
| `GET /q/metrics` | Prometheus metrics |
| `GET /q/swagger-ui` | Interactive API documentation |

---

## Security

### Authentication

All endpoints require a valid JWT in the `Authorization: Bearer <token>` header. Tokens must be ES256-signed by [auth-service](https://github.com/adarshraj/auth-service). AI Wrap fetches the public key automatically from `AUTH_SERVICE_URL/.well-known/jwks.json` and validates the signature and issuer claim. No shared secret is needed.

### PromptGuard

Runs on every user-supplied value before it reaches the LLM. Blocks prompt injection patterns:

- `"ignore previous instructions"`, `"disregard previous"`
- `"you are now"`, `"act as"`, `"pretend to be"`, `"roleplay as"`
- Jailbreak / DAN-mode phrases
- XML/YAML instruction injection (`<INST>`, `[INST]`)
- LLaMA/ChatML special tokens (`<|im_start|>`, `<|system|>`)

Returns `400 Bad Request`. The specific pattern matched is logged server-side; the client receives a generic message.

### ContentGuard

Runs on the fully-assembled prompt after variable substitution. Blocks requests that appear to be asking the service to assist with:

- Weapons or explosives
- Malware creation or unauthorised system access
- Data destruction (DROP TABLE, etc.)
- Violence against persons
- Drug synthesis
- Identity theft or financial fraud
- Child exploitation

Returns `400 Bad Request`.

### Security Response Headers

Applied to every HTTP response:

| Header | Value |
|--------|-------|
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `X-XSS-Protection` | `0` |
| `Permissions-Policy` | `camera=(), microphone=(), geolocation=()` |

### API Key Masking

`api_key` values are redacted in all log output (`[REDACTED]`). They are never written to files or audit logs.

### Prompt Length Limit

Raw prompts and combined template variable values are capped at `AI_WRAP_MAX_PROMPT_CHARS` (default 200,000 characters, ≈ 50,000 tokens). Requests exceeding this limit receive `400 Bad Request`.

---

## Rate Limiting

Rate limiting is applied per authenticated user (JWT subject) before the request reaches the AI provider.

### Limits

| Limit | Default | Override |
|-------|---------|----------|
| Per minute | 30 requests | `AI_WRAP_RATE_LIMIT_RPM` |
| Per day | 1,000 requests | `AI_WRAP_RATE_LIMIT_RPD` |

### Response Headers (on invoke endpoints)

| Header | Description |
|--------|-------------|
| `X-RateLimit-Limit` | Configured per-minute limit |
| `X-RateLimit-Remaining` | Requests remaining in the current minute |
| `Retry-After` | Seconds to wait (only on 429 responses) |

### Behaviour on limit exceeded

Returns `429 Too Many Requests` with a JSON error body and `Retry-After` header:
- **Minute limit**: `Retry-After: 60`
- **Daily limit**: `Retry-After: <seconds until midnight>`

---

## Reliability

Every provider service method is protected by three stacked fault tolerance policies applied in this order:

```
Request → Bulkhead → CircuitBreaker → Retry → Provider call
```

### Bulkhead

- Max **10 concurrent** calls per provider
- Waiting queue of **5** requests
- Queue full → `503 Service Unavailable` immediately (does not count against rate limit)

### Circuit Breaker

- Opens after **5 requests** with a failure ratio > **50%**
- Stays open for **30 seconds** before attempting a probe request
- Open circuit → request fails fast with `500`

### Retry

- **2 retries** on any exception
- **500 ms** base delay with **±200 ms** jitter
- Only retries if circuit is closed

### Per-request Timeout

Pass `timeout_seconds` in `model_params` to override the default provider timeout for a single call (OpenAI and DeepSeek only). Gemini uses a fixed startup-time timeout.

### Graceful Shutdown

On SIGTERM, the server waits up to **30 seconds** for in-flight requests to complete before exiting. This prevents mid-response kills during rolling deployments.

---

## Observability

### Prometheus Metrics

Available at `GET /q/metrics`. Key metrics:

| Metric | Labels | Description |
|--------|--------|-------------|
| `ai_requests_total` | `provider`, `type` (text/vision), `status` (success/failure) | Total invocation count |
| `ai_request_duration_seconds` | `provider`, `type` | Invocation latency histogram |

### Audit Log

Every AI invocation writes a structured JSON line to `logs/audit.log`:

```json
{
  "userId": "alice",
  "action": "text",
  "provider": "openai",
  "template": "insights-qa",
  "model": "gpt-4o-mini",
  "processingTimeMs": 912,
  "success": true,
  "errorType": "",
  "inputTokens": 312,
  "outputTokens": 48
}
```

The log rotates at 10 MB with 5 backups. Prompt content is intentionally excluded.

### Request Tracing

Each request gets an 8-hex-char `requestId` generated at entry:
- Injected into SLF4J MDC — appears in every log line for that request
- Returned to the caller as `X-Request-Id` response header
- Exposed as a CORS header so browser clients can read it

### Health Check

`GET /q/health/ready` runs a custom `ProviderHealthCheck` that reports the enabled/disabled status of each provider. The service reports `UP` if at least one provider is enabled.

---

## Prompt Templates

Templates live in `src/main/resources/prompts/` as plain `.txt` files. No code changes needed to add a new feature — just add a file.

### Format

```
You are a helpful assistant specialised in {domain}.
Answer concisely.
---
User data:
{context}

Question: {question}
```

- Content **before** `---` becomes the **system prompt** (optional).
- Content **after** `---` (or the whole file if no `---`) becomes the **user prompt**.
- `{placeholder}` names are filled from the `variables` map in the request.
- The `system_prompt` field in a request overrides the template's system section.
- Templates are cached in memory on first load.

### Bundled templates

| Template name | Variables | Description |
|---------------|-----------|-------------|
| `insights-qa` | `context`, `question` | Answer a natural-language question about spending data |
| `ocr-receipt-structured` | _(none)_ | Extract structured JSON from a receipt/invoice image |
| `import-transaction` | varies | Parse and classify bank transaction data |
| `import-investment` | varies | Parse and classify investment transaction data |

### Adding a template

1. Create `src/main/resources/prompts/my-feature.txt`
2. Restart the app (or hot-reload in dev mode)
3. Call `/ai/invoke` with `"template": "my-feature"`

---

## Provider Configuration Guide

### Choosing a provider

| Value | Vision | Text | Notes |
|-------|--------|------|-------|
| `openai` | Yes | Yes | Best quality; paid |
| `gemini` | Yes | Yes | Good quality; free tier available |
| `deepseek` | No | Yes | Text-only; cost-effective for reasoning |
| `paddle` | Yes (OCR only) | No | Runs locally; no LLM reasoning |
| `ollama` | Yes (llava) | Yes | Local, free; requires setup |

### Per-request API key

Supply `api_key` in the request body to override the server-configured key for that call. A dynamic client is built using the provided key and the server's base URL.

Supported for **OpenAI** and **DeepSeek**. For **Gemini**, the server-configured key is always used (a warning is logged if `api_key` is supplied).

### Per-request model override

```json
{ "provider": "openai", "model_params": { "model": "gpt-4o" } }
```

### Global default model override

```bash
OPENAI_MODEL=gpt-4o
GEMINI_MODEL=gemini-2.5-flash
DEEPSEEK_MODEL=deepseek-reasoner
```

---

## Enabling Ollama

Ollama support is disabled by default to avoid Quarkus Dev Services auto-downloading large model files.

### 1. Start an Ollama server

```bash
ollama pull llava      # vision model (~4 GB)
ollama pull llama3.2   # text model (~2 GB)
ollama serve           # starts on http://localhost:11434
```

### 2. Uncomment the dependency in `pom.xml`

```xml
<dependency>
  <groupId>io.quarkiverse.langchain4j</groupId>
  <artifactId>quarkus-langchain4j-ollama</artifactId>
</dependency>
```

### 3. Uncomment the Ollama properties in `application.properties`

```properties
quarkus.langchain4j.ollama.ollama-vision.base-url=${OLLAMA_BASE_URL:http://localhost:11434}
quarkus.langchain4j.ollama.ollama-vision.chat-model.model-id=${OLLAMA_VISION_MODEL:llava}
quarkus.langchain4j.ollama.ollama-text.base-url=${OLLAMA_BASE_URL:http://localhost:11434}
quarkus.langchain4j.ollama.ollama-text.chat-model.model-id=${OLLAMA_TEXT_MODEL:llama3.2}
```

### 4. Add Ollama cases back to `ProviderResolver.kt`

In `textFunction()`:
```kotlin
AiProvider.OLLAMA -> { msgs -> ollamaText.chat(msgs, params, apiKey) }
```

In `visionFunction()`:
```kotlin
AiProvider.OLLAMA -> { prompt, b64, mime -> ollamaOcr.invokeVision(prompt, b64, mime, systemPrompt, params) }
```

### 5. Rebuild

```bash
./mvnw clean quarkus:dev
```

> **Warning:** With the Ollama dependency on the classpath, Quarkus Dev Services will try to pull the model if `OLLAMA_BASE_URL` does not point to a running instance. Always have Ollama running before starting in dev mode.

---

## Connecting Your App

AI Wrap is designed to be called by any application. The only shared configuration is `AUTH_SERVICE_URL` — your app obtains JWTs from auth-service and passes them to AI Wrap.

### Required environment variable in your app

```env
AI_WRAP_URL=http://ai-wrap:8090    # Docker (use service name)
AI_WRAP_URL=http://localhost:8090  # local dev
```

### Docker network setup

Your app's `docker-compose.yml` must join the same external network:

```yaml
networks:
  ai-wrap-network:
    external: true
```

### Minimal request example (JavaScript / fetch)

```js
const res = await fetch(`${AI_WRAP_URL}/ai/invoke`, {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${jwt}`,
    'Content-Type': 'application/json',
  },
  body: JSON.stringify({
    provider: 'gemini',
    template: 'insights-qa',
    variables: { question, context },
  }),
});
const { result, input_tokens, output_tokens } = await res.json();
```

---

## CI / CD

### GitHub Actions

A CI workflow runs on every push and pull request to `main`/`master`:

```
.github/workflows/ci.yml
```

Steps:
1. Check out code
2. Set up Java 25 (Eclipse Temurin)
3. Run `./mvnw clean verify` (build + all tests)
4. Upload Surefire test reports as an artifact on failure

### Running tests locally

```bash
./mvnw test
```

Test classes:

| Class | Type | Tests |
|-------|------|-------|
| `AiResourceTest` | QuarkusTest (integration) | Auth, invoke, headers, token usage |
| `ProviderHealthCheckTest` | QuarkusTest (integration) | Health endpoint scenarios |
| `RateLimiterTest` | Unit | Minute limit, daily limit, remaining count, exception metadata |
| `AuditServiceTest` | Unit | JSON output, special chars, token fields |
| `ContentGuardTest` | Unit | 17 harmful-content patterns |
| `PromptGuardTest` | Unit | 17 injection patterns |

---

## Project Structure

```
ai-wrap/
├── mvnw / mvnw.cmd                     # Maven wrapper — no Maven installation required
├── pom.xml                             # Build (Quarkus 3.32, Java 25, Kotlin 2.3)
├── docker-compose.yml                  # ai-wrap + PaddleOCR sidecar
├── .env.example                        # Template for environment variables
├── .dockerignore                       # Excludes target/, .git/, test sources
├── .github/
│   └── workflows/ci.yml               # GitHub Actions — build + test on push/PR
│
├── src/main/docker/
│   └── Dockerfile.jvm                 # Non-root JRE-25 image with HEALTHCHECK
│
├── paddle-ocr-sidecar/
│   ├── main.py                        # FastAPI app — POST /ocr, GET /health
│   ├── requirements.txt               # paddleocr, fastapi, uvicorn, pillow, pymupdf, pillow-heif
│   └── Dockerfile                     # python:3.11-slim, pre-downloads OCR models
│
└── src/main/kotlin/com/adars/aiwrap/
    ├── Application.kt                 # @QuarkusMain entry point
    │
    ├── filter/
    │   ├── RequestLoggingFilter.kt    # Generates requestId, injects into MDC, logs → METHOD /path
    │   ├── ResponseLoggingFilter.kt   # Logs ← STATUS METHOD /path Xms, sets X-Request-Id header
    │   └── SecurityHeadersFilter.kt  # OWASP security headers on every response
    │
    ├── health/
    │   └── ProviderHealthCheck.kt     # @Readiness — reports per-provider enabled status
    │
    ├── model/
    │   └── AiModels.kt               # AiInvokeRequest, AiInvokeResponse, AiMetaResponse,
    │                                 # TemplateInfo, ProviderInfo, ModelParams, ChatMessage,
    │                                 # ProviderResult (text + token usage from provider)
    ├── resource/
    │   └── AiResource.kt             # GET /ai/meta
    │                                 # POST /ai/invoke
    │                                 # POST /ai/invoke/vision
    ├── service/
    │   ├── AiService.kt              # Template loading, prompt resolution, provider dispatch
    │   ├── AuditService.kt           # Structured JSON audit log (logs/audit.log)
    │   ├── RateLimiter.kt            # Per-user sliding-minute + daily quota
    │   ├── PromptGuard.kt            # Injection detection on user-supplied values
    │   └── ContentGuard.kt           # Harmful-intent detection on assembled prompts
    │
    └── provider/
        ├── AiProvider.kt             # Enum: OPENAI, GEMINI, DEEPSEEK, OLLAMA, PADDLE
        ├── ProviderResolver.kt       # Routes to correct service bean
        ├── openai/
        │   ├── OpenAiTextService.kt  # @Bulkhead @CircuitBreaker @Retry; dynamic build for api_key
        │   └── OpenAiOcrService.kt  # Same; vision via gpt-4o-mini
        ├── gemini/
        │   ├── GeminiTextService.kt  # @Bulkhead @CircuitBreaker @Retry
        │   └── GeminiOcrService.kt
        ├── deepseek/
        │   └── DeepSeekTextService.kt  # OpenAI-compatible; dynamic build for api_key
        └── paddle/
            └── PaddleOcrClient.kt   # MicroProfile REST client for the OCR sidecar

src/main/resources/
    ├── application.properties
    └── prompts/
        ├── insights-qa.txt              # Q&A over spending data
        ├── ocr-receipt-structured.txt   # Receipt/invoice → structured JSON
        ├── import-transaction.txt       # Bank transaction parsing
        └── import-investment.txt        # Investment transaction parsing
```
