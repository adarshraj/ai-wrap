# AI Shim

A vendor-agnostic AI gateway. Any app that needs LLM, vision, or image-generation functionality calls a handful of HTTP endpoints — AI Shim handles provider routing, prompt templating, security guards, rate limiting, reliability, and response normalisation. No AI SDK code lives in your client.

**Modalities**: text (chat) · vision (image → text) · image generation (text → image)

**Providers**: OpenAI · Google Gemini · DeepSeek · Anthropic · Azure OpenAI · Groq · OpenRouter · Mistral · Cerebras · xAI · Cohere · Ollama (optional, disabled by default)

**Stack**: Kotlin 2.3 · Quarkus 3.32 · Java 25 · LangChain4j 1.7 · SmallRye JWT / Fault Tolerance / Health / OpenAPI · Micrometer + Prometheus · OpenTelemetry (OTLP) · Redis (optional, for shared rate-limit state)

---

## Table of Contents

1. [Architecture](#architecture)
2. [Features](#features)
3. [Prerequisites](#prerequisites)
4. [Environment Variables](#environment-variables)
5. [Local Development](#local-development)
6. [Building the Application](#building-the-application)
7. [Running with Docker Compose](#running-with-docker-compose)
8. [Production Deployment (Multi-Client)](#production-deployment-multi-client)
9. [API Reference](#api-reference)
10. [Image Generation](#image-generation)
11. [Security](#security)
12. [Rate Limiting](#rate-limiting)
13. [Reliability](#reliability)
14. [Observability](#observability)
15. [Prompt Templates](#prompt-templates)
16. [Provider Configuration Guide](#provider-configuration-guide)
17. [Using Ollama](#using-ollama)
18. [Connecting Your App](#connecting-your-app)
19. [CI / CD](#ci--cd)
20. [Project Structure](#project-structure)

---

## Architecture

```
Your Apps (any clients — browser, Express, another service)
        │  Authorization: Bearer <jwt>
        │  GET  /ai/templates               (list prompt templates)
        │  GET  /ai/providers               (list providers + capabilities)
        │  GET  /ai/providers/:id/models    (list models for a provider)
        │  POST /ai                         (text + vision — multipart)
        │  POST /ai/image                   (image generation — multipart)
        ▼
  ai-shim  (Kotlin + Quarkus 3.32, port 8090)
        │
        │  PromptGuard → ContentGuard → RateLimiter (memory | redis)
        │
        ├── Text / Vision   ── LangChain4j ──► OpenAI / Gemini / Anthropic / Azure / …
        │     @Bulkhead  @CircuitBreaker  @Retry
        │
        └── Image Gen       ── REST ──────────► Gemini 2.5 Flash Image
              @Bulkhead  @CircuitBreaker  @Retry    (OpenAI gpt-image-1 wiring pending)
```

For OCR, see [paddle-ocr-wrap](https://github.com/adarshraj/paddle-ocr-wrap) — a
standalone HTTP service. Consumers that need OCR + LLM call both services directly.

AI Shim is **stateless by default** — no database. Optional Redis is used only for sharing
rate-limit counters across replicas. It:

- Routes requests to the right AI provider across three modalities (text, vision, image gen)
- Loads and fills prompt templates from classpath `.txt` files (works for any modality)
- Runs **PromptGuard** (injection detection) and **ContentGuard** (harmful-intent detection)
- Enforces per-user per-minute and per-day rate limits, with a pluggable storage backend
- Applies fault tolerance on every provider call (bulkhead → circuit breaker → retry)
- Returns a normalised response including token usage
- Emits Prometheus metrics, structured logs, and OpenTelemetry traces

Adding new AI functionality requires only a new `.txt` template file — no Kotlin changes.
Adding a new image-gen provider requires one class implementing `ImageGenService`.

---

## Features

### Core
- **Five endpoints**: `GET /ai/templates`, `GET /ai/providers`, `GET /ai/providers/{provider}/models`, `POST /ai` (text + vision), `POST /ai/image` (image generation)
- **Three modalities**: text, vision (image → text), image generation (text → image) — all behind one gateway, one auth scheme, one rate limiter
- **Multi-turn chat**: pass a full `messages` array for conversation history
- **Prompt templates**: server-side `.txt` files with `{placeholder}` substitution and an optional system-prompt section — work for all modalities
- **Per-request model overrides**: model, temperature, max_tokens, top_p, stop, frequency_penalty, presence_penalty, json_mode, timeout_seconds
- **Image-gen params**: size, count, quality, style, response_format, seed, negative_prompt (provider-specific fields surface as `warnings` when ignored)
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
- **Pluggable storage backend**: `memory` (default, single-process) or `redis` (multi-replica). Switch with one env var, no code change.
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
- **Prometheus metrics** at `/q/metrics`: `ai.requests.total` (by provider/type/status, where `type ∈ {text, vision, image}`) and `ai.request.duration`
- **Structured audit log**: one JSON line per invocation written to `logs/audit.log` (rotating, max 10 MB × 5 backups)
- **JSON console logs** (in `prod` profile): human-readable in dev/test, structured JSON in prod so Promtail/Loki can parse fields (`requestId`, `traceId`, `level`, `service.name`)
- **OpenTelemetry tracing**: W3C traceparent propagation, OTLP exporter configurable via `OTEL_EXPORTER_OTLP_ENDPOINT` (Tempo / Jaeger / any OTLP collector)
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

| Tool | Version | Purpose | Required? |
|------|---------|---------|-----------|
| Java (JDK) | 25 | Run / build the Quarkus app | Yes |
| Docker | 24+ | Container runtime | For containerised runs |
| Docker Compose | v2 | Multi-service orchestration | For containerised runs |
| Redis | 6+ | Shared rate-limit counters across replicas | Only when `AI_SHIM_RATELIMIT_BACKEND=redis` |
| OTLP collector | — | Trace sink (Tempo / Jaeger / OTel Collector) | Optional — spans drop silently if absent |
| auth-service or JWKS endpoint | — | JWT verification | Yes (or use a static public key — see Local Development §5) |

> Maven is bundled via the Maven wrapper (`./mvnw`). Python is not needed.

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
| `ANTHROPIC_API_KEY` | `claude-sonnet-4-20250514` |
| `AZURE_OPENAI_API_KEY` | `gpt-4o-mini` (deployment name) |

If a key is not set it defaults to `DISABLED`. The server starts normally; requests to that provider fail at call time, not at startup.

### Azure OpenAI (optional)

| Variable | Description |
|----------|-------------|
| `AZURE_OPENAI_RESOURCE_NAME` | Azure resource name |
| `AZURE_OPENAI_ENDPOINT` | Full endpoint URL (e.g. `https://myresource.openai.azure.com/`) |

### Free-Tier Provider Keys (optional)

These providers can also be configured per-request via the `api_key` field. Server-wide keys avoid passing them on every call.

| Variable | Description |
|----------|-------------|
| `GROQ_API_KEY` | Groq API key |
| `OPENROUTER_API_KEY` | OpenRouter API key |
| `MISTRAL_API_KEY` | Mistral API key |
| `CEREBRAS_API_KEY` | Cerebras API key |
| `XAI_API_KEY` | xAI API key |
| `COHERE_API_KEY` | Cohere API key |

### Model Overrides (optional)

| Variable | Description |
|----------|-------------|
| `OPENAI_MODEL` | Override default OpenAI model |
| `GEMINI_MODEL` | Override default Gemini text model |
| `GEMINI_VISION_MODEL` | Override default Gemini vision model |
| `DEEPSEEK_MODEL` | Override default DeepSeek model |
| `ANTHROPIC_MODEL` | Override default Anthropic model |
| `AZURE_OPENAI_DEPLOYMENT` | Override default Azure OpenAI deployment name |

### Rate Limiting

| Variable | Default | Description |
|----------|---------|-------------|
| `AI_SHIM_RATE_LIMIT_RPM` | `30` | Max requests per user per minute |
| `AI_SHIM_RATE_LIMIT_RPD` | `1000` | Max requests per user per day |
| `AI_SHIM_RATELIMIT_BACKEND` | `memory` (dev) / `redis` (prod profile) | Storage backend. `memory` = in-process counters (single replica only). `redis` = shared across replicas. |
| `REDIS_URL` | `redis://localhost:6379` | Redis connection string — used only when backend is `redis` |
| `AI_SHIM_REDIS_HEALTH` | `false` (dev) / `true` (prod profile) | Whether the Redis readiness probe contributes to `/q/health/ready`. Disable in dev so the service stays UP without a real Redis. |

### Image Generation

| Variable | Default | Description |
|----------|---------|-------------|
| `GEMINI_IMAGE_ENDPOINT` | `https://generativelanguage.googleapis.com/v1beta` | Base URL for the Gemini REST image-gen endpoint |
| `GEMINI_IMAGE_MODEL` | `gemini-2.5-flash-image` | Default Gemini image-gen model ID |
| `AI_SHIM_IMAGE_MAX_COUNT` | `4` | Cap on the number of images returned per request (requests above this are clamped and a warning is attached) |

### Observability

| Variable | Default | Description |
|----------|---------|-------------|
| `QUARKUS_PROFILE` | `dev` | Set to `prod` to enable JSON console logging, OTLP tracing, and Redis-backed rate limiting by default |
| `QUARKUS_OTEL_ENABLED` | `true` | Set `false` to fully disable OpenTelemetry (useful for local runs without a collector) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://otel-collector:4317` (prod profile) | OTLP/gRPC endpoint for trace export |

### Request / Upload Limits

| Variable | Default | Description |
|----------|---------|-------------|
| `AI_SHIM_MAX_BODY_SIZE` | `50M` | Max HTTP request body (raise for large PDF imports) |
| `AI_SHIM_MAX_UPLOAD_BYTES` | `20971520` | Max file upload size in bytes (20 MB) |
| `AI_SHIM_MAX_PROMPT_CHARS` | `200000` | Max characters in a raw prompt or combined template variables |

### Server

| Variable | Default | Description |
|----------|---------|-------------|
| `AI_SHIM_ALLOWED_ORIGIN` | `http://localhost:5173` | CORS allowed origin |
| `LOG_DIR` | `logs` | Directory for `audit.log` |

### Ollama (local or cloud)

Ollama is routed through the OpenAI-compatible API — no extra dependency needed. Works with both a local Ollama instance and Ollama Cloud.

| Variable | Default | Description |
|----------|---------|-------------|
| `OLLAMA_BASE_URL` | `http://localhost:11434/v1` | Ollama server URL (append `/v1` for OpenAI-compatible API). For Ollama Cloud, use your cloud endpoint. |
| `OLLAMA_VISION_MODEL` | `llava` | Default vision model name |
| `OLLAMA_TEXT_MODEL` | `llama3.2` | Default text model name |

---

## Local Development

AI Shim runs **fully standalone** — it has no database, and every external integration (Redis, OTLP collector, Infisical, auth-service) is optional. The only thing you must handle is the JWT that every endpoint requires.

### 1. Set environment variables

```bash
export GEMINI_API_KEY=your-gemini-key          # or OPENAI_API_KEY / DEEPSEEK_API_KEY
export AUTH_SERVICE_URL=http://localhost:8703  # auth-service must be running — or see alternatives below
```

### 2. Start in dev mode

```bash
./mvnw quarkus:dev
```

Quarkus starts on `http://localhost:8090` with live reload. Changes to `.kt`, `.properties`, or `.txt` template files trigger an automatic rebuild. In dev mode:

- Rate limiter uses the **in-memory** backend (no Redis required)
- Redis readiness probe is **disabled** so `/q/health/ready` stays UP even with no Redis on the box
- Console logs are **human-readable**, not JSON
- OpenTelemetry defaults to `http://localhost:4317` — if nothing listens there, spans are silently dropped (harmless)

### 3. Verify startup

```bash
curl http://localhost:8090/q/health
# → { "status": "UP" }
```

### 4. Open Swagger UI

```
http://localhost:8090/q/swagger-ui
```

### 5. JWT options for standalone development

Every endpoint requires `Authorization: Bearer <jwt>`. Three ways to satisfy this in local dev:

**a. Run auth-service alongside it** — clone [auth-service](https://github.com/adarshraj/auth-service) on port 8703. Most faithful to prod. `POST http://localhost:8703/auth/login` returns a token.

**b. Point at any JWKS endpoint you control** — set `AUTH_SERVICE_URL` to anything that serves a valid JWKS JSON at `/.well-known/jwks.json`.

**c. Swap in a static public key** — generate an ES256 keypair, set:
```bash
export MP_JWT_VERIFY_PUBLICKEY="-----BEGIN PUBLIC KEY-----..."
export MP_JWT_VERIFY_PUBLICKEY_LOCATION=  # empty — disables JWKS fetch
```
Sign your test tokens with the matching private key and pass them as `Authorization: Bearer <jwt>`.

### 6. Discover available templates and providers

```bash
curl -H "Authorization: Bearer $JWT" http://localhost:8090/ai/templates
curl -H "Authorization: Bearer $JWT" http://localhost:8090/ai/providers
```

### 7. Test a text request

```bash
curl -X POST http://localhost:8090/ai \
  -H "Authorization: Bearer $JWT" \
  -F provider=gemini \
  -F prompt='Write a 2-line rhyme about a cat' \
  -F 'model_params={"model":"gemini-2.0-flash"}'
```

### 8. Test an image request

```bash
curl -X POST http://localhost:8090/ai/image \
  -H "Authorization: Bearer $JWT" \
  -F provider=gemini \
  -F prompt='Flat cartoon illustration of a friendly orange cat wearing a space helmet, pastel background' \
  -F 'image_params={"model":"gemini-2.5-flash-image","size":"1024x1024"}' \
  -o response.json

# Decode the first image from the JSON response
jq -r '.images[0].base64' response.json | base64 -d > out.png
```

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
docker build -f src/main/docker/Dockerfile.jvm -t ai-shim:latest .
```

The image uses Eclipse Temurin 25 JRE, runs as a non-root user, and includes a `HEALTHCHECK` via `/q/health/live`.

---

## Running with Docker Compose

### 1. Create the shared external network

```bash
docker network create ai-shim-network
```

### 2. Configure environment

```bash
cp .env.example .env
# Fill in AUTH_SERVICE_URL and at least one AI provider key
```

### 3. Build and start

```bash
docker compose up --build -d
```

### 4. Verify

```bash
curl http://localhost:8090/q/health
```

### 5. View logs

```bash
docker compose logs -f ai-shim
```

### Docker Compose services

| Service | Port | Description |
|---------|------|-------------|
| `ai-shim` | `8090` (host) | Quarkus application |

**Networks:**
- `ai-shim-network` — external network your calling apps join to reach AI Shim

---

## Production Deployment (Multi-Client)

AI Shim is designed so one deployed instance can back **many client apps** (web frontends, Express/Python/Go services, mobile backends, batch jobs). This section covers the production posture.

### 1. Activate the prod profile

Set `QUARKUS_PROFILE=prod` in the runtime environment. This turns on:

- **JSON console logs** (for Loki / Promtail / any JSON-consuming aggregator)
- **Redis-backed rate limiting** (`aishim.ratelimit.backend=redis` by default)
- **Redis readiness probe** contributes to `/q/health/ready`
- **OTLP trace export** targeted at `OTEL_EXPORTER_OTLP_ENDPOINT`
- **`service.name=ai-shim`** on every log and trace

All other properties still honor env-var overrides, so you can flip any single default back (e.g. `AI_SHIM_RATELIMIT_BACKEND=memory` if you're running a single replica and don't want Redis).

### 2. Minimum production env vars

```bash
QUARKUS_PROFILE=prod

# JWT verification — points at your auth-service JWKS endpoint
AUTH_SERVICE_URL=https://auth.example.com

# CORS — comma-separated list of exact origins that host browser clients
AI_SHIM_ALLOWED_ORIGIN=https://app1.example.com,https://app2.example.com

# At least one provider key
GEMINI_API_KEY=...
OPENAI_API_KEY=...

# Shared rate-limit store (prod profile defaults backend to redis)
REDIS_URL=redis://redis:6379

# OTLP collector (Tempo / Jaeger / OpenTelemetry Collector)
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
```

### 3. Running multiple replicas

The in-memory rate limiter is **not safe** across multiple replicas — each pod would have its own counter and the effective per-user limit would multiply by the replica count. For any deployment with more than one replica:

- Set `AI_SHIM_RATELIMIT_BACKEND=redis` (prod profile already does this)
- Point all replicas at the same `REDIS_URL`
- Redis counters use `INCR` + `EXPIRE`, so no cleanup job is required — keys expire naturally at the end of each bucket window

Redis is the only shared state. Everything else (template cache, provider clients, metrics, audit log) is per-replica by design.

### 4. Supporting multiple client apps

Every client app talks to the **same** `ai-shim` instance. Isolation happens through:

- **Per-JWT rate limiting** — the gateway keys by JWT subject, so users of app A can't exhaust app B's quota
- **Plan quotas live in each client app** — ai-shim enforces a technical floor (abuse protection); business quotas (free vs. paid plans, per-feature caps) belong in the client because ai-shim doesn't know your pricing
- **Per-app API keys** — set `GROQ_API_KEY` / `OPENAI_API_KEY` server-side for shared use, or have each client pass its own key in the `api_key` request field so provider costs can be attributed per client
- **Shared prompt templates, private ones too** — templates live on the classpath, so any client can reference any template. If apps need private prompts, namespace the template names (e.g. `app1-summarize.txt`, `app2-summarize.txt`).

### 5. Service-to-service auth (for backend clients)

Browser clients pass through the end-user's JWT. Backend services have two options:

1. **Long-lived service token** issued by auth-service, stored in a secrets manager (Infisical, Vault, Doppler, AWS Secrets Manager). Rotate on a schedule. Simplest — start here.
2. **Client-credentials flow** — the backend requests a fresh token from auth-service every N minutes. More rotation discipline, slightly more moving parts.

Either way, the token travels in the standard `Authorization: Bearer <jwt>` header and is verified the same way as a browser-user token.

### 6. Platform integration (Traefik, Loki, Tempo, CrowdSec)

If you run the companion [platform](../platform) repo, ai-shim drops in with Docker labels:

```yaml
services:
  ai-shim:
    image: ai-shim:latest
    environment:
      QUARKUS_PROFILE: prod
      AUTH_SERVICE_URL: https://auth.example.com
      GEMINI_API_KEY: ${GEMINI_API_KEY}
      REDIS_URL: redis://platform-redis:6379
      OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4317
    networks:
      - platform_proxy
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.aishim.rule=Host(`ai-shim.example.com`)"
      - "traefik.http.routers.aishim.tls=true"
      - "traefik.http.routers.aishim.tls.certresolver=letsencrypt"
      - "traefik.http.services.aishim.loadbalancer.server.port=8090"

networks:
  platform_proxy:
    external: true
```

This wires up: TLS termination (Traefik), log shipping (Promtail → Loki), trace shipping (OTel collector → Tempo), metrics scraping (Prometheus → `/q/metrics`), and CrowdSec edge filtering — all without application changes.

### 7. Scaling guidance

| Dimension | Guidance |
|-----------|----------|
| **Replicas** | Stateless — scale horizontally behind any load balancer. Use `AI_SHIM_RATELIMIT_BACKEND=redis` from 2+ replicas. |
| **CPU** | Light — the gateway is almost entirely IO-bound waiting on upstream providers. 0.25–0.5 vCPU per replica is usually enough. |
| **Memory** | Template cache + LangChain4j clients ~200 MB baseline. `MaxRAMPercentage=75.0` already set in the JVM Dockerfile. |
| **Upstream limits** | Provider rate limits (Gemini free tier = 15 RPM / 1500 RPD) are the real scale ceiling. Move to paid tiers before scaling ai-shim replicas matters. |
| **Bulkhead** | 10 concurrent calls × number of replicas. A 3-replica deployment handles 30 concurrent provider calls before the bulkhead starts queuing. |
| **Graceful shutdown** | 30 s drain window on SIGTERM — size your rolling-deploy readiness delay accordingly. |

### 8. Secrets handling

Never bake API keys into the image or Docker Compose file. Inject at runtime from:

- **Infisical** (recommended if using the platform repo)
- **Vault** / **Doppler** / **AWS Secrets Manager** / **GCP Secret Manager**
- **Docker secrets** / **Kubernetes secrets**

Rotate provider keys by updating the secret store and restarting the pod — ai-shim reads them once at startup.

---

## API Reference

All endpoints require `Authorization: Bearer <jwt>` (ES256, issued by [auth-service](https://github.com/adarshraj/auth-service)).

Interactive documentation is available at `/q/swagger-ui`.

---

### `GET /ai/templates`

Returns all available prompt templates and their variable placeholders.

Response is cached for 5 minutes (`Cache-Control: public, max-age=300`).

**Response:**
```json
{
  "templates": [
    {
      "name": "my-template",
      "variables": ["context", "question"],
      "has_system_prompt": true
    }
  ]
}
```

---

### `GET /ai/providers/{provider}/models`

List models available from a specific provider. Proxies the upstream provider's model listing API and returns a unified response.

Cached for 1 minute (`Cache-Control: public, max-age=60`).

**Path parameter:** `{provider}` — Provider ID (e.g. `openai`, `openrouter`, `gemini`, `anthropic`, `groq`, `mistral`, `cerebras`, `xai`, `cohere`, `deepseek`, `ollama`)

**Headers:**

| Header | Required | Description |
|--------|----------|-------------|
| `X-Provider-Api-Key` | No | Per-request API key for the provider. Passed via header (not query param) to avoid leaking keys in access logs and browser history. If omitted, the server-configured key or environment variable is used. |

**Response:**
```json
{
  "provider": "openrouter",
  "models": [
    { "id": "meta-llama/llama-3-8b-instruct:free", "name": "meta-llama/llama-3-8b-instruct:free", "created": 1234567890, "owned_by": "meta" },
    { "id": "google/gemma-2-9b-it:free", "name": "google/gemma-2-9b-it:free", "created": 1234567890, "owned_by": "google" }
  ]
}
```

`created` and `owned_by` are omitted when not available from the upstream provider.

**Supported providers:**

| Provider | API used |
|----------|----------|
| `openai`, `deepseek`, `groq`, `openrouter`, `mistral`, `cerebras`, `xai`, `cohere` | OpenAI-compatible `GET /v1/models` |
| `anthropic` | `GET /v1/models` with `x-api-key` header |
| `gemini` | `GET /v1beta/models?key=...` |
| `azure_openai` | Not supported (model availability depends on deployment) |
| `ollama` | OpenAI-compatible `GET {OLLAMA_BASE_URL}/models` (no auth for local) |

**Error responses:**

| Status | Cause |
|--------|-------|
| `400` | Unknown provider, missing API key, or unsupported provider |
| `401` | Missing or invalid JWT |
| `500` | Upstream provider error |

---

### `POST /ai`

Unified text + vision endpoint. Send `multipart/form-data`. If a `file` is attached, it's a vision request; otherwise it's text-only.

**Request fields:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `provider` | string | Yes | `openai`, `gemini`, `anthropic`, `ollama`, etc. |
| `prompt` | string | No* | Raw prompt text |
| `template` | string | No* | Template name from the classpath prompts directory |
| `variables` | JSON string | No | Template variable overrides, e.g. `{"question":"..."}` |
| `system_prompt` | string | No | System persona override |
| `messages` | JSON string | No | Multi-turn history (takes precedence over prompt/template) |
| `model_params` | JSON string | Yes | Must include `model`. e.g. `{"model":"gpt-4o","temperature":0.3}` |
| `api_key` | string | No | Per-request provider API key |
| `file` | binary | No | Image (JPEG/PNG/WebP/BMP/HEIC) or PDF for vision requests, max 20 MB |

\* Either `prompt`, `template`, or `messages` is required. Priority: `messages` > `prompt` > `template`.

Call `GET /ai/providers/{provider}/models` first to discover available models.

**Examples:**

```bash
# Text request
curl -X POST http://localhost:8090/ai \
  -H "Authorization: Bearer $JWT" \
  -F provider=openai \
  -F prompt="Summarise my expenses" \
  -F 'model_params={"model":"gpt-4o","temperature":0.3}'

# Vision request (attach a file)
curl -X POST http://localhost:8090/ai \
  -H "Authorization: Bearer $JWT" \
  -F provider=gemini \
  -F prompt="Extract text from this receipt" \
  -F 'model_params={"model":"gemini-2.0-flash"}' \
  -F file=@receipt.jpg
```

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
| `400` | Unknown provider, missing prompt/template, missing model, injection detected, harmful content, prompt too long |
| `401` | Missing or invalid JWT |
| `413` | File too large |
| `429` | Per-minute or per-day rate limit exceeded (`Retry-After` header included) |
| `503` | Provider bulkhead full — too many concurrent requests |
| `500` | Provider error |

---

### `POST /ai/image`

Image generation endpoint. Send `multipart/form-data`. Today only `gemini` is wired (`gemini-2.5-flash-image`); `openai` (`gpt-image-1`) and `azure_openai` (DALL·E) slot in through the same `ImageGenService` interface.

**Request fields:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `provider` | string | Yes | `gemini` (more coming) |
| `prompt` | string | No* | Raw prompt text describing the image |
| `template` | string | No* | Template name of a `prompts/*.txt` file on the classpath |
| `variables` | JSON string | No | Template variable overrides |
| `system_prompt` | string | No | Style / persona prefix prepended to the prompt |
| `image_params` | JSON string | No | See [ImageParams fields](#imageparams-fields) |
| `api_key` | string | No | Per-request provider API key (ignored by Gemini — server key is used) |
| `reference` | binary | No | Optional reference image(s) for edit / variation flows. Repeatable field. Max 20 MB each. |

\* Either `prompt` or `template` is required.

**Example (raw prompt):**

```bash
curl -X POST http://localhost:8090/ai/image \
  -H "Authorization: Bearer $JWT" \
  -F provider=gemini \
  -F prompt='Flat cartoon illustration of a friendly orange cat wearing a space helmet, pastel background' \
  -F 'image_params={"model":"gemini-2.5-flash-image","size":"1024x1024"}'
```

**Example (template):**

```bash
curl -X POST http://localhost:8090/ai/image \
  -H "Authorization: Bearer $JWT" \
  -F provider=gemini \
  -F template=my-image-template \
  -F 'variables={"subject":"dinosaurs","style":"flat cartoon"}' \
  -F 'image_params={"model":"gemini-2.5-flash-image"}'
```

Templates are resolved from the classpath `prompts/` directory. ai-shim is app-agnostic and ships with **no bundled templates** — clients send raw prompts via the `prompt` field by default. Deployers who want server-side prompt management can drop their own `.txt` files into `src/main/resources/prompts/` at build time.

**Response:**

```json
{
  "provider": "gemini",
  "model": "gemini-2.5-flash-image",
  "processing_time_ms": 4120,
  "input_tokens": 42,
  "output_tokens": 1290,
  "images": [
    {
      "base64": "iVBORw0KGgoAAAANSUhEUgAA...",
      "mime_type": "image/png",
      "size_bytes": 284719,
      "finish_reason": "STOP"
    }
  ],
  "warnings": []
}
```

- `images[]` is always an array, even when `count=1`.
- Exactly one of `base64` / `url` is populated per image (Gemini always returns `base64`).
- `warnings[]` surfaces non-fatal issues such as `"style ignored by gemini"` or `"count clamped to 4"`.
- The base64 payload is **never** written to the audit log — only `image_count` and `image_bytes_out`.

**Error responses:** same envelope as `POST /ai`. An extra case:

| Status | Cause |
|--------|-------|
| `400` | Provider does not support image generation (e.g. `deepseek`, `anthropic`) |

### `ImageParams` fields

| Field | Type | Providers | Description |
|-------|------|-----------|-------------|
| `model` | string | All | Provider-specific model ID (e.g. `gemini-2.5-flash-image`, `gpt-image-1`) |
| `size` | string | All | `WxH` format, e.g. `"1024x1024"` |
| `count` | integer | All | Number of images. Clamped to `AI_SHIM_IMAGE_MAX_COUNT` (default 4). |
| `quality` | string | OpenAI-family | `standard` / `hd` (ignored by Gemini, surfaced as warning) |
| `style` | string | OpenAI-family | `vivid` / `natural` (ignored by Gemini) |
| `response_format` | string | OpenAI-family | `b64` (default) / `url`. Gemini always returns `b64`. |
| `seed` | integer | Provider-dependent | Reproducibility seed |
| `negative_prompt` | string | Provider-dependent | Ignored by Gemini; surfaced as warning |
| `timeout_seconds` | integer | Gemini | Per-request timeout override |

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

## Image Generation

Image generation is exposed at `POST /ai/image` and sits behind the same middleware stack as text/vision (PromptGuard, ContentGuard, RateLimiter, Bulkhead, CircuitBreaker, Retry, AuditService, metrics).

### Architecture

```
POST /ai/image ──► AiResource.image()
                      │
                      ▼
                   AiService.generateImage()
                      │     (PromptGuard + ContentGuard + template substitution)
                      ▼
                   ImageServiceResolver
                      │
                      ├── GEMINI    → GeminiImageGenService    (REST: generateContent, responseModalities=[TEXT,IMAGE])
                      ├── OPENAI    → (pending: gpt-image-1)
                      └── AZURE     → (pending: DALL·E)
```

Each provider implements the `ImageGenService` interface:

```kotlin
interface ImageGenService {
    fun generate(
        prompt: String,
        systemPrompt: String? = null,
        params: ImageParams? = null,
        referenceImages: List<ReferenceImage> = emptyList(),
        apiKey: String? = null,
    ): ImageProviderResult
}
```

The `AiProvider` enum has a `supportsImageGen` flag — `ImageServiceResolver` consults this before dispatching, so providers that don't support image generation fail fast with a helpful error.

### Adding a new image-gen provider

1. Create `provider/<name>/<Name>ImageGenService.kt` implementing `ImageGenService`
2. Annotate with `@ApplicationScoped` and apply `@Bulkhead`, `@CircuitBreaker`, `@Retry` on the `generate` function
3. Inject the new service into `ImageServiceResolver` and add its case to the `when` block
4. Flip `supportsImageGen = true` on the relevant `AiProvider` enum entry
5. Add the provider's config keys under `aishim.image.<provider>.*` in `application.properties`

No changes to `AiService`, `AiResource`, guards, audit, or metrics are required — the response shape is already normalised and all cross-cutting concerns sit above the provider layer.

### Image-gen templates (optional)

Prompt templates work the same for image generation as for text — a `.txt` file in `src/main/resources/prompts/` with `{placeholder}` substitution. ai-shim is **app-agnostic** and does not ship with domain-specific image templates. Clients can either:

1. **Send raw prompts** via the `prompt` field — simplest, and the right default for most integrations. Each calling app owns its own prompt engineering.
2. **Add their own templates** — drop a `.txt` file into `prompts/` at build time for prompts that need guard-rails or placeholder substitution on the server.

Example of a custom template a caller might add:

```
Flat cartoon illustration of {subject}, {style}.
Centered composition, no text, no letters, no words.
```

Save as `prompts/my-image.txt` and call with `template=my-image`, `variables={"subject":"...","style":"..."}`.

---

## Security

### Authentication

All endpoints require a valid JWT in the `Authorization: Bearer <token>` header. Tokens must be ES256-signed by [auth-service](https://github.com/adarshraj/auth-service). AI Shim fetches the public key automatically from `AUTH_SERVICE_URL/.well-known/jwks.json` and validates the signature and issuer claim. No shared secret is needed.

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

Raw prompts and combined template variable values are capped at `AI_SHIM_MAX_PROMPT_CHARS` (default 200,000 characters, ≈ 50,000 tokens). Requests exceeding this limit receive `400 Bad Request`.

---

## Rate Limiting

Rate limiting is applied per authenticated user (JWT subject) before the request reaches the AI provider.

### Limits

| Limit | Default | Override |
|-------|---------|----------|
| Per minute | 30 requests | `AI_SHIM_RATE_LIMIT_RPM` |
| Per day | 1,000 requests | `AI_SHIM_RATE_LIMIT_RPD` |

### Pluggable storage backend

The rate limiter stores counters through the `RateLimiterBackend` interface. Two implementations ship:

| Backend | When to use | How to select |
|---------|-------------|---------------|
| `memory` (default in dev) | Single-process deployments, local dev, tests | `AI_SHIM_RATELIMIT_BACKEND=memory` |
| `redis` (default in prod profile) | Any deployment with **2+ replicas** — counters are shared so effective limits match config | `AI_SHIM_RATELIMIT_BACKEND=redis` + `REDIS_URL=redis://host:6379` |

The Redis backend uses `INCR` + conditional `EXPIRE` — keys expire naturally at the end of each window, so no cleanup job is required. In `memory` mode the Redis client is **never instantiated**; no connection is opened even though the dependency is on the classpath.

**Switching backends is a one-line env-var change — no rebuild, no code change.**

Adding a new backend (e.g. DynamoDB, Postgres) is a single class implementing `incrementWithTtl(key, ttlSeconds)` and a `@LookupIfProperty` annotation matching a new value of `aishim.ratelimit.backend`.

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

### What rate limiting does NOT cover

- **Plan quotas** (free vs. paid tiers) — these belong in each client app, not in ai-shim. The gateway enforces an abuse floor; the client enforces pricing.
- **Per-provider upstream limits** — the Gemini free tier has its own 15 RPM / 1500 RPD cap that ai-shim does not currently enforce. If you hit it, you'll get 429s from Gemini surfaced as 500s. Run on paid tiers or add a per-provider limiter layer when this becomes a problem.
- **Edge / IP-level abuse** — put Cloudflare, a Traefik middleware, or CrowdSec in front for pre-auth filtering.

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
| `ai_requests_total` | `provider`, `type` (text / vision / image), `status` (success / failure) | Total invocation count |
| `ai_request_duration_seconds` | `provider`, `type` | Invocation latency histogram |

Scrape config for Prometheus:

```yaml
scrape_configs:
  - job_name: ai-shim
    metrics_path: /q/metrics
    static_configs:
      - targets: ['ai-shim:8090']
```

### Audit Log

Every AI invocation writes a structured JSON line to `logs/audit.log`:

```json
{
  "userId": "alice",
  "action": "text",
  "provider": "openai",
  "template": "my-template",
  "model": "gpt-4o-mini",
  "processingTimeMs": 912,
  "success": true,
  "errorType": "",
  "inputTokens": 312,
  "outputTokens": 48
}
```

The log rotates at 10 MB with 5 backups. Prompt content is intentionally excluded.

### Console Logs

- **Dev / test**: human-readable format with request ID, trace ID, and span ID in brackets
- **Prod profile** (`QUARKUS_PROFILE=prod`): structured JSON one-line-per-log so Promtail / Fluent Bit / Vector can parse fields natively. Every line carries `service.name=ai-shim`, plus standard OTel `traceId` / `spanId` from MDC.

### Request Tracing

Each request gets an 8-hex-char `requestId` generated at entry:
- Injected into SLF4J MDC — appears in every log line for that request
- Returned to the caller as `X-Request-Id` response header
- Exposed as a CORS header so browser clients can read it

### Distributed Tracing (OpenTelemetry)

ai-shim ships with `quarkus-opentelemetry`. It:

- Honors W3C `traceparent` headers from upstream callers, so traces from your client apps continue through ai-shim
- Exports spans via OTLP/gRPC to `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://otel-collector:4317` in the prod profile)
- Works with Tempo, Jaeger, Honeycomb, any OTLP-compatible backend
- Set `QUARKUS_OTEL_ENABLED=false` to disable entirely for local runs

Every AI invocation becomes a trace with spans covering template resolution, PromptGuard, ContentGuard, the provider call, and the response parse — invaluable when debugging "why did this take 40 seconds".

### Health Check

`GET /q/health/ready` runs a custom `ProviderHealthCheck` that reports the enabled/disabled status of each provider. The service reports `UP` if at least one provider is enabled.

When `AI_SHIM_REDIS_HEALTH=true` (default in prod profile), the Redis readiness probe also contributes — the service reports `DOWN` if Redis is unreachable. In dev the probe is disabled so `/q/health/ready` stays UP without a real Redis.

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

**None.** ai-shim is app-agnostic and does not ship with any domain-specific prompt templates. Every caller is free to:

- Send raw prompts via the `prompt` field (the default, and what most clients should do)
- Manage their own prompts in their own codebase or database
- Drop private `.txt` files into `src/main/resources/prompts/` at build time if they want the server to handle placeholder substitution, guard-rails, or system-prompt sections

The template machinery is still fully supported — it's just unopinionated about content.

### Adding a template (optional)

1. Create `src/main/resources/prompts/my-feature.txt`
2. Restart the app (or hot-reload in dev mode)
3. Call `/ai` with `"template": "my-feature"`

---

## Provider Configuration Guide

### Choosing a provider

| Value | Vision | Text | Notes |
|-------|--------|------|-------|
| `openai` | Yes | Yes | Best quality; paid |
| `gemini` | Yes | Yes | Good quality; free tier available |
| `deepseek` | No | Yes | Text-only; cost-effective for reasoning |
| `anthropic` | Yes | Yes | Claude models; paid |
| `azure_openai` | Yes | Yes | Azure-hosted OpenAI; requires deployment config |
| `groq` | Yes | Yes | Llama/Kimi models; 30 RPM free |
| `openrouter` | Yes | Yes | Routes to many models; 32+ free models |
| `mistral` | Yes | Yes | Mistral models; 1B tokens/month free |
| `cerebras` | No | Yes | Text-only; fastest inference |
| `xai` | No | Yes | Grok models; text-only |
| `cohere` | No | Yes | Command models; text-only |
| `ollama` | Yes (llava) | Yes | Local or cloud; free; uses OpenAI-compatible API |
| `openai_compatible` | Yes | Yes | Any OpenAI-compatible endpoint; requires `base_url` in `model_params` |

### Per-request API key

Supply `api_key` in the request body to override the server-configured key for that call. A dynamic client is built using the provided key and the server's base URL.

Supported for **OpenAI** and **DeepSeek**. For **Gemini**, the server-configured key is always used (a warning is logged if `api_key` is supplied).

### Model selection (required)

The client must specify `model_params.model` on every request. Call `GET /ai/providers/{provider}/models` first to discover available models.

```json
{ "provider": "openai", "model_params": { "model": "gpt-4o" }, "prompt": "Hello" }
```

### Global default model override

```bash
OPENAI_MODEL=gpt-4o
GEMINI_MODEL=gemini-2.5-flash
DEEPSEEK_MODEL=deepseek-reasoner
```

---

## Using Ollama

Ollama is supported out of the box via its OpenAI-compatible API — no extra dependency needed. Works with both local instances and Ollama Cloud.

### Local Ollama

```bash
ollama pull llava      # vision model (~4 GB)
ollama pull llama3.2   # text model (~2 GB)
ollama serve           # starts on http://localhost:11434
```

The default `OLLAMA_BASE_URL` (`http://localhost:11434/v1`) points to a local instance. No further configuration needed.

### Ollama Cloud

Set `OLLAMA_BASE_URL` to your Ollama Cloud endpoint:

```bash
OLLAMA_BASE_URL=https://your-endpoint.ollama.ai/v1
```

### Usage

```bash
# Text request using local Ollama
curl -X POST http://localhost:8090/ai \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"provider":"ollama","prompt":"Hello!","model_params":{"model":"llama3.2"}}'

# List available Ollama models
curl "http://localhost:8090/ai/providers/ollama/models" \
  -H "Authorization: Bearer $JWT"

# With a per-request API key (e.g. for OpenRouter)
curl "http://localhost:8090/ai/providers/openrouter/models" \
  -H "Authorization: Bearer $JWT" \
  -H "X-Provider-Api-Key: sk-or-..."
```

---

## Connecting Your App

AI Shim is designed to be called by any application. The only shared configuration is `AUTH_SERVICE_URL` — your app obtains JWTs from auth-service and passes them to AI Shim.

### Required environment variable in your app

```env
AI_SHIM_URL=http://ai-shim:8090    # Docker (use service name)
AI_SHIM_URL=http://localhost:8090  # local dev
```

### Docker network setup

Your app's `docker-compose.yml` must join the same external network:

```yaml
networks:
  ai-shim-network:
    external: true
```

### Minimal request example (JavaScript / fetch)

```js
const res = await fetch(`${AI_SHIM_URL}/ai`, {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${jwt}`,
    'Content-Type': 'application/json',
  },
  body: JSON.stringify({
    provider: 'gemini',
    prompt: `Answer the following question based on this context.\n\nContext: ${context}\n\nQuestion: ${question}`,
    model_params: { model: 'gemini-2.0-flash' },
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
ai-shim/
├── mvnw / mvnw.cmd                     # Maven wrapper — no Maven installation required
├── pom.xml                             # Build (Quarkus 3.32, Java 25, Kotlin 2.3)
├── docker-compose.yml                  # ai-shim service
├── .env.example                        # Template for environment variables
├── .dockerignore                       # Excludes target/, .git/, test sources
├── .github/
│   └── workflows/ci.yml               # GitHub Actions — build + test on push/PR
│
├── src/main/docker/
│   └── Dockerfile.jvm                 # Non-root JRE-25 image with HEALTHCHECK
│
└── src/main/kotlin/com/adars/aishim/
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
    │   ├── AiModels.kt               # AiInvokeResponse, TemplateInfo, ProviderInfo,
    │   │                             # ModelParams, ChatMessage, ProviderResult
    │   └── ImageModels.kt            # ImageParams, GeneratedImage, ImageProviderResult,
    │                                 # AiImageResponse
    ├── resource/
    │   └── AiResource.kt             # GET  /ai/templates
    │                                 # GET  /ai/providers
    │                                 # GET  /ai/providers/{id}/models
    │                                 # POST /ai       (text + vision)
    │                                 # POST /ai/image (image generation)
    ├── service/
    │   ├── AiService.kt              # Template loading, prompt resolution, dispatch
    │   │                             # (invoke / invokeVision / generateImage)
    │   ├── ModelListService.kt       # Proxies upstream provider model listing APIs
    │   ├── AuditService.kt           # Structured JSON audit log (logs/audit.log)
    │   ├── RateLimiter.kt            # Per-user sliding-minute + daily quota (delegates to backend)
    │   ├── ratelimit/
    │   │   ├── RateLimiterBackend.kt              # Backend interface
    │   │   ├── InMemoryRateLimiterBackend.kt      # Default; @LookupIfProperty(memory)
    │   │   └── RedisRateLimiterBackend.kt         # @LookupIfProperty(redis) — INCR + EXPIRE
    │   ├── PromptGuard.kt            # Injection detection on user-supplied values
    │   └── ContentGuard.kt           # Harmful-intent detection on assembled prompts
    │
    └── provider/
        ├── AiProvider.kt             # Enum of supported providers (supportsImageGen flag)
        ├── ProviderResolver.kt       # Routes text/vision to correct service bean
        ├── image/
        │   ├── ImageGenService.kt    # Contract for image-gen providers
        │   └── ImageServiceResolver.kt  # Routes POST /ai/image to the right bean
        ├── openai/                   # OpenAiTextService, OpenAiOcrService
        ├── gemini/
        │   ├── GeminiTextService.kt
        │   ├── GeminiOcrService.kt
        │   └── GeminiImageGenService.kt  # REST generateContent, responseModalities=[TEXT,IMAGE]
        ├── deepseek/                 # DeepSeekTextService (OpenAI-compatible)
        ├── anthropic/                # AnthropicTextService, AnthropicOcrService
        └── azure/                    # AzureOpenAiTextService, AzureOpenAiOcrService

src/main/resources/
    ├── application.properties        # Base config + %prod profile overrides
    └── prompts/
        └── (empty — no bundled templates; callers supply their own prompts)
```
