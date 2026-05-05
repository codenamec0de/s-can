---
tags: [scan, architecture, v1.4.2]
type: architecture
created: 2026-05-06
---

# Architecture

Two deployable artefacts: the **Android app** (`source/`) and the
**AI sidecar** (`scan-ai/`). Everything else (Firebase, HIBP, Exodus,
Ollama) is a third-party service the app or sidecar talks to.

## High-level

```mermaid
flowchart LR
    subgraph Device["Android device"]
        UI["Activities<br/>(Material 3)"]
        Room[("Room DB<br/>scan history,<br/>SMS verdicts,<br/>breaches")]
        Receivers["Receivers<br/>SMS / Boot /<br/>PackageInstall"]
        Service["ForegroundService<br/>ScanMonitorService"]
        Workers["WorkManager<br/>SmsForwardWorker<br/>PermissionMonitorWorker"]
        UI --> Room
        Receivers --> Workers
        Service --> Room
        Workers --> Room
    end

    subgraph Self["Self-hosted (user's box)"]
        Sidecar["FastAPI sidecar<br/>:8000"]
        Ollama["Ollama runtime<br/>qwen3.5:latest"]
        Sidecar --> Ollama
    end

    subgraph External["Third-party"]
        FB["Firebase Auth"]
        HIBP["Have I Been Pwned"]
        Exodus["Exodus trackers API"]
    end

    UI -->|Google sign-in| FB
    UI -->|email lookup| HIBP
    UI -->|tracker dataset| Exodus
    Workers -->|"POST /v1/sms/classify<br/>(cert-pinned, V1.4 item 2.2)"| Sidecar
```

## Module layout (Android)

```
com.uow.scan
├── (root)             Activities — every screen in the app
├── adapter            RecyclerView adapters
├── api                Retrofit services (HIBP, Scan-AI)
├── data               Room DB, DAOs, entities
├── model              Plain DTOs / domain types
├── receiver           Broadcast receivers (SMS, Boot, PackageInstall)
├── service            Foreground services
├── ui                 Reusable UI helpers
├── util               Heavy lifting: scanners, analyzers, generators
└── worker             WorkManager workers
```

See [[Activities & Screens]], [[Components - Receivers Services Workers]],
and [[Data Layer - Room]] for per-package detail.

## SMS scam detection — end-to-end

```mermaid
sequenceDiagram
    participant Tel as Android telephony
    participant Recv as SmsBroadcastReceiver
    participant Work as SmsForwardWorker
    participant Side as FastAPI sidecar
    participant LLM as Ollama qwen3.5
    participant DB as Room (SmsVerdict)
    participant UI as SmsScamActivity

    Tel->>Recv: SMS_RECEIVED broadcast
    Recv->>Work: enqueue (sender, body)
    Work->>Side: POST /v1/sms/classify (Bearer)
    Side->>LLM: prompt
    LLM-->>Side: SCAM / SUSPICIOUS / SAFE + reasons
    Side-->>Work: verdict JSON
    Work->>DB: insert SmsVerdictEntity
    UI->>DB: observe verdicts
    DB-->>UI: render
```

## Wi-Fi security analyser

`util/WifiSecurityAnalyzer.kt` reads the active connection via
`WifiManager` (auth type, cipher, PMF capability, BSSIDs, frequency)
and pairs it with the system DNS list to build a finding set. No
re-association — strictly read-only. See [[Permissions]] for why
`ACCESS_FINE_LOCATION` and `NEARBY_WIFI_DEVICES` are required.

## Breach checker

`util/BreachChecker.kt` orchestrates lookups via
`api/HIBPApiService.kt` (Retrofit). Results are persisted via
`BreachResultDao` so resolution status survives app restarts.

## Persistence boundaries

- **On-device only**: scan results, SMS verdicts, breach state,
  monitored apps, scheduled scans.
- **Off-device**: Firebase Auth tokens (Google), HIBP queries (email
  hash), Exodus tracker dataset (download), sidecar SMS classification
  (sender + body forwarded to **user's own** sidecar).

No telemetry. No third-party analytics SDKs.

See [[AI Sidecar API]] for what the sidecar accepts and returns.
