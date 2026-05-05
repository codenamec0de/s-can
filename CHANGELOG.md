# Changelog

All notable changes to S'CAN are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/).

---

## [1.4.2] — 2026-05-06

R8 release-ready. First tagged build available as a GitHub Release.

### Added
- Signed, R8-shrunk release APK (`app-release.apk`, ~6 MB) attached to GitHub Releases for tester sideloading.
- ProGuard rules for Retrofit, OkHttp, Gson, Firebase, Room, MPAndroidChart, and project API/data models so reflection-driven code survives R8.
- Cert-pin build config: `BuildConfig.SCAN_AI_CERT_PIN` carries the SHA-256 SPKI pin for the AI sidecar (rotation = code change).

### Changed
- Login screen now uses the official `scan_logo` asset instead of the V4 wordmark placeholder.

### Notes
- TLS + cert-pinning to the AI sidecar (V1.4 item 2.2) and SMS onboarding disclosure (V1.4 item 2.1) remain on the V1.4 freeze list.

## [1.4.1] — 2026-04-30

Pre-R8 baseline: SMS pipeline and Wi-Fi analyser feature-complete.

### Added
- **SMS Scam Detection** end-to-end: receiver → forwarder → FastAPI sidecar → `qwen3.5:latest` classifier; result rendered in `SmsScamDetectionActivity` with URL extraction and brand-impersonation flags.
- **Wi-Fi Security** analyser: auth type, PMF (802.11w), cipher, evil-twin clusters, captive-portal probing, DNS server enumeration.
- **Breach Checker**: Have I Been Pwned integration with monitored email tracking and per-breach data-class exposure.
- Room database for scan history, breach state, and SMS results.

### Changed
- AI sidecar (`scan-ai/`) moved under `Desktop/` and switched its default classifier to `qwen3.5:latest`.

## [1.4] — earlier 2026

Initial public-portfolio drop of the privacy & security audit suite.

[1.4.2]: https://github.com/codenamec0de/s-can/releases/tag/v1.4.2
[1.4.1]: https://github.com/codenamec0de/s-can/releases/tag/v1.4.1
[1.4]: https://github.com/codenamec0de/s-can/releases/tag/v1.4
