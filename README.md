# S'CAN

Android privacy & security audit suite. Final-year project, University of Wollongong.

> **Private repository — All rights reserved.** Source is shared for backup and
> collaborator review only. No license is granted for use, copying, modification,
> or redistribution. See `LICENSE` for full terms.

---

## What it does

S'CAN audits the apps installed on an Android device for privacy and security
risks. Active features in V1.4:

- **Wi-Fi Security** — Analyses the connected network for MITM exposure: auth
  type, PMF (802.11w), cipher, evil-twin clusters, captive-portal checks, DNS
  servers in use.
- **SMS Scam Detection** — Forwards incoming SMS to a self-hosted AI sidecar
  (FastAPI + Ollama qwen3.5) that classifies messages as `SCAM` /
  `SUSPICIOUS` / `SAFE`, extracts URLs, and flags brand impersonation.
- **Breach Checker** — Queries Have I Been Pwned for monitored email
  addresses; tracks resolution status and per-breach data exposure.

Coming soon (V1.5+): Network Traffic Monitor, DNS Leak Detection,
Terminator (auto-revoke permissions on app minimise).

## Repo layout

```
.
├── source/        # Android Studio project (Kotlin, Gradle, R8 release config)
├── scan-ai/       # Python FastAPI sidecar (SMS classifier + URL analyser)
├── ScanLogo.png   # App brand asset (master copy)
└── LICENSE        # Proprietary — All rights reserved
```

## Building

### Android app (`source/`)

Open in Android Studio. Debug builds work out of the box with Firebase auth
already configured. Release builds need a keystore at
`source/app/scan-release.jks` and a `source/keystore.properties` file with
`storeFile`, `storePassword`, `keyAlias`, `keyPassword` — both gitignored.

```bash
cd source
./gradlew :app:assembleDebug          # debug build
./gradlew :app:assembleRelease        # signed, R8-obfuscated release
```

### AI sidecar (`scan-ai/`)

```bash
cd scan-ai
python3 -m venv venv && source venv/bin/activate
pip install -r requirements.txt
# Pull a classifier model:
ollama pull qwen3.5:latest
MODEL_NAME=qwen3.5:latest ADMIN_TOKEN=<your-admin-token> python main.py
```

Server binds `0.0.0.0:8000`. Mint per-tester tokens via
`POST /v1/admin/token` with `Authorization: Bearer <ADMIN_TOKEN>`.

## Stack

- **Android** — Kotlin, Material 3, Room, Retrofit/OkHttp, WorkManager,
  coroutines, Firebase Authentication.
- **Sidecar** — Python 3, FastAPI, httpx, Pillow.
- **AI** — Ollama-hosted local LLM (qwen3.5 9.7B, Q4_K_M).

## Status

V1.4.2 — preparing for stabilisation. SMS pipeline and Wi-Fi analyser are
feature-complete; TLS + cert-pinning to the AI sidecar (V1.4 item 2.2) and
SMS onboarding disclosure (V1.4 item 2.1) remain on the freeze list.
