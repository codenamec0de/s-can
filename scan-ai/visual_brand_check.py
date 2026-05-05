# Phase 6 skeleton — design draft. Do not call from production endpoints until reviewed.
#
# Visual brand impersonation check for S'CAN URL pipeline.
# Consumes screenshot from Phase 5 sandboxed crawler, asks Qwen2-VL-7B (Q4 via Ollama)
# whether the page visually impersonates one of 15 AU brands.
#
# See ~/scan-ai/docs/phase6-visual-brand-check.md for full design rationale.

from __future__ import annotations

import base64
import io
import json
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Optional

import httpx
from PIL import Image

# ----------------------------------------------------------------------------
# Constants
# ----------------------------------------------------------------------------

# Model served locally by Ollama. Pin the digest in production, not just the tag.
# Switched from qwen2-vl:7b-q4 (not on Ollama library) to qwen2.5vl:7b on 2026-04-29.
OLLAMA_MODEL_TAG = "qwen2.5vl:7b"
OLLAMA_HOST = "http://127.0.0.1:11434"

# Preprocessing targets (see design doc s.2).
TARGET_WIDTH = 896
TARGET_HEIGHT = 560
JPEG_QUALITY = 85
MIN_INPUT_DIM_PX = 200
MAX_OUTPUT_BYTES = 5 * 1024 * 1024

# Fusion threshold. Below this, top_match is reported as None.
MIN_TOP_MATCH_SCORE = 0.5


# ----------------------------------------------------------------------------
# Prompt template
# ----------------------------------------------------------------------------
# Authored choices (see design doc s.4):
#   - System framing as fraud analyst reduces hedging.
#   - Closed-set instruction prevents invented brands.
#   - Inline JSON schema → reliable structured output from Qwen2-VL-7B.
#   - Anchored 0.0 / 0.5 / 0.8 / 1.0 scale reduces inter-run drift.
#   - Evidence required for any non-zero score → guards against hallucination.

VLM_PROMPT_TEMPLATE = """You are a fraud analyst. You inspect a webpage screenshot and decide whether it visually impersonates a known brand from a fixed list. You do not hedge. You do not speculate beyond what is visible.

BRAND LIST (only score these — never invent a brand not on this list):
{brand_block}

SCORING SCALE (use these anchors):
  0.0 = no visual similarity to this brand
  0.5 = generic resemblance (similar colour family, no logo)
  0.8 = logo, wordmark, or layout clearly mimics the brand
  1.0 = pixel-level clone of an official page

For every brand in the list, return a match_score, a one-sentence rationale, and an evidence array. Evidence anchors must be concrete and grounded in the screenshot, e.g. "logo top-left matches AusPost red P mark", "colour palette dominated by CommBank yellow", "URL bar shows non-official domain". If match_score is 0.0, evidence should be an empty array and rationale should be "no visual indicators".

Return ONLY valid JSON matching this schema, no prose before or after:

{{
  "per_brand": [
    {{
      "brand": "<brand name exactly as in the list>",
      "match_score": <float 0.0–1.0>,
      "rationale": "<one sentence>",
      "evidence": ["<anchor 1>", "<anchor 2>"]
    }}
  ]
}}
"""


# ----------------------------------------------------------------------------
# Data shapes
# ----------------------------------------------------------------------------

@dataclass
class BrandRecord:
    """One entry from brands.json, plus the visual_hint added in Phase 6 prep."""
    brand: str
    aliases: list[str]
    domains: list[str]
    keywords: list[str]
    visual_hint: str = ""  # e.g. "red & white, stylised P logo"


@dataclass
class PerBrandResult:
    brand: str
    match_score: float
    rationale: str
    evidence: list[str] = field(default_factory=list)


@dataclass
class VisualBrandCheckResult:
    top_match: Optional[str]
    confidence: float
    per_brand: list[PerBrandResult]
    model: str
    latency_ms: int
    image_dims: tuple[int, int]


