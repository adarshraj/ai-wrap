"""
PaddleOCR sidecar service.
Accepts image (JPEG, PNG, WebP, BMP, HEIC/HEIF) and PDF uploads and returns
OCR text with per-line confidence scores.
"""

import io
import logging
from typing import List

import pypdfium2 as pdfium
import numpy as np
import pillow_heif
from fastapi import FastAPI, File, HTTPException, UploadFile
from paddleocr import PaddleOCR
from PIL import Image
from pydantic import BaseModel

# Register HEIF/HEIC as a Pillow plugin so Image.open() handles it transparently
pillow_heif.register_heif_opener()

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("paddle-ocr")

app = FastAPI(title="PaddleOCR Sidecar", version="1.0.0")

# Initialised once at startup — model files are pre-downloaded in the Docker image
ocr_engine = PaddleOCR(use_angle_cls=True, lang="en", use_gpu=False, show_log=False)

SUPPORTED_MIME_TYPES = {
    "image/jpeg",
    "image/jpg",
    "image/png",
    "image/webp",
    "image/bmp",
    "image/heic",
    "image/heif",
    "application/pdf",
    "application/octet-stream",  # Quarkus REST client sends File parts as octet-stream
}
import os
MAX_FILE_BYTES = int(os.environ.get("AI_WRAP_MAX_UPLOAD_BYTES", str(20 * 1024 * 1024)))


class OcrLine(BaseModel):
    text: str
    confidence: float
    bbox: List[List[float]]


class OcrResult(BaseModel):
    text: str
    lines: List[OcrLine]
    confidence: float
    provider: str = "paddle"


@app.get("/health")
def health():
    return {"status": "ok", "provider": "paddle"}


def _ocr_image(image: Image.Image) -> tuple[List[OcrLine], List[str]]:
    """Run PaddleOCR on a single PIL image and return (lines, texts)."""
    img_array = np.array(image.convert("RGB"))
    result = ocr_engine.ocr(img_array, cls=True)
    lines: List[OcrLine] = []
    texts: List[str] = []
    if result and result[0]:
        for item in result[0]:
            bbox, (text, confidence) = item
            lines.append(OcrLine(text=text, confidence=round(float(confidence), 4), bbox=bbox))
            texts.append(text)
    return lines, texts


def _images_from_pdf(raw: bytes) -> List[Image.Image]:
    """Render each PDF page to a PIL Image at 150 DPI."""
    doc = pdfium.PdfDocument(raw)
    images = []
    for page in doc:
        bitmap = page.render(scale=150 / 72)
        images.append(bitmap.to_pil().convert("RGB"))
    doc.close()
    return images


@app.post("/ocr", response_model=OcrResult)
async def process_image(file: UploadFile = File(...)):
    """Accept an image (JPEG/PNG/WebP/BMP/HEIC) or PDF, run PaddleOCR, return text and bounding boxes."""
    content_type = (file.content_type or "image/jpeg").lower()
    if content_type not in SUPPORTED_MIME_TYPES:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported content type '{content_type}'. Supported: {sorted(SUPPORTED_MIME_TYPES)}",
        )

    raw = await file.read()
    if len(raw) > MAX_FILE_BYTES:
        raise HTTPException(status_code=400, detail=f"File exceeds {MAX_FILE_BYTES // (1024*1024)} MB limit")

    try:
        all_lines: List[OcrLine] = []
        all_texts: List[str] = []

        if content_type == "application/pdf":
            images = _images_from_pdf(raw)
            if not images:
                raise HTTPException(status_code=400, detail="PDF contains no renderable pages")
            logger.info("PDF has %d page(s)", len(images))
            for i, img in enumerate(images):
                page_lines, page_texts = _ocr_image(img)
                all_lines.extend(page_lines)
                all_texts.extend(page_texts)
                logger.info("  Page %d: %d lines", i + 1, len(page_lines))
        else:
            # Images — HEIC/HEIF handled transparently via pillow-heif plugin
            image = Image.open(io.BytesIO(raw))
            all_lines, all_texts = _ocr_image(image)

        full_text = "\n".join(all_texts)
        avg_confidence = (
            sum(line.confidence for line in all_lines) / len(all_lines) if all_lines else 0.0
        )

        logger.info("OCR done: %d lines, avg_confidence=%.3f", len(all_lines), avg_confidence)
        return OcrResult(
            text=full_text,
            lines=all_lines,
            confidence=round(avg_confidence, 4),
        )

    except HTTPException:
        raise
    except Exception as exc:
        logger.exception("PaddleOCR processing failed")
        raise HTTPException(status_code=500, detail=str(exc)) from exc
