"""
S'CAN AI Server — SMS Scam Classification
FastAPI + Ollama (Llama 3.1 8B)
"""
import os
import asyncio
import base64
import json
import secrets
import logging
import tempfile
from datetime import datetime
from typing import Optional

from fastapi import FastAPI, HTTPException, Header, Depends
from pydantic import BaseModel
import httpx

from url_analyzer import extract_urls, analyze_static, analyze_domain
from visual_brand_check import check_brand_impersonation

app = FastAPI(title="S'CAN AI Server", version="1.0.0")

# ── Logging (console + file) ──────────────────────────────────────────
LOG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "scan-ai.log")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-5s  %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler(LOG_FILE),
    ],
)
logger = logging.getLogger("scan-ai")


@app.on_event("startup")
async def _log_startup():
    logger.info("=" * 60)
    logger.info("S'CAN AI Server starting  model=%s  ollama=%s", MODEL_NAME, OLLAMA_BASE)
    logger.info("Log file: %s", LOG_FILE)
    logger.info("=" * 60)


from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request as StarletteRequest
import time as _time_mod


class RequestLogMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: StarletteRequest, call_next):
        start = _time_mod.monotonic()
        response = await call_next(request)
        elapsed = round((_time_mod.monotonic() - start) * 1000)
        logger.info(
            "HTTP  %s %s  %d  %dms  client=%s",
            request.method, request.url.path, response.status_code,
            elapsed, request.client.host if request.client else "?",
        )
        return response


app.add_middleware(RequestLogMiddleware)

# ── Configuration ──────────────────────────────────────────────────────
OLLAMA_BASE = os.getenv("OLLAMA_BASE", "http://localhost:11434")
MODEL_NAME = os.getenv("MODEL_NAME", "llama3.1:8b")
ADMIN_TOKEN = os.getenv("ADMIN_TOKEN", "scan-admin-secret")

# ── Token store (file-backed) ─────────────────────────────────────────
TOKENS_FILE = os.path.join(os.path.dirname(__file__), "tokens.json")
VALID_TOKENS: set[str] = set()


def _load_tokens():
    if os.path.exists(TOKENS_FILE):
        with open(TOKENS_FILE) as f:
            VALID_TOKENS.update(json.load(f))


def _save_tokens():
    with open(TOKENS_FILE, "w") as f:
        json.dump(sorted(VALID_TOKENS), f, indent=2)


_load_tokens()

# ── Models ─────────────────────────────────────────────────────────────


class ClassifyRequest(BaseModel):
    text: str
    sender: Optional[str] = None


class ClassifyResponse(BaseModel):
    verdict: str        # SCAM | SUSPICIOUS | SAFE
    confidence: float   # 0.0 – 1.0
    explanation: str
    urls: list[str] = []  # URLs extracted from the SMS text


class UrlCheckRequest(BaseModel):
    url: str
    deep: bool = True   # if True, run Stage 2 (WHOIS/cert/DNS)


class UrlSignal(BaseModel):
    type: str
    value: str
    weight: float


class UrlCheckResponse(BaseModel):
    url: str
    verdict: str            # phishing | suspicious | clean
    brand_match: Optional[str] = None
    brand_confidence: float = 0.0
    signals: list[UrlSignal] = []
    risk_score: float = 0.0


class FeedbackRequest(BaseModel):
    message_text: str
    original_verdict: str
    correct_verdict: str


class TokenResponse(BaseModel):
    token: str


class HealthResponse(BaseModel):
    status: str
    model: str
    ollama_ok: bool


class VisualBrandCheckRequest(BaseModel):
    screenshot_path: Optional[str] = None
    image_b64: Optional[str] = None


class VisualBrandCheckPerBrand(BaseModel):
    brand: str
    match_score: float
    rationale: str
    evidence: list[str] = []


class VisualBrandCheckResponse(BaseModel):
    top_match: Optional[str] = None
    confidence: float
    per_brand: list[VisualBrandCheckPerBrand]
    model: str
    latency_ms: int
    image_dims: list[int]


