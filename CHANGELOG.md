# Changelog

All notable changes to S'CAN are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/).

---

## [1.4.3] — 2026-05-07

Background-sensor detection re-grounded on real, observed signals.
Previous releases inferred sensor abuse from `permission grant + background
data transfer`, which produced false positives (most visibly the
Instagram-camera-in-background false alarm hit on a Samsung A17). 1.4.3
replaces that heuristic with a three-phase pipeline that only claims a
sensor was used when it was actually observed.

### Added
- **Phase 0 — In-process op tracking.** New `OpAccessTracker` registers
  `CameraManager.AvailabilityCallback` and
  `AudioManager.AudioRecordingCallback` and persists every observed
  camera / microphone access (`PermissionAccessEntity` /
  `PermissionAccessDao`, Room v6 → v7).
- **Phase 1 — OS-signed privacy notifications.**
  `PrivacyNotificationListener` (a `NotificationListenerService`)
  captures system privacy-indicator notifications from
  `permissioncontroller`, Samsung's privacy dashboard, and SystemUI;
  parses them; reverse-resolves the labelled app to a package via a
  cached label index. Compensates for Samsung One UI's process freezer
  dropping in-process callback deliveries.
- **Phase 2 — Per-app baseline + verdict engine.** `BehaviorScorer`
  builds a per-package median / p90 / hour-of-day / sensor-rate
  baseline from prior alerts and classifies each new alert as
  `NORMAL`, `UNUSUAL`, or `SUSPICIOUS`. `AlertsFragment` consults the
  verdict map first; the headline reads in plain English ("WhatsApp
  sent data in the background") and the supporting line is the
  technical detail.
- **Sensor-only alert path.** `BackgroundUsageMonitor.scan()` Pass 1
  and Pass 2 now emit alerts when *either* the 50 KB data threshold is
  crossed *or* a sensor was observed in active use during the window.
  Apps that hold a sensor without exfiltrating immediately (local
  recording, batched upload, malware staging) are now caught.
- **Notification-access banner** on the Alerts screen pointing the user
  at system settings if `BIND_NOTIFICATION_LISTENER_SERVICE` hasn't
  been granted yet.
- **Embedded HIBP API key** in `BreachCheckerActivity` so testers can
  exercise breach-check without provisioning their own key.
  `getApiKey()` returns the embedded value unconditionally.
- **SensorProbe — standalone test harness app** (`sensorprobe/`).
  Three toggles (Camera / Microphone / Location) hold each sensor open
  from a foreground service, with simulated ~16 KB exfiltration every
  8 s while a holder is active. Lets the detection pipeline be
  re-verified end-to-end without instrumenting a real third-party app.

### Changed
- "Critical" severity now requires `permissions.isNotEmpty() && bytes
  >= 1MB` — byte count alone is no longer sufficient. WeeklyStatsRecorder
  was updated to match.
- `BackgroundUsageMonitor` reports the *observed* op set as the access
  claim, not the *granted* permission set. The "could access X, Y, Z"
  context line was restored alongside it via `grantedSummaryFor()` so
  users see both what the app did this time and what else it has the
  keys for.
- PDF report (Settings → Export Report) redesigned: per-call `Palette`
  for consistent colours, rebuilt cover page, redesigned section
  dividers and stat cards, tighter spacing.

### Fixed
- **Native crash in PDF export.** `PdfReportGenerator` cached
  `val c = p.canvas` once at the top of a section; when
  `ensureSpace()` finished a page, the cached canvas was freed and
  the next `c.drawText` segfaulted (`SIGSEGV in
  drawBackgroundActivity`). `c` is now a `var` and is refreshed
  after every `ensureSpace()` call. All free-text fields are also
  run through a sanitiser that strips non-BMP characters before they
  hit the PDF font.
- **Wi-Fi Security button text clipping.** The buttons at the bottom
  of the Wi-Fi Security screen had their text visually truncated by
  ~6 dp because of Material's default insets. Fixed with
  `insetTop="0dp"`, `insetBottom="0dp"`, `paddingTop="0dp"`,
  `paddingBottom="0dp"` on `btnRescan`, `btnExport`, and
  `btnGrantPermission`.
- **BehaviorScorer baseline pollution.** First cut filtered the entire
  current alert batch out of the baseline sample, which left scoring
  with too little history when several alerts arrived together. Each
  alert's baseline is now computed against its own package's history
  minus only that one alert.

### Notes
- Location detection has no Phase 0 path. Android exposes no callback
  to a third-party app for *another* app's location access; we rely on
  Phase 1 (OS privacy notifications) when the system actually posts
  one. Camera and microphone are fully covered.
- Detailed dev-facing notes for this release live in `changes.md` at
  the repo root.

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

[1.4.3]: https://github.com/codenamec0de/s-can/releases/tag/v1.4.3
[1.4.2]: https://github.com/codenamec0de/s-can/releases/tag/v1.4.2
[1.4.1]: https://github.com/codenamec0de/s-can/releases/tag/v1.4.1
[1.4]: https://github.com/codenamec0de/s-can/releases/tag/v1.4