# ----------------------------------------------------------------------------
# Stubs
# ----------------------------------------------------------------------------

def _preprocess_screenshot(screenshot_path: str) -> tuple[bytes, tuple[int, int]]:
    """Load image from disk, downscale to TARGET_WIDTH x TARGET_HEIGHT, encode as JPEG q=85."""
    src = Image.open(screenshot_path)
    if src.width < MIN_INPUT_DIM_PX or src.height < MIN_INPUT_DIM_PX:
        raise ValueError(
            f"input dims too small: {src.width}x{src.height} < {MIN_INPUT_DIM_PX}px"
        )

    if src.mode != "RGB":
        src = src.convert("RGB")

    canvas = Image.new("RGB", (TARGET_WIDTH, TARGET_HEIGHT), (255, 255, 255))
    src_ratio = src.width / src.height
    target_ratio = TARGET_WIDTH / TARGET_HEIGHT
    if src_ratio > target_ratio:
        new_w = TARGET_WIDTH
        new_h = round(TARGET_WIDTH / src_ratio)
    else:
        new_h = TARGET_HEIGHT
        new_w = round(TARGET_HEIGHT * src_ratio)
    resized = src.resize((new_w, new_h), Image.LANCZOS)
    canvas.paste(resized, ((TARGET_WIDTH - new_w) // 2, (TARGET_HEIGHT - new_h) // 2))

    buf = io.BytesIO()
    canvas.save(buf, format="JPEG", quality=JPEG_QUALITY, optimize=True)
    data = buf.getvalue()
    if len(data) > MAX_OUTPUT_BYTES:
        raise ValueError(f"encoded JPEG {len(data)} bytes exceeds cap {MAX_OUTPUT_BYTES}")
    return data, (TARGET_WIDTH, TARGET_HEIGHT)


def _build_brand_block(brands: list[BrandRecord]) -> str:
    """Compress brand records to one line each for the prompt."""
    lines = []
    for b in brands:
        aliases = ", ".join(b.aliases) if b.aliases else b.brand
        domain = b.domains[0] if b.domains else ""
        hint = b.visual_hint or "no visual hint"
        lines.append(f"  - {b.brand} ({aliases}): {hint}; official domain {domain}")
    return "\n".join(lines)


def _call_ollama_vlm(image_bytes: bytes, prompt_text: str, timeout_s: float = 90.0) -> str:
    """Single-shot call to local Ollama with image + prompt. Returns raw response string."""
    payload = {
        "model": OLLAMA_MODEL_TAG,
        "prompt": prompt_text,
        "images": [base64.b64encode(image_bytes).decode("ascii")],
        "format": "json",
        "stream": False,
        "options": {
            "temperature": 0.1,
            "num_predict": 1024,
        },
    }
    with httpx.Client(timeout=timeout_s) as client:
        r = client.post(f"{OLLAMA_HOST}/api/generate", json=payload)
        r.raise_for_status()
        return r.json().get("response", "")


def _parse_vlm_response(raw: str, expected_brands: list[str]) -> list[PerBrandResult]:
    """Parse the JSON the VLM returned into typed results, in expected_brands order."""
    try:
        start = raw.find("{")
        end = raw.rfind("}") + 1
        if start == -1 or end == 0:
            raise ValueError("no JSON object found")
        parsed = json.loads(raw[start:end])
    except (json.JSONDecodeError, ValueError) as e:
        raise ValueError(f"VLM JSON parse failed: {e}; raw[:500]={raw[:500]!r}")

    by_brand: dict[str, dict] = {}
    for item in parsed.get("per_brand", []) or []:
        name = item.get("brand")
        if isinstance(name, str):
            by_brand[name.strip().lower()] = item

    results: list[PerBrandResult] = []
    for brand in expected_brands:
        item = by_brand.get(brand.strip().lower())
        if not item:
            results.append(PerBrandResult(brand=brand, match_score=0.0,
                                          rationale="no response", evidence=[]))
            continue
        try:
            score = max(0.0, min(1.0, float(item.get("match_score", 0.0))))
        except (TypeError, ValueError):
            score = 0.0
        evidence = item.get("evidence") or []
        if not isinstance(evidence, list):
            evidence = []
        evidence = [str(e) for e in evidence]
        results.append(PerBrandResult(
            brand=brand,
            match_score=score,
            rationale=str(item.get("rationale", "")) or "no rationale",
            evidence=evidence,
        ))
    return results


# ----------------------------------------------------------------------------
# Top-level orchestration
# ----------------------------------------------------------------------------

def check_brand_impersonation(
    screenshot_path: str,
    brands: list[dict],
) -> dict:
    """Run the full Phase 6 visual brand check.

    Args:
      screenshot_path: absolute path to screenshot.png from the Phase 5 crawler.
      brands: list of brand dicts as loaded from brands.json (must include the
              visual_hint field added in Phase 6 prep).

    Returns:
      A dict matching the schema in the design doc s.5:
        {
          "top_match": str | None,
          "confidence": float,
          "per_brand": [ {brand, match_score, rationale, evidence}, ... ],
          "model": str,
          "latency_ms": int,
          "image_dims": [w, h]
        }

    Failure modes:
      - Bad/missing screenshot → ValueError from _preprocess_screenshot, propagated.
      - Ollama unreachable → underlying httpx error from _call_ollama_vlm, propagated.
      - Unparseable VLM output → ValueError from _parse_vlm_response, propagated.

    The caller (URL-check orchestrator) is responsible for wrapping this in a timeout
    and combining the result with the static signals from Phase 4.
    """
    t0 = time.monotonic()

    typed_brands = [
        BrandRecord(
            brand=b["brand"],
            aliases=b.get("aliases", []),
            domains=b.get("domains", []),
            keywords=b.get("keywords", []),
            visual_hint=b.get("visual_hint", ""),
        )
        for b in brands
    ]
    image_bytes, dims = _preprocess_screenshot(screenshot_path)
    brand_block = _build_brand_block(typed_brands)
    prompt_text = VLM_PROMPT_TEMPLATE.format(brand_block=brand_block)
    raw = _call_ollama_vlm(image_bytes, prompt_text)

    expected = [b.brand for b in typed_brands]
    per_brand = _parse_vlm_response(raw, expected)

    top = max(per_brand, key=lambda r: r.match_score) if per_brand else None
    if top and top.match_score >= MIN_TOP_MATCH_SCORE:
        top_match: Optional[str] = top.brand
        confidence = top.match_score
    else:
        top_match = None
        confidence = top.match_score if top else 0.0

    elapsed_ms = int((time.monotonic() - t0) * 1000)

    return {
        "top_match": top_match,
        "confidence": confidence,
        "per_brand": [asdict(r) for r in per_brand],
        "model": OLLAMA_MODEL_TAG,
        "latency_ms": elapsed_ms,
        "image_dims": list(dims),
    }


# ----------------------------------------------------------------------------
# Convenience: brand loading
# ----------------------------------------------------------------------------

def load_brands(brands_json_path: str | Path) -> list[BrandRecord]:
    """Load brands.json into typed BrandRecord list."""
    with open(brands_json_path) as f:
        raw = json.load(f)
    return [
        BrandRecord(
            brand=item["brand"],
            aliases=item.get("aliases", []),
            domains=item.get("domains", []),
            keywords=item.get("keywords", []),
            visual_hint=item.get("visual_hint", ""),
        )
        for item in raw
    ]


if __name__ == "__main__":
    # Local smoke run is intentionally not implemented. Use the eval harness in
    # ~/scan-ai/eval/visual_brand/run_eval.py once both this module and the harness
    # are past skeleton stage.
    raise SystemExit("visual_brand_check.py is a skeleton; run via the eval harness.")
