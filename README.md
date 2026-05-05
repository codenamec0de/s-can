<div align="center">

<img src="ScanLogo.png" alt="S'CAN" width="180"/>

# S'CAN

**Android privacy & security audit suite**

[![Version](https://img.shields.io/badge/version-1.4.2-1f6feb)](https://github.com/codenamec0de/s-can/releases)
[![Min SDK](https://img.shields.io/badge/min--sdk-26-3ddc84)](#requirements)
[![Target SDK](https://img.shields.io/badge/target--sdk-34-3ddc84)](#requirements)
[![Kotlin](https://img.shields.io/badge/kotlin-1.9-7f52ff)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-Proprietary-red)](LICENSE)
[![Status](https://img.shields.io/badge/status-V1.4.2%20stabilising-yellow)](#status)

University of Wollongong — final-year project

</div>

> **Private repository · All rights reserved.** Source is shared for backup
> and collaborator review only. No license is granted for use, copying,
> modification, or redistribution. See [LICENSE](LICENSE).

---

## Table of contents

- [What it does](#what-it-does)
- [Try it on your device](#try-it-on-your-device)
- [Repo layout](#repo-layout)
- [Building from source](#building-from-source)
- [Stack](#stack)
- [Requirements](#requirements)
- [Roadmap](#roadmap)
- [Status](#status)
- [Security](#security)

---

## What it does

S'CAN audits the apps installed on an Android device for privacy and
security risks. Active features in V1.4:

| Feature | What it checks |
|---|---|
| **Wi-Fi Security** | Auth type, PMF (802.11w), cipher, evil-twin clusters, captive-portal probing, DNS servers in use — flags MITM exposure on the connected network. |
| **SMS Scam Detection** | Forwards incoming SMS to a self-hosted AI sidecar (FastAPI + Ollama `qwen3.5`) that classifies messages as `SCAM` / `SUSPICIOUS` / `SAFE`, extracts URLs, and flags brand impersonation. |
| **Breach Checker** | Queries Have I Been Pwned for monitored email addresses; tracks resolution status and per-breach data-class exposure. |
| **App audit** | Inventories installed packages, surfaces tracker SDKs (Exodus dataset), and ranks dangerous permission combinations. |

---

## Try it on your device

The fastest path: grab the signed release APK from the
[Releases](https://github.com/codenamec0de/s-can/releases) page and sideload it.

1. On your Android device, enable **Settings → Apps → Special app access →
   Install unknown apps** for your browser of choice.
2. Download `app-release.apk` from the latest release.
3. Open the APK; Android will prompt you to install.
4. On first launch, sign in with Google and walk through the permission
   onboarding — SMS scanning is opt-in.

> Built against `minSdk 26` (Android 8.0+) / `targetSdk 34` (Android 14).

The SMS scam detector needs an instance of the AI sidecar running
somewhere reachable from the device — see
[Building the AI sidecar](#ai-sidecar-scan-ai) below.

---

## Repo layout

```
.
├── source/        # Android Studio project (Kotlin, Gradle, R8 release config)
├── scan-ai/       # Python FastAPI sidecar (SMS classifier + URL analyser)
├── ScanLogo.png   # App brand asset (master copy)
├── CHANGELOG.md   # Versioned change history
├── SECURITY.md    # Vulnerability disclosure policy
└── LICENSE        # Proprietary — All rights reserved
```

---

## Building from source

### Android app (`source/`)

Open in Android Studio. Debug builds work out of the box with Firebase auth
already configured. Release builds need a keystore at
`source/app/scan-release.jks` and a `source/keystore.properties` file with
`storeFile`, `storePassword`, `keyAlias`, `keyPassword` — both gitignored.

```bash
cd source
./gradlew :app:assembleDebug          # debug build (~16 MB, sideloadable)
./gradlew :app:assembleRelease        # signed, R8-shrunk release (~6 MB)
```

Output APKs land in `source/app/build/outputs/apk/{debug,release}/`.

### AI sidecar (`scan-ai/`)

```bash
cd scan-ai
python3 -m venv venv && source venv/bin/activate
pip install -r requirements.txt
ollama pull qwen3.5:latest                    # ~6 GB Q4_K_M
MODEL_NAME=qwen3.5:latest \
ADMIN_TOKEN=<your-admin-token> \
python main.py
```

Server binds `0.0.0.0:8000`. Mint per-tester tokens via
`POST /v1/admin/token` with `Authorization: Bearer <ADMIN_TOKEN>`.

---

## Stack

- **Android** — Kotlin · Material 3 · Room · Retrofit/OkHttp · WorkManager ·
  coroutines · Firebase Authentication · MPAndroidChart · Glide · Biometric.
- **Sidecar** — Python 3 · FastAPI · httpx · Pillow.
- **AI** — Ollama-hosted local LLM (`qwen3.5` 9.7B, Q4_K_M).
- **Build** — Gradle Kotlin DSL · R8 (full mode) · KSP for Room.

## Requirements

| | Min | Target |
|---|---|---|
| **Android SDK** | 26 (Android 8.0) | 34 (Android 14) |
| **JDK** | 1.8 | 1.8 |
| **Kotlin** | 1.9.x | — |
| **Gradle** | 8.x | — |

Sidecar: Python ≥ 3.10, Ollama ≥ 0.1.30, ~8 GB RAM free for the LLM.

---

## Roadmap

V1.5 and beyond — coming next:

- **Network Traffic Monitor** — passive capture of egress endpoints per app.
- **DNS Leak Detection** — check whether DNS queries follow the active VPN
  tunnel.
- **Terminator** — auto-revoke sensitive permissions when an app is
  minimised.

---

## Status

**V1.4.2 — preparing for stabilisation.** SMS pipeline and Wi-Fi analyser
are feature-complete; the V1.4 freeze list is down to two items:

- TLS + cert-pinning to the AI sidecar (V1.4 item 2.2)
- SMS onboarding disclosure (V1.4 item 2.1)

See [CHANGELOG.md](CHANGELOG.md) for the full release history.

---

## Security

Found a vulnerability? Please **don't** open a public issue — see
[SECURITY.md](SECURITY.md) for private reporting channels and scope.
