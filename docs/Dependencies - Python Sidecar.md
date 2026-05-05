---
tags: [scan, dependencies, python, sidecar]
type: reference
created: 2026-05-06
---

# Dependencies — Python Sidecar

From `scan-ai/requirements.txt`. Eight direct deps, all pinned to a
minimum version with `>=`. Sidecar is a FastAPI service that fronts an
**Ollama**-hosted LLM — Ollama itself is a system dependency, not a pip
package.

| Dependency | Floor | Why |
|---|---|---|
| `fastapi` | ≥ 0.115.0 | Async web framework — every endpoint in [[AI Sidecar API]]. |
| `uvicorn[standard]` | ≥ 0.30.0 | ASGI server. `[standard]` brings `httptools`, `uvloop`, websockets. |
| `httpx` | ≥ 0.27.0 | Async HTTP client — used to talk to Ollama and to fetch URLs for analysis. |
| `pydantic` | ≥ 2.9.0 | Request/response models, validation. |
| `tldextract` | ≥ 5.0 | Splits a URL into subdomain / registrable domain / TLD without DNS. Used in URL analysis for brand-impersonation checks. |
| `python-whois` | ≥ 0.9 | Domain WHOIS lookups (creation date, registrar) — a strong signal for newly-registered scam domains. |
| `dnspython` | ≥ 2.4 | DNS resolution for sidecar-side URL checks (MX, A, AAAA). |
| `Pillow` | ≥ 10.0 | Image preprocessing for the visual brand-check pipeline (`visual_brand_check.py`). |

## System dependencies (not in `requirements.txt`)

| What | Version | Why |
|---|---|---|
| Python | ≥ 3.10 | Pydantic v2 + FastAPI feature floor. |
| **Ollama** runtime | ≥ 0.1.30 | Hosts the local LLM. Started independently of the sidecar. |
| `qwen3.5:latest` | (Q4_K_M, ~6 GB) | Default classifier model. Pulled with `ollama pull qwen3.5:latest`. |
| RAM | ≥ 8 GB free | LLM working set. |

## How they fit together

```
┌──────────────┐     httpx       ┌─────────────┐
│ Android app  │ ──────────────▶ │ FastAPI     │
└──────────────┘                 │ sidecar     │
                                 │ (uvicorn)   │
                                 └──────┬──────┘
                                        │ httpx
                                        ▼
                                 ┌─────────────┐
                                 │ Ollama HTTP │ ──▶ qwen3.5
                                 │ :11434      │
                                 └─────────────┘
```

## At a glance

8 pip deps, 1 system dep (Ollama). No database — the sidecar is
stateless aside from the per-tester token store
(`tokens.json`, gitignored). No background workers — single
`uvicorn` process binds `0.0.0.0:8000`.

See [[AI Sidecar API]] for endpoints and auth, [[Build & Run]] for
bring-up steps.
