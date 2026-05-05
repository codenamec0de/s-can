---
tags: [scan, dependencies, external-services]
type: reference
created: 2026-05-06
---

# Dependencies — External Services

Third-party services that S'CAN talks to but doesn't host. Each has a
narrow surface area; nothing is load-bearing for offline operation
except Firebase Auth (sign-in is required to enter the app).

| Service | Used by | What for | Auth |
|---|---|---|---|
| **Firebase Authentication** | Android app | Google sign-in, account linking, password reset. | `google-services.json` baked into the APK; per-user ID tokens. |
| **Have I Been Pwned (HIBP)** | Android app | Email breach lookup (`api/HIBPApiService.kt`). | API key — currently configured in code (rotate before any public release). |
| **Exodus Privacy API** | Android app | Tracker SDK dataset (`assets/trackers.json` is the offline cache; live API can refresh). | Public, unauthenticated. |
| **Ollama runtime** | Sidecar | Hosts `qwen3.5:latest`; serves classification at `:11434`. | Localhost-only by default. |
| **Self-hosted FastAPI sidecar** | Android app | SMS classification, URL analysis. | Per-tester bearer token minted by the admin endpoint. |

## Notes per service

### Firebase

- BoM 32.7.0 pins `firebase-auth-ktx`. See [[Dependencies - Android]].
- `google-services.json` is gitignored — local file only. Re-download
  from the Firebase console if reset.

### HIBP

- `api/HIBPApiService.kt` defines the Retrofit interface.
- Rate limit applies; backoff is up to the caller in `BreachChecker`.
- All queries are by canonical email (no truncated-hash flow today).

### Exodus

- The tracker dataset is shipped as `assets/trackers.json` so a fresh
  install works offline.
- `util/TrackerRepository.kt` mediates between the bundled snapshot and
  live refresh.

### Ollama

- Started outside the sidecar process. The sidecar talks to it over
  HTTP at `http://127.0.0.1:11434`.
- Model name comes from the `MODEL_NAME` env var passed to the
  sidecar — defaults to `qwen3.5:latest` post-2026-04-30 reformat.

### Self-hosted FastAPI sidecar

- Tester tokens minted via `POST /v1/admin/token` with the admin
  bearer (`ADMIN_TOKEN` env var).
- Tokens persist in `tokens.json`. Gitignored.
- TLS + cert-pinning to this service is V1.4 item 2.2 — pin baked into
  `BuildConfig.SCAN_AI_CERT_PIN`. See [[Build Config]].

See [[AI Sidecar API]] for endpoints, [[Architecture]] for the data
flow diagram.
