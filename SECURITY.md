# Security policy

S'CAN is itself a privacy and security tool, so I take vulnerability reports
in the app or its sidecar seriously and want to hear about issues before they
get pushed to a wider audience.

## Reporting a vulnerability

**Do not open a public GitHub issue for security bugs.**

Send a private report through one of:

- **GitHub Security Advisories** — preferred:
  <https://github.com/codenamec0de/s-can/security/advisories/new>
- **Email** — `alafcagri@gmail.com` with `[SCAN-SECURITY]` in the subject

Please include:

- Affected component (Android app `source/`, AI sidecar `scan-ai/`, or both).
- Affected version (release tag, commit SHA, or `versionName` from app About).
- Reproduction steps and, if possible, a proof-of-concept.
- Impact you believe this has (data exposure, code exec, auth bypass, etc.).

I aim to acknowledge new reports within **72 hours** and to ship a fix or
mitigation within **30 days** for high-severity issues. Coordinated
disclosure is appreciated — please give me time to ship a fix before going
public.

## Scope

In scope:

- The Android app under `source/` (Kotlin code, ProGuard config, signing
  flow, on-device data handling).
- The AI sidecar under `scan-ai/` (FastAPI service, token issuance, prompt
  handling, model interface).
- The transport between the app and the sidecar (cert pinning, TLS config).

Out of scope:

- Bugs in third-party dependencies (please report upstream first; tell me if
  the dependency is reachable from S'CAN).
- Issues that require a rooted/compromised device or a malicious app already
  granted accessibility/notification access.
- Theoretical issues without a working PoC against current code.

## Testing on your own device

The signed APK on the **Releases** page is intended for tester sideloading.
You should only install it on a device you own or have permission to test.
S'CAN reads SMS, scans installed packages, and queries the connected Wi-Fi —
treat it the same way you'd treat any privacy-adjacent tool.

## What S'CAN itself does to protect you

- Cert-pin to the AI sidecar (pin in `BuildConfig.SCAN_AI_CERT_PIN`).
- No telemetry; no third-party analytics SDKs.
- Local scan results stored in Room on-device, never uploaded.
- SMS bodies are forwarded to the user's own configured sidecar, not to a
  third-party service.

If any of the above turns out not to be true in shipped code, that is a
security bug — please report it.