# ── Auth ───────────────────────────────────────────────────────────────

async def verify_token(authorization: str = Header(...)):
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "Invalid authorization header")
    token = authorization.removeprefix("Bearer ")
    if token not in VALID_TOKENS and token != ADMIN_TOKEN:
        raise HTTPException(401, "Invalid token")
    return token


# ── System prompt ──────────────────────────────────────────────────────

SYSTEM_PROMPT = """You are an SMS classifier. Your job is to decide whether a message is a SCAM, SUSPICIOUS, or SAFE.

IMPORTANT RULES:
- The vast majority of SMS messages are SAFE. Normal conversations, greetings, plans, jokes, personal messages between friends, family, or partners are always SAFE.
- Only mark as SCAM if the message contains clear phishing indicators: fake URLs, urgent payment demands, fake prize claims, or impersonation of known organisations with wrong domains.
- Only mark as SUSPICIOUS if there are mild red flags but nothing definitive.
- When in doubt, default to SAFE. Do NOT over-flag.

A message is SCAM only if it has TWO OR MORE of these red flags:
1. Contains a URL with a suspicious/fake domain (e.g. auspost-delivery.xyz instead of auspost.com.au)
2. Demands urgent payment or threatens penalties
3. Impersonates a known brand/government agency
4. Asks for personal info, passwords, or bank details
5. Uses urgency tactics ("act now", "account suspended", "last chance")

A message is SAFE if it is:
- A normal personal conversation (hi, hello, plans, how are you, etc.)
- An appointment reminder from a business
- A delivery status update with NO payment links
- An OTP/verification code from a service the user signed up for
- Any casual, friendly, or everyday message

Respond ONLY with a JSON object (no markdown, no extra text):
{"verdict": "SCAM|SUSPICIOUS|SAFE", "confidence": 0.0-1.0, "explanation": "brief reason"}

Examples:

SMS: "Hi babe, are we meeting tonight?"
{"verdict": "SAFE", "confidence": 0.99, "explanation": "Normal personal message between people who know each other"}

SMS: "Hey mate, running 10 mins late. See you at the pub!"
{"verdict": "SAFE", "confidence": 0.99, "explanation": "Casual message from a friend about meeting plans"}

SMS: "Mum can you pick me up from uni at 4?"
{"verdict": "SAFE", "confidence": 0.99, "explanation": "Normal family message requesting a pickup"}

SMS: "Your Uber is arriving now. White Toyota Camry ABC123."
{"verdict": "SAFE", "confidence": 0.98, "explanation": "Standard ride-hailing notification with specific vehicle details"}

SMS: "Your Coles order #4521 is ready for pickup at Wollongong store."
{"verdict": "SAFE", "confidence": 0.95, "explanation": "Legitimate order pickup notification with no links or payment requests"}

SMS: "Reminder: Dentist appointment tomorrow at 2:30 PM. Reply YES to confirm."
{"verdict": "SAFE", "confidence": 0.96, "explanation": "Standard appointment reminder with no suspicious elements"}

SMS: "AusPost: Your parcel is waiting. Pay $2 redelivery fee: http://auspost-delivery.xyz/pay"
{"verdict": "SCAM", "confidence": 0.97, "explanation": "Fake AusPost delivery scam. Suspicious URL domain does not match official auspost.com.au, and demands payment"}

SMS: "ANZ: Unusual activity on your account. Verify now: http://anz-secure.net/verify"
{"verdict": "SCAM", "confidence": 0.96, "explanation": "Bank phishing. ANZ uses anz.com.au, not anz-secure.net. Banks never send verification links via SMS"}

SMS: "Hi mum I dropped my phone. This is my new number. Can you transfer $500 urgently?"
{"verdict": "SCAM", "confidence": 0.94, "explanation": "Classic Hi Mum scam. Claims new number and urgently requests money transfer"}

SMS: "Linkt: Unpaid toll $4.90. Pay now to avoid $165 fine: http://linkt-pay.net/overdue"
{"verdict": "SCAM", "confidence": 0.95, "explanation": "Fake toll payment scam. Linkt uses linkt.com.au, not linkt-pay.net. Threatens fine to create urgency"}

Now classify this SMS message:"""


