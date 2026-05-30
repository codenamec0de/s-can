<div align="center">

<img src="ScanLogo.png" alt="S'CAN" width="180"/>

# S'CAN

**Android privacy & security audit suite**

[![Version](https://img.shields.io/badge/version-1.4.6-1f6feb)](https://github.com/codenamec0de/s-can/releases)
[![Min SDK](https://img.shields.io/badge/min--sdk-26-3ddc84)](#requirements)
[![Target SDK](https://img.shields.io/badge/target--sdk-34-3ddc84)](#requirements)
[![Kotlin](https://img.shields.io/badge/kotlin-1.9-7f52ff)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-Proprietary-red)](LICENSE)
[![Status](https://img.shields.io/badge/status-V1.4.6%20demo--ready-3ddc84)](#status)

University of Wollongong — final-year project

</div>

> **Private repository · All rights reserved.** All source code, design,
> branding, ideas, and concepts in this repository are the intellectual
> property of Çağrı (Charlie) Alaf. The project is shared for backup and
> collaborator review only — no license is granted for use, copying,
> modification, redistribution, or derivative works. See [LICENSE](LICENSE)
> and the [Intellectual property](#intellectual-property) section below.

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
- [Intellectual property](#intellectual-property)

---

## What it does

S'CAN audits the apps installed on an Android device for privacy and
security risks. Active features as of V1.4.6:

| Feature | What it checks |
|---|---|
| **Wi-Fi Security** | Auth type, PMF (802.11w), cipher, evil-twin clusters, captive-portal probing, DNS servers in use — flags MITM exposure on the connected network. Now also lists **every nearby network** (sortable by signal/risk) with a per-network detail screen, offline IEEE-OUI vendor lookup, and a trusted-network allowlist; passive and read-only. |
| **DNS Leak Detection (Beta)** | Answers "is my browsing private right now?" — inspects the active DNS resolver, Private DNS (DoT) and VPN state and grades the network **PRIVATE / PARTIAL / EXPOSED**, entirely on-device (no DNS queries leave the phone). |
| **SMS Scam Detection** | Classifies incoming SMS as `SCAM` / `SUSPICIOUS` / `SAFE` — **on-device by default** (nothing leaves the phone), with an optional self-hosted AI sidecar (FastAPI + Ollama) and automatic fail-over back to the on-device classifier. Extracts URLs and flags brand impersonation. |
| **Breach Checker** | Queries Have I Been Pwned for monitored email addresses; tracks resolution status and per-breach data-class exposure. |
| **App audit** | Inventories installed packages, surfaces tracker SDKs (Exodus dataset), and rates each app's risk — **behaviour-gated**, so an app is flagged HIGH only when a real finding is observed (a background sensor access or an integrity issue), not merely for the permissions it holds. The activity timeline shows the real observed sensor access (what, how long, when, foreground vs background). |
| **Background monitor** | A foreground service watches user-selected apps for background sensor/data activity and raises plain-language alerts scored against each app's own 7-day baseline. |

---

## Try it on your device

The fastest path: grab the signed release APK from the
[Releases](https://github.com/codenamec0de/s-can/releases) page and sideload it.

1. On your Android device, enable **Settings → Apps → Special app access →
   Install unknown apps** for your browser of choice.
2. Download the APK attached to the latest release (e.g. `scan-v1.4.6.apk`).
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
├── docs/          # V1.4 project knowledge vault — open as Obsidian vault
├── ScanLogo.png   # App brand asset (master copy)
├── CHANGELOG.md   # Versioned change history
├── SECURITY.md    # Vulnerability disclosure policy
└── LICENSE        # Proprietary — All rights reserved
```

**Documentation:** `docs/` is an [Obsidian](https://obsidian.md) vault
covering the architecture, dependencies, build config, components, data
layer, sidecar API, and roadmap for V1.4. Start at
[`docs/Index.md`](docs/Index.md). Renders fine on GitHub too — no
Obsidian required for read-only browsing.

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

The Breach Checker reads its Have I Been Pwned API key from `HIBP_API_KEY` in
`source/local.properties` (gitignored), injected into `BuildConfig` at build time —
it is never committed to source. Without it, the breach screen simply prompts for
a key.

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

**V1.4.6 — demo-ready.** A capability release building on the V1.4.5 reliability
work. Adds **DNS Leak Detection (Beta)** (on-device PRIVATE/PARTIAL/EXPOSED
grading of the current network), a much deeper **Wi-Fi Security** area (every
nearby network with per-network detail, evil-twin detection, offline OUI vendor
lookup, and a trusted-network allowlist — passive and read-only), and sharper SMS
scam detection (seven cycling sample scams, a clear-verdicts action, and two new
on-device fallback patterns). V1.4.5's foundations remain: behaviour-gated app
risk (HIGH only on a real finding), on-device SMS by default, a shared dark-themed
dialog, and a global crash handler with lifecycle-safe screens.

See [CHANGELOG.md](CHANGELOG.md) for the full release history.

---

## Security

Found a vulnerability? Please **don't** open a public issue — see
[SECURITY.md](SECURITY.md) for private reporting channels and scope.

---

## Intellectual property

S'CAN is an original work by **Çağrı (Charlie) Alaf** (GitHub
[@codenamec0de](https://github.com/codenamec0de)), produced as a final-year
project at the University of Wollongong. **All code, design, branding,
ideas, concepts, architecture, algorithms, heuristics, prompts, threat
models, and accompanying documentation in this repository are the
exclusive intellectual property of the author.**

This repository is shared in private form solely for backup, academic
portfolio review, and collaborator inspection. Nothing here is
open-source. In particular, **without prior written permission from the
author**:

- The source code, in whole or in part, may not be copied, modified,
  redistributed, sublicensed, sold, or used in any derivative work or
  product.
- The project's name, branding (including the **S'CAN** mark and the
  scan logo), product design, screen flows, and visual identity may not
  be reused or imitated.
- The ideas, concepts, methodologies, and research outputs (including
  the SMS scam-classification pipeline, the Wi-Fi MITM analysis logic,
  and the breach-tracking model) may not be repackaged, commercialised,
  or claimed as another party's work.
- The contents of this repository may not be scraped, mirrored, or
  ingested into machine-learning training datasets.

Quoting or citing the project for non-commercial academic purposes is
welcome with proper attribution to **Çağrı (Charlie) Alaf — University of
Wollongong, 2026**.

For licensing inquiries, written-permission requests, or any commercial
use, contact the author via GitHub. See [LICENSE](LICENSE) for full terms.
