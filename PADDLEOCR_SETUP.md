# Running AI Wrap with PaddleOCR

## Prerequisites

- Docker 24+ and Docker Compose v2

> **No AI provider API key required for PaddleOCR.** PaddleOCR runs entirely as a local sidecar — no external API calls. An API key is only needed if you also want to use OpenAI, Gemini, or DeepSeek alongside it.

---

## Step 1: Set Up the Environment File

```bash
cd /home/adarsh/Downloads/ConvertedNutProjects/ai-wrap
cp .env.example .env
```

Edit `.env` with the required values:

```bash
# REQUIRED
JWT_SECRET=your-secret-key-here-make-it-long-and-random

# Enable PaddleOCR sidecar (no external API key needed)
PADDLE_OCR_ENABLED=true
PADDLE_OCR_URL=http://paddle-ocr:8091

# Optional: only needed if you also want LLM providers
# OPENAI_API_KEY=sk-...
# GEMINI_API_KEY=AIza...
# DEEPSEEK_API_KEY=sk-...
```

---

## Step 2: Create the Docker Network

```bash
docker network create ai-wrap-network
```

Both services communicate over this external network — must be created before starting.

---

## Step 3: Build and Start with PaddleOCR Profile

```bash
docker compose --profile ocr up --build -d
```

- `--profile ocr` enables the `paddle-ocr` sidecar service
- `--build` rebuilds images (required on first run or after file changes)
- `-d` runs in detached/background mode

> **Note:** First build downloads ~500 MB of PaddleOCR models. Subsequent starts are fast.

### Known Issue: `munmap_chunk(): invalid pointer` (core dumped)

This crash occurs during the model pre-download step in Docker. Root cause: `paddlepaddle==2.6.2` has a heap corruption bug on Debian Bookworm (used by `python:3.11-slim`).

**Fix applied in this repo:**
- `paddle-ocr-sidecar/requirements.txt`: downgraded `paddlepaddle` from `2.6.2` → `2.5.2`
- `paddle-ocr-sidecar/Dockerfile`: changed base image from `python:3.11-slim` → `python:3.10-slim`

After applying the fix, rebuild with `--no-cache` to avoid using the broken cached layer:

```bash
docker compose --profile ocr build --no-cache paddle-ocr
docker compose --profile ocr up -d
```

---

## Step 4: Verify Services Are Running

```bash
# Check both containers are up
docker compose ps

# Check PaddleOCR health
curl http://localhost:8091/health
# Expected: {"status":"ok","provider":"paddle"}

# Check AI Wrap health
curl http://localhost:8090/q/health/ready
# Expected: {"status":"UP",...}
```

---

## Step 5: Generate a JWT Token

All endpoints require a `Authorization: Bearer <token>` header.

**Option A — jwt-cli:**
```bash
npm install -g jwt-cli
jwt sign --secret "your-secret-key-here" --alg HS256 '{"sub":"user1"}'
```

**Option B — https://jwt.io:**
- Algorithm: HS256
- Secret: value of `JWT_SECRET` from `.env`
- Payload: `{ "sub": "user1" }`

---

## Step 6: Submit an OCR Request

**Basic text extraction:**
```bash
curl -X POST http://localhost:8090/ai/invoke/vision \
  -H "Authorization: Bearer <your-jwt-token>" \
  -F "file=@/path/to/image.jpg" \
  -F "provider=PADDLE" \
  -F "prompt=Extract all text from this document"
```

**Structured receipt/invoice extraction (built-in template):**
```bash
curl -X POST http://localhost:8090/ai/invoke/vision \
  -H "Authorization: Bearer <your-jwt-token>" \
  -F "file=@/path/to/receipt.jpg" \
  -F "provider=PADDLE" \
  -F "template=ocr-receipt-structured"
```

**Supported file formats:** JPEG, PNG, WebP, BMP, HEIC/HEIF, PDF (max 20 MB)

---

## Step 7: View Logs

```bash
# PaddleOCR sidecar logs
docker compose logs paddle-ocr -f

# AI Wrap logs
docker compose logs ai-wrap -f

# Structured audit log
tail -f logs/audit.log
```

---

## To Stop

```bash
docker compose --profile ocr down
```

---

## Alternative: Run PaddleOCR Sidecar Locally (Without Docker)

```bash
cd paddle-ocr-sidecar

python3 -m venv venv
source venv/bin/activate

pip install -r requirements.txt

uvicorn main:app --host 0.0.0.0 --port 8091
```

Then in `.env` set:
```bash
PADDLE_OCR_URL=http://localhost:8091
```

And start AI Wrap without the OCR profile:
```bash
docker compose up --build -d
```

---

## Other Useful Endpoints

| Endpoint | Purpose |
|----------|---------|
| `GET /ai/meta` | List available templates and providers |
| `GET /q/health` | Combined health check |
| `GET /q/metrics` | Prometheus metrics |
| `GET /q/swagger-ui` | Interactive API docs |