# ── Endpoints ──────────────────────────────────────────────────────────

@app.get("/v1/health", response_model=HealthResponse)
async def health():
    ollama_ok = False
    try:
        async with httpx.AsyncClient() as client:
            r = await client.get(f"{OLLAMA_BASE}/api/tags", timeout=5)
            ollama_ok = r.status_code == 200
    except Exception:
        pass
    return HealthResponse(
        status="ok" if ollama_ok else "degraded",
        model=MODEL_NAME,
        ollama_ok=ollama_ok,
    )


@app.post("/v1/classify", response_model=ClassifyResponse,
          dependencies=[Depends(verify_token)])
async def classify(req: ClassifyRequest):
    if not req.text.strip():
        raise HTTPException(400, "Empty message text")

    import time as _time
    _start = _time.monotonic()
    logger.info("CLASSIFY  from=%s  text=%s", req.sender or "?", req.text[:80])

    user_msg = req.text
    if req.sender:
        user_msg = f"From: {req.sender}\nMessage: {req.text}"

    try:
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{OLLAMA_BASE}/api/generate",
                json={
                    "model": MODEL_NAME,
                    "system": SYSTEM_PROMPT,
                    "prompt": user_msg,
                    "stream": False,
                    "think": False,
                    "options": {
                        "temperature": 0.1,
                        "num_predict": 512,
                    },
                },
                timeout=120,
            )
            response.raise_for_status()
    except httpx.HTTPError as e:
        logger.error("Ollama request failed: %s", e)
        raise HTTPException(502, f"Ollama error: {e}")

    raw = response.json().get("response", "")
    result = _parse_ai_response(raw)

    # Extract URLs from the original SMS text
    urls = extract_urls(req.text)

    _elapsed = round((_time.monotonic() - _start) * 1000)
    logger.info(
        "VERDICT   %s  conf=%.2f  elapsed=%dms  urls=%d  expl=%s",
        result.verdict, result.confidence, _elapsed, len(urls),
        result.explanation[:80],
    )
    return ClassifyResponse(
        verdict=result.verdict,
        confidence=result.confidence,
        explanation=result.explanation,
        urls=urls,
    )


@app.post("/v1/url-check", response_model=UrlCheckResponse,
          dependencies=[Depends(verify_token)])
async def url_check(req: UrlCheckRequest):
    import time as _time
    _start = _time.monotonic()
    logger.info("URL-CHECK  url=%s  deep=%s", req.url[:80], req.deep)

    # Stage 1: Static analysis (instant)
    result = analyze_static(req.url)

    # Stage 2: Domain/cert analysis (if requested and not already safe)
    if req.deep and result["verdict"] != "safe":
        result = await analyze_domain(req.url, result)

    _elapsed = round((_time.monotonic() - _start) * 1000)
    logger.info(
        "URL-VERDICT  %s  risk=%.3f  brand=%s  elapsed=%dms",
        result["verdict"], result["risk_score"],
        result.get("brand_match") or "none", _elapsed,
    )

    return UrlCheckResponse(
        url=result["url"],
        verdict=result["verdict"],
        brand_match=result.get("brand_match"),
        brand_confidence=result.get("brand_confidence", 0.0),
        signals=[UrlSignal(**s) for s in result["signals"]],
        risk_score=result["risk_score"],
    )


_GPU_SEMAPHORE = asyncio.Semaphore(1)
_BRANDS_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "brands.json")


@app.post("/v1/visual-brand-check", response_model=VisualBrandCheckResponse,
          dependencies=[Depends(verify_token)])
