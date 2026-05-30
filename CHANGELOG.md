# Changelog

All notable changes to S'CAN are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/).

---

## [1.4.6] — 2026-05-30

A capability release: two new network-privacy tools, a much deeper Wi-Fi
Security area, and demo-ready SMS detection — on the existing known-good build
toolchain.

### Added
- **DNS Leak Detection (Beta)** — `util/DnsLeakAnalyzer.kt` + `DnsLeakActivity.kt`.
  A new on-device tool answering "is my browsing private right now?": inspects the
  current network's active DNS resolver, **Private DNS (DoT)** and VPN state and
  grades it **PRIVATE / PARTIAL / EXPOSED**, with plain-language findings,
  per-resolver detail tiles and a one-tap shortcut toward the Private DNS setting.
  Runs entirely on-device — no DNS queries are sent off the phone. Launchable from a
  new cyan **Beta** card on the Home screen.
- **Wi-Fi Security — nearby networks & evil-twin detection.** Wi-Fi Security now
  lists **every network in range** (sortable by signal or risk), each with its own
  security grade, plus a new per-network detail screen (`WifiNetworkDetailActivity`,
  `adapter/WifiNetworkAdapter.kt`) breaking down encryption, signal, vendor and
  posture. Evil-twin detection flags a rogue AP that copies a nearby network's name
  to lure a connection — the one nearby case that genuinely warrants a warning.
- **Offline manufacturer (OUI) lookup** — `util/OuiLookup.kt` + a bundled IEEE OUI
  database asset (`oui_vendors.tsv`); networks show their hardware (router/AP) vendor,
  resolved entirely offline.
- **Trusted networks** — mark an access point you recognise so it is never flagged
  as an evil twin again.
- New Wi-Fi/Home widgets `ui/home/widget/RadarPulseView.kt` and `SignalBarsView.kt`.
- SMS: a new **Clear verdicts** action; "Try a sample scam" now cycles **seven**
  varied, realistic AU examples (parcel, road toll, tax refund, bank, myGov,
  family-impersonation, streaming) instead of one; two new on-device fallback
  patterns (myGov account-suspension, streaming-billing phishing).

### Changed
- **Quieter by design:** a nearby open/weak network is *its* posture, not a threat
  to you while you are not connected to it, so it no longer raises an alarm — only an
  evil twin does.
- Nearby networks **refresh automatically** as Android scans — passive and
  read-only (no `startScan()`); the app never changes your Wi-Fi.

### Version
- `versionName` 1.4.6, `versionCode` 8.

---

## [1.4.5] — 2026-05-30

A reliability and accuracy release: the headline features now behave correctly
and honestly, the UI is consistent end-to-end, and the app is more responsive
and crash-resistant.

### Added
- **`util/ScanApp.kt`** — Application class that pins the dark theme and installs
  a global uncaught-exception handler which relaunches the app gracefully (with a
  loop-guard) instead of the system "app keeps stopping" dialog.
- **`util/ScanDialog.kt`** + `layout/dialog_scan.xml` / `dialog_scan_choice.xml` —
  a shared, V4-styled dialog (input / confirm / notice / single-select list) that
  replaces every stock Android `AlertDialog` across the app for one consistent
  dark look.
- **`util/SensorAccessFormat.kt`** — one source of truth for phrasing observed
  camera / mic / location accesses, so App Info, Home "Needs attention" and the
  Alerts timeline read identically (sensor, real active duration, when, and
  background vs while-in-use).
- **`util/DemoDataSeeder.kt`** — hidden presenter aid (long-press the Home
  greeting) that seeds realistic sample data on a clean device.
- "Try a sample scam" action on the SMS screen;
  `PermissionAccessDao.recentAccesses` / `packagesWithBackgroundAccess` and
  `AlertDao.getAlertsForPackage` queries.

### Changed
- **Behaviour-gated risk.** `AppScanner` separates permission *exposure*
  (recalibrated — storage/media no longer inflate it) from *effective risk*: an
  app is HIGH only when a real finding exists (an observed **background** sensor
  access, or a **critical integrity** issue), otherwise it is capped at MEDIUM.
  Applied consistently to the App Info badge, scan pipeline, monitor seeding,
  charts, reports and new-install alerts via `AppScanner.effectiveRisk`.
- **Audit → Flagged** now mirrors effective risk and updates on every re-scan,
  instead of flagging any app that merely *holds* a sensitive permission.
- **SMS scam detection** defaults to the on-device classifier and **auto-fails-
  over** to it when the optional remote server errors, so detection never dies
  silently.
- App Info "Findings" and the Alerts timeline now show real observed evidence and
  no longer flag ordinary background network data; the Alerts row drops the noisy
  "Could access" capability list.
- The app pins its dark theme (fixing invisible light text on some screens) and
  reframes "coming soon" copy honestly.

### Performance
- Instant on-device SMS verdicts (no network round-trip); remote request timeouts
  cut from 60s to ~12s so a stalled server fails over fast instead of hanging.
