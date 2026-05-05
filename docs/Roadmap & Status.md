---
tags: [scan, roadmap, status, v1.4]
type: planning
created: 2026-05-06
---

# Roadmap & Status

Where V1.4.2 sits and what comes next.

## V1.4 scope freeze

Frozen on **2026-04-30**. Only two SMS-related items remain inside
V1.4 — everything else moved to V1.5+.

| Item | Status | Notes |
|---|---|---|
| **2.2** TLS + cert-pinning to AI sidecar | open, in-flight | Pin already in `BuildConfig.SCAN_AI_CERT_PIN` ([[Build Config#BuildConfig fields]]). Next: install OkHttp `CertificatePinner` in the `ScanAiClient`. |
| **2.1** SMS onboarding disclosure | open, blocked behind 2.2 | Order: **2.2 → stabilisation → 2.1**. UI scaffold exists at `SmsOnboardingActivity`; copy is the gating piece. |

## V1.4.2 GitHub Release

- Tag: `v1.4.2`
- URL: <https://github.com/codenamec0de/s-can/releases/tag/v1.4.2>
- Asset: `scan-v1.4.2-release.apk` (~6 MB, signed, R8-shrunk).
- SHA-256:
  `55a50c0537d8f91ebf275ac7fb07e4008ec0b136bd99777c771365bae9c5bfb1`
- Install steps: see [[Build & Run#Tester sideload (no source needed)]].

## V1.5+ — deferred from V1.4

| Item | Notes |
|---|---|
| **Network Traffic Monitor** | Passive capture of egress endpoints per app. Likely VpnService-based. |
| **DNS Leak Detection** | Verify DNS queries follow the active VPN tunnel; surface leaks. |
| **Terminator** | Auto-revoke sensitive permissions on app minimise. UI scaffolding exists ([[Activities & Screens#TerminatorActivity]]); engine is `util/TerminatorEngine.kt` (logic-incomplete). |
| **A3 crawler / honeypot** | Prep work paused after the 2026-04-29 Mullvad-WG / Docker iptables incident. Host-topology decision still pending. |
| **SQLCipher for Room** | Roadmap item if shipped to non-trusted devices. |
| **Inbox-bootstrap SMS scan** | Adds `READ_SMS`. Roadmap §8.2. |

## Known cleanup before stabilisation

- Decide whether to keep the TestDataUsageService (diagnostic-only).
- Lock the `MODEL_NAME` default in the sidecar to match what the app
  expects (`qwen3.5:latest`).
- Tighten `network_security_config` once cert-pin is wired (cleartext
  is currently allowed for local-LAN sidecar testing).

## Reference

- Repository CHANGELOG: `Scan1.4.2-R8/CHANGELOG.md`.
- Decisions, syntheses, runbooks: separate `scan_vault` (the
  why-memory).
- Project knowledge base: NotebookLM bundle (17 sources).