async def visual_brand_check_endpoint(req: VisualBrandCheckRequest):
    if not req.screenshot_path and not req.image_b64:
        raise HTTPException(400, "Provide screenshot_path or image_b64")

    import time as _time
    _start = _time.monotonic()

    tmp_path: Optional[str] = None
    if req.image_b64:
        try:
            data = base64.b64decode(req.image_b64, validate=True)
        except Exception:
            raise HTTPException(400, "Invalid base64 image")
        tf = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
        tf.write(data)
        tf.close()
        tmp_path = tf.name
        path = tmp_path
    else:
        path = req.screenshot_path
        if not os.path.exists(path):
            raise HTTPException(400, f"screenshot_path not found: {path}")

    try:
        with open(_BRANDS_PATH) as f:
            brands = json.load(f)
    except (OSError, json.JSONDecodeError) as e:
        raise HTTPException(500, f"brands.json load error: {e}")

    logger.info("VBC start  path=%s  brands=%d", os.path.basename(path), len(brands))
    try:
        async with _GPU_SEMAPHORE:
            result = await asyncio.to_thread(check_brand_impersonation, path, brands)
    except ValueError as e:
        raise HTTPException(422, f"VLM pipeline error: {e}")
    except httpx.HTTPError as e:
        raise HTTPException(502, f"Ollama error: {e}")
    finally:
        if tmp_path and os.path.exists(tmp_path):
            try:
                os.unlink(tmp_path)
            except OSError:
                pass

    _elapsed = round((_time.monotonic() - _start) * 1000)
    logger.info(
        "VBC done   top=%s  conf=%.2f  inf=%dms  total=%dms",
        result.get("top_match") or "none", result["confidence"],
        result["latency_ms"], _elapsed,
    )
    return result


@app.post("/v1/feedback", dependencies=[Depends(verify_token)])
async def feedback(req: FeedbackRequest):
    logger.info(
        "Feedback: %s -> %s | %s",
        req.original_verdict, req.correct_verdict, req.message_text[:80],
    )
    feedback_file = os.path.join(os.path.dirname(__file__), "feedback.jsonl")
    with open(feedback_file, "a") as f:
        f.write(json.dumps({
            "timestamp": datetime.now().isoformat(),
            "text": req.message_text,
            "original": req.original_verdict,
            "correct": req.correct_verdict,
        }) + "\n")
    return {"status": "recorded"}


@app.post("/v1/admin/token", response_model=TokenResponse)
async def create_token(authorization: str = Header(...)):
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "Invalid authorization header")
    token = authorization.removeprefix("Bearer ")
    if token != ADMIN_TOKEN:
        raise HTTPException(403, "Admin token required")

    new_token = secrets.token_urlsafe(32)
    VALID_TOKENS.add(new_token)
    _save_tokens()
    logger.info("New tester token created: %s…", new_token[:8])
    return TokenResponse(token=new_token)


# ── Helpers ────────────────────────────────────────────────────────────

def _parse_ai_response(raw: str) -> ClassifyResponse:
    try:
        start = raw.find("{")
        end = raw.rfind("}") + 1
        if start == -1 or end == 0:
            raise ValueError("No JSON found in AI output")
        parsed = json.loads(raw[start:end])

        verdict = parsed.get("verdict", "SUSPICIOUS").upper()
        if verdict not in ("SCAM", "SUSPICIOUS", "SAFE"):
            verdict = "SUSPICIOUS"

        confidence = max(0.0, min(1.0, float(parsed.get("confidence", 0.5))))
        explanation = str(parsed.get("explanation", "Unable to determine"))

        return ClassifyResponse(
            verdict=verdict, confidence=confidence, explanation=explanation
        )
    except (json.JSONDecodeError, ValueError, KeyError):
        raw_upper = raw.upper()
        if "SCAM" in raw_upper:
            verdict = "SCAM"
        elif "SUSPICIOUS" in raw_upper:
            verdict = "SUSPICIOUS"
        else:
            verdict = "SAFE"
        return ClassifyResponse(
            verdict=verdict,
            confidence=0.5,
            explanation=raw[:200] if raw else "Could not parse AI response",
        )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
