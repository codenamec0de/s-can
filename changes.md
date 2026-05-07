# S'CAN — Changes in 1.4.3

This release reworks how S'CAN attributes background sensor access, fixes
several user-reported issues, and ships a standalone test harness so the
detection pipeline can be re-verified end-to-end without instrumenting a
real third-party app.

The high-level theme: **stop guessing what an app *might* have done from a
permission grant; report what it *did* do based on real, observed signals.**

---

## 1. Detection pipeline overhaul (Phase 0 → 1 → 2)

Previous releases inferred sensor abuse from a permission grant + background
data transfer ("Instagram has CAMERA and used data while backgrounded →
Instagram used your camera"). That produced false positives — most notably
the Instagram-camera-in-background false alarm a tester hit on a Samsung A17.
The new pipeline has three independent signal sources, all corroborating each
other before an alert says a sensor was used.

### Phase 0 — Real op tracking (in-process)
- Added `OpAccessTracker` (singleton) that registers
  `CameraManager.AvailabilityCallback` and
  `AudioManager.AudioRecordingCallback`. These are OS-level callbacks; they
  fire when *any* app on the device opens the camera or starts an
  AudioRecord, and the system reverse-attributes the active package via
  `UsageStatsManager.queryEvents`.
- New Room entity `PermissionAccessEntity` and DAO
  `PermissionAccessDao` persist every observed access with
  `(packageName, op, startedAt, endedAt, foregroundAtStart)`.
- `BackgroundUsageMonitor.scan()` no longer reports the *granted* permission
  set as the access claim. It calls
  `accessDao.opsInWindow(packageName, startTime, endTime)` and reports only
  ops actually observed during the window.
- Database bumped from v6 → v7 (destructive migration on upgrade).

### Phase 1 — OS-signed privacy notifications
- `PrivacyNotificationListener` (a `NotificationListenerService`) listens
  for the system's privacy-indicator notifications from
  `com.android.permissioncontroller`,
  `com.google.android.permissioncontroller`,
  `com.samsung.android.permissioncontroller`,
  `com.samsung.android.privacydashboard`,
  `com.android.systemui`, and `com.samsung.android.app.smartcapture`.
- It parses the title / text / bigText for camera, microphone, and location
  keywords, then reverse-resolves the app label back to a package name via a
  cached label index.
- Compensates for the Samsung One UI process freezer dropping in-process
  `AvailabilityCallback` deliveries to background apps — when Phase 0 misses
  a transition, the OS-posted notification is still seen.
- Requires the user to grant **Notification access** to S'CAN in system
  settings. The Alerts screen shows a banner pointing the user there if it
  isn't granted yet.
- Manifest declares the service with the
  `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` permission and the
  required `android.service.notification.NotificationListenerService`
  intent filter.

### Phase 2 — Per-app baseline + verdict engine
- New `BehaviorScorer` builds a per-app baseline (median, p90,
  hour-of-day buckets, sensor-rate distribution) from prior alerts for the
  same package, then assigns each new alert a verdict:
  - `NORMAL`     — within ±2× the app's own historical median
  - `UNUSUAL`    — ≥2× p90 AND ≥3× median AND ≥5 MB
  - `SUSPICIOUS` — unusual sensor for this app, or a night-time spike
- `AlertsFragment` consults the verdict map first; the headline is
  human-readable ("WhatsApp sent data in the background") and the
  supporting line is the technical detail.
- Important fix: per-alert exclusion of self from the history sample. The
  first cut filtered the entire current batch out of the baseline, which
  poisoned scoring when several alerts arrived together. Now each alert's
  baseline is computed against its own package's history minus only that
  one alert.

### Sensor-only alert path (1.4.3)
- `BackgroundUsageMonitor` previously gated all alerts on **≥50 KB of
  background network data**. An app could hold the camera for hours with no
  network activity and never trigger an alert.
- Pass 1 and Pass 2 now emit an alert when **either** the data threshold
  is crossed **or** at least one sensor was observed in active use during
  the window. Apps that hold a sensor without exfiltrating immediately
  (local recording, batched upload, malware staging) are now caught.

### Severity tuning
- "Critical" no longer fires from byte count alone. The severity classifier
  requires `permissions.isNotEmpty() && bytes >= 1MB`. WeeklyStatsRecorder
  was updated to match.
- The "what could this app access" line was restored alongside the observed
  list, so users see both *what it actually did this time* and *what else
  it has the keys for*. `getGrantedSensitivePermissions` was made public to
  serve the `grantedSummaryFor()` helper that produces the
  "could access Camera, Mic, Location" tail.

---

## 2. SensorProbe — standalone test harness (new)

A separate Android app at `sensorprobe/` that exists solely to trigger the
detection pipeline.

- Three toggles (Camera / Microphone / Location) that hold each sensor open
  from a foreground service (`BackgroundSensorService`).
- Camera2 with a 320×240 YUV `ImageReader` + repeating preview request —
  the minimum to flip the system's "camera in use" indicator.
- AudioRecord with a `VOICE_COMMUNICATION` source and a reader thread that
  keeps the buffer drained, so OEM short-circuits don't revoke the active
  state.
- LocationManager updates from both `GPS_PROVIDER` and `NETWORK_PROVIDER`.
- Foreground service type is the OR of every sensor declared in the
  manifest (`camera|microphone|location|dataSync`); the `dataSync` slot is
  only used for the brief idle window before a real holder activates,
  avoiding the `SecurityException: foregroundServiceType ... is not a
  subset` crashes that an earlier draft tripped.
- Simulated exfiltration: while any sensor is held, the service downloads
  16 KB from `httpbin.org/bytes/16384` every 8 s, so per-UID NetworkStats
  also exercises the data-transfer arm of the alert pipeline.

The smoke test that was run against this build:

1. Install both APKs.
2. Tap Camera in SensorProbe → grant permission → press Home.
3. Wait ~90 s — leak runs ~6 cycles, ~96 KB transferred.
4. Open S'CAN → Alerts.
5. Top entry: **SensorProbe — SUSPICIOUS — "used your camera, microphone
   while you weren't using it" — Verified**, with a system-tray heads-up
   on the `scan_alert_channel` notification channel.

---

## 3. Embedded HIBP API key for testers

`BreachCheckerActivity` now ships with the HIBP API key
(`781dcc5259a6477995aa3ebf9f7f3aa2`) embedded as
`embeddedHibpApiKey`. `getApiKey()` returns the embedded value
unconditionally; `saveApiKey()` is a documented no-op. Testers can use
breach-check without provisioning their own HIBP key.

---

## 4. Export Report (Settings → Export Report) bug fixes

The Export Report flow crashed natively
(`SIGSEGV in Canvas.drawText` from `drawBackgroundActivity`) once the page
filled. Root cause: `PdfReportGenerator` cached
`val c = p.canvas` once at the top of a section. When `ensureSpace()`
needed a new page, it called `finishPage()` and the cached canvas pointer
was no longer valid — the next `c.drawText` wrote into a freed canvas.

- `c` is now a `var` and is **refreshed after every `ensureSpace()`
  call**: `c = p.canvas`.
- All free-text fields are run through a `s()` sanitizer that strips
  non-BMP characters before they hit the PDF font, removing a separate
  class of native crashes from emoji / surrogate pairs.
- The PDF was redesigned: per-call `Palette` class for consistent colors,
  rebuilt cover page, redesigned section dividers and stat cards, tighter
  spacing.

---

## 5. Wi-Fi Security button text clipping fix

The buttons at the bottom of the Wi-Fi Security screen (`btnRescan`,
`btnExport`, `btnGrantPermission`) had their text visually truncated at
the bottom — Material's default insets were eating ~6 dp of vertical
space. Fixed by setting

```xml
android:insetTop="0dp"
android:insetBottom="0dp"
android:paddingTop="0dp"
android:paddingBottom="0dp"
```

on each button.

---

## 6. Files added / changed

```
source/app/src/main/java/com/uow/scan/data/entity/PermissionAccessEntity.kt   (new)
source/app/src/main/java/com/uow/scan/data/dao/PermissionAccessDao.kt         (new)
source/app/src/main/java/com/uow/scan/util/OpAccessTracker.kt                 (new)
source/app/src/main/java/com/uow/scan/service/PrivacyNotificationListener.kt  (new)
source/app/src/main/java/com/uow/scan/util/NotificationListenerHelper.kt      (new)
source/app/src/main/java/com/uow/scan/util/BehaviorScorer.kt                  (new)
source/app/src/main/java/com/uow/scan/util/BackgroundUsageMonitor.kt          (sensor-only path)
source/app/src/main/java/com/uow/scan/ui/alerts/AlertsFragment.kt             (verdict-aware UI)
source/app/src/main/java/com/uow/scan/util/PdfReportGenerator.kt              (canvas refresh + redesign)
source/app/src/main/java/com/uow/scan/BreachCheckerActivity.kt                (embedded HIBP key)
source/app/src/main/res/layout/activity_wifi_security.xml                     (button insets)
source/app/src/main/AndroidManifest.xml                                       (NotificationListener service)
source/app/build.gradle.kts                                                   (versionName 1.4.3, code 5)

sensorprobe/                                                                  (new test harness app)
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/uow/sensorprobe/MainActivity.kt
  app/src/main/java/com/uow/sensorprobe/BackgroundSensorService.kt
  app/src/main/res/layout/activity_main.xml
  app/src/main/res/values/strings.xml
  build.gradle.kts, settings.gradle.kts, gradle.properties
```

---

## 7. Known limitations

- **Location detection** has no Phase 0 path. Android exposes no callback
  to a third-party app for *another app's* location access; we have to
  rely on Phase 1 (OS privacy notifications) when the system actually
  posts one. Camera and microphone are fully covered.
- **Samsung One UI process freezer** can suppress
  `CameraManager.AvailabilityCallback` deliveries to backgrounded
  third-party apps. Phase 1 partially compensates, but a small attribution
  gap remains for in-process detection. Not specific to this release;
  tracked separately.
- **NetworkStats summary buckets** on Samsung One UI 5+ flush in coarse
  windows. Very short scan windows occasionally see 0 B even when an app
  did transfer. The 2-hour `DATA_LOOKBACK_MS` already widens the window
  to compensate.

---

## 8. Smoke test artefacts

- `permission_access_events`: rows for `com.uow.sensorprobe / CAMERA` and
  `com.uow.sensorprobe / MICROPHONE` with non-null `endedAt` after the
  toggle was released.
- `alerts`: row for `com.uow.sensorprobe` with
  `permissions = "Camera,Microphone"` and `data ≥ 50 KB` after the leak
  ran. Pre-1.4.3 this row would not have been generated because the
  build-time hold-without-exfil was `data = 0 B`.
- System notification: `id = 1002` on channel `scan_alert_channel`,
  posted on the same scan cycle as the alert row.