- Lighter Alerts list — removed the per-row PackageManager permission lookups.
- Screen background work is `viewLifecycleOwner`-scoped (cancelled the moment you
  leave a screen); findings and the sensor timeline use bounded DB queries.

### Security
- The bundled HIBP API key is **no longer hardcoded in source** — it's injected at
  build time from a gitignored `local.properties` (`HIBP_API_KEY`) via `BuildConfig`,
  so it never lives in version control.

### Fixed
- Fragment detach crashes on fast tab switches (Audit / Home).
- Onboarding: batched permission requests no longer interrupt each other, the
  Usage-Access shortcut is guarded on devices that lack it, and the progress
  counter reads required grants.
- The scan flow can no longer get stuck on "Scanning…".
- Home "Needs attention" rows no longer open a blank screen (wrong navigation key).
- The device-security score is now computed, so the exported PDF report has real
  data.
- The first background-monitoring cycle no longer floods the alert list.

### Version
- `versionName` 1.4.5, `versionCode` 7.

---

## [1.4.4] — 2026-05-11

The Activity → Alerts page now explains *which* permissions an app could
use and *why* it was running in the background, instead of just naming
the app and the byte count. Every alert row gains a granular permission
breakdown (precise / approximate / background location stay distinct
instead of collapsing to "Location") and a "Why active" line that cites
real evidence: OS-confirmed sensor accesses with timestamps, the
foreground/background split of the bytes themselves, and the most-
likely declared mechanism.

### Added
- **`util/AttributionEngine.kt`** — combines three independent OS-
  attributed signals into a per-alert explanation: state-bucketed
  network bytes (`NetworkStatsManager.queryDetails` with
  `Bucket.STATE_FOREGROUND` vs `STATE_DEFAULT`), the Camera / Mic /
  Location access timeline from `PermissionAccessEntity`, and the
  manifest signals from the new inspector below. The
  `mostLikelyMechanism()` inference cross-references all three: when
  an observed sensor matches a declared foreground service type the
  cause is named with high confidence ("microphone foreground
  service"); otherwise declared mechanisms are ranked by how
  plausibly each drives unsolicited background bytes.
- **`util/BackgroundReasonInspector.kt`** — manifest-level
  introspection that surfaces *how* a third-party app is allowed to
  run in the background. Reads `ServiceInfo.foregroundServiceType` for
  every declared service (decodes the bitmask into named types:
  dataSync, location, mediaPlayback, phoneCall, mediaProjection,
  camera, microphone, connectedDevice, plus the API-34 additions),
  detects FCM registration, sync adapters, JobScheduler use,
  background-location grant, and boot-completed wake-up. All cached
  per-package.
- **`DataUsageHelper.getAppDataUsageBuckets()`** — per-UID,
  state-bucketed bytes via `queryDetails` (not `queryDetailsForUid`,
  which is privacy-locked on Samsung OneUI 5+ for non-self UIDs).
  Returns `null` on devices that block per-UID detail queries so the
  engine can fall back gracefully without dropping the rest of the
  explanation.
- **`PermissionAccessDao.accessesInWindow()`** — full event rows with
  timestamps and foreground state for any sensor access overlapping
  an alert window; complements the existing distinct-op queries used
  by the scorer.

### Changed
- **Alert row layout** (`item_v4_alert_row.xml`) — three new TextViews
  under the existing detail line: `tvAlertPermsObserved` (bold; lists
  ops actively observed during the window), `tvAlertPermsHeld` (lists
  every dangerous permission the app currently holds, with full
  granular labels), and `tvAlertReason` (the "Why active" evidence
  line populated by the attribution engine).
- **`AlertsFragment`** — granted-permission labels are no longer
  collapsed; "Precise Location", "Approximate Location" and
  "Background Location" stay distinct. The "could access" list is
  shown for every alert (previously gated on Suspicious + no-observed-
  sensor verdict). New `attributionsById` map fills async in parallel
  with `BehaviorScorer.scoreAll` and triggers a re-render once the
  rich explanation is ready.
- **`buildBackgroundReason()`** prefers the attribution engine's
  evidence-backed explanation when ready; falls back to the
  manifest-only capability summary while it loads.

### Notes
- Camera / Mic / Location are the only ops a non-privileged app can
  *observe* in real time. SMS, Contacts, Calendar etc. continue to
  fall back to capability-only ("could access") labels because the
  underlying `WATCH_APPOPS` permission is `signature|privileged|appop`
  in AOSP and cannot be granted to a third-party app on a stock
  retail device (`pm grant` rejects signature-class permissions; the
  Shizuku route requires ADB-on-every-boot or root).
- State-bucketed bytes are unavailable for non-self UIDs on Samsung
  OneUI 5+; on those devices the engine drops the bg/fg byte split
  and renders the rest of the explanation.
- No database schema change. The DAO addition is a new query against
  the existing `permission_access_events` table.

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
