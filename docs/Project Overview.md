---
tags: [scan, overview, v1.4.2]
type: overview
created: 2026-05-06
---

# Project Overview

**S'CAN** is an Android privacy & security audit suite. It scans the apps
installed on a user's device, the Wi-Fi network they're connected to,
and the SMS messages they receive, then surfaces privacy and security
risks with concrete remediations.

Final-year project at the **University of Wollongong**, single-author
(Çağrı "Charlie" Alaf).

## What ships in V1.4

| Feature | What it does | Where it lives |
|---|---|---|
| **Wi-Fi Security Analyzer** | Audits the connected network for MITM exposure: auth type, PMF (802.11w), cipher, evil-twin clusters, captive-portal probing, DNS server enumeration. | `WifiSecurityActivity` + `util/WifiSecurityAnalyzer.kt` |
| **SMS Scam Detection** | Forwards inbound SMS to a self-hosted FastAPI sidecar running an Ollama-hosted LLM (`qwen3.5:latest`); receives `SCAM` / `SUSPICIOUS` / `SAFE` verdicts with URL and brand-impersonation context. | `SmsBroadcastReceiver` → `SmsForwardWorker` → sidecar → `SmsScamActivity` |
| **Breach Checker** | Queries Have I Been Pwned for monitored email addresses; tracks resolution and per-breach data classes. | `BreachCheckerActivity` + `util/BreachChecker.kt` + `api/HIBPApiService.kt` |
| **App audit** | Inventories installed packages, surfaces Exodus tracker SDKs, and ranks dangerous permission combinations. | `MainActivity`, `ScanActivity`, `AppDetailActivity`, `util/AppScanner.kt`, `util/LocalTrackerScanner.kt` |
| **Reports** | PDF / JSON / CSV exports of scan results with history and side-by-side comparison. | `ExportReportActivity`, `ReportHistoryActivity`, `ScanComparisonActivity`, `util/PdfReportGenerator.kt` & friends |
| **Background monitoring** | Foreground service tracks data usage; WorkManager periodically re-checks permissions; boot receiver restarts everything after reboot. | `service/ScanMonitorService.kt`, `worker/PermissionMonitorWorker.kt`, `receiver/BootReceiver.kt` |

## What's deferred

- **TLS + cert-pinning to the AI sidecar** (V1.4 item 2.2). Pin is
  baked in (`BuildConfig.SCAN_AI_CERT_PIN`), OkHttp `CertificatePinner`
  wiring is the next hop.
- **SMS onboarding disclosure** (V1.4 item 2.1).
- **Network Traffic Monitor**, **DNS Leak Detection**, **Terminator**
  auto-revoke — V1.5+.

See [[Roadmap & Status]] for the full picture.

## Tech in one sentence

Kotlin Android app (Material 3, Room, Retrofit/OkHttp, WorkManager,
Firebase Auth) talking to a Python FastAPI sidecar that fronts a local
Ollama LLM. See [[Architecture]] for the diagram.
