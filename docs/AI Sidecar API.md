---
tags: [scan, sidecar, api, fastapi]
type: reference
created: 2026-05-06
---

# AI Sidecar API

FastAPI service in `scan-ai/main.py`. Six endpoints, all under `/v1`.
Five require a per-tester bearer token; one mints them with an admin
token. See [[Dependencies - Python Sidecar]] for the runtime stack and
[[Build & Run#AI sidecar]] for bring-up.

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET`  | `/v1/health` | none | Liveness probe — reports model name and whether Ollama is reachable. |
| `POST` | `/v1/classify` | tester bearer | Classify an SMS body as `SCAM` / `SUSPICIOUS` / `SAFE`. Used by [[Components - Receivers Services Workers#WorkManager workers\|SmsForwardWorker]]. |
| `POST` | `/v1/url-check` | tester bearer | URL safety analysis — Stage 1 always; Stage 2 (WHOIS/cert/DNS) when `deep=true`. |
| `POST` | `/v1/visual-brand-check` | tester bearer | Image-based brand-impersonation check (Pillow + VLM). |
| `POST` | `/v1/feedback` | tester bearer | Submit a correction (`original_verdict` → `correct_verdict`) for offline review (`feedback.jsonl`). |
| `POST` | `/v1/admin/token` | **admin bearer** (`ADMIN_TOKEN` env var) | Mint a per-tester token. Tokens persist in `tokens.json` (gitignored). |

## Schemas

### `/v1/classify`

```json
// Request
{ "text": "...", "sender": "+61400000000" }

// Response
{
  "verdict": "SCAM",                 // SCAM | SUSPICIOUS | SAFE
  "confidence": 0.93,                // 0.0 – 1.0
  "explanation": "...",
  "urls": ["https://example.com"]    // extracted from the SMS text
}
```

### `/v1/url-check`

```json
// Request
{ "url": "https://example.com", "deep": true }

// Response
{
  "url": "https://example.com",
  "verdict": "phishing",             // phishing | suspicious | clean
  "brand_match": "Westpac",
  "brand_confidence": 0.88,
  "signals": [
    { "type": "whois.age_days", "value": "3", "weight": 0.4 }
  ],
  "risk_score": 0.91
}
```

### `/v1/visual-brand-check`

```json
// Request — exactly one of screenshot_path, image_b64
{ "image_b64": "<base64 PNG>" }

// Response
{
  "top_match": "Commonwealth Bank",
  "confidence": 0.81,
  "per_brand": [
    { "brand": "Commonwealth Bank", "match_score": 0.81, "rationale": "...", "evidence": ["logo region", "..."] }
  ],
  "model": "llava:latest",
  "latency_ms": 1840,
  "image_dims": [1080, 2340]
}
```

### `/v1/feedback`

```json
// Request
{ "message_text": "...", "original_verdict": "SAFE", "correct_verdict": "SCAM" }
```

Appended to `feedback.jsonl` (gitignored) for offline review.

### `/v1/admin/token`

```json
// Request — empty body
{}

// Response
{ "token": "<per-tester bearer>" }
```

`Authorization: Bearer <ADMIN_TOKEN>` is required.

## Auth model

```
Admin token (ADMIN_TOKEN env)
        │
        ▼ /v1/admin/token
Per-tester bearer token (kept in tokens.json)
        │
        ▼ /v1/classify, /v1/url-check, /v1/visual-brand-check, /v1/feedback
```

`verify_token()` rejects missing/non-Bearer headers with `401`. Tokens
are opaque random strings — no JWT, no expiry today.

## Transport

- HTTP today on `0.0.0.0:8000`.
- TLS + cert-pinning between app and sidecar is **V1.4 item 2.2** —
  pin already in `BuildConfig.SCAN_AI_CERT_PIN`. See [[Build Config]].

## Related modules

- `scan-ai/url_analyzer.py` — Stage 1/2 URL signals.
- `scan-ai/visual_brand_check.py` — Pillow + VLM pipeline.
- `scan-ai/brands.json` — known brand list for matching.
