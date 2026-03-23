FROM python:3.10-slim

WORKDIR /app

# System libraries required by PaddleOCR / OpenCV / HEIC (libheif)
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgomp1 \
    libglib2.0-0 \
    libsm6 \
    libxext6 \
    libxrender-dev \
    libgl1 \
    libheif1 \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py .

EXPOSE 8091

HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -f http://localhost:8091/health || exit 1

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8091"]
