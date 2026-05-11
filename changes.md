# S'CAN — Changes in 1.4.4

This release upgrades the Activity → Alerts page from "an app sent
N bytes in the background" to a complete answer: *which* permissions
that app could have used (with the granular labels intact — Precise vs
Approximate vs Background Location stay distinct), *which* sensors were
actually observed during the window with timestamps, *what fraction* of
the bytes left the device specifically while the app was offscreen, and
*why* the app was allowed to be active in the first place.

The high-level theme: **stop hiding the granularity behind a one-word
pill; surface every OS-attributed signal we can read without root, and
clearly label the rest as inference.**

---

## 1. Activity → Alerts: granular permission breakdown

The previous UI showed a single "perm pill" (Camera / Microphone /
Location / etc.) and, for Suspicious-verdict alerts only, a collapsed
"could access Location, Contacts" line. Multiple Android perms that
mapped to the same friendly name were merged — so an app that held
Precise Location, Approximate Location *and* Background Location
displayed as just "Location".

That collapsing is now gone.

- `AlertsFragment.collapseLabel()` deleted; granted permissions render
  with their full labels from `PermissionHelper.getPermissionName()`.
- The "could access" list is shown for every alert, not gated on the
  Suspicious verdict.
- The row layout (`res/layout/item_v4_alert_row.xml`) gains three new
  TextViews under the existing detail line:
  - `tvAlertPermsObserved` (bold) — ops actively observed during the
    window. Camera and Microphone come from
    `CameraManager.AvailabilityCallback` and
    `AudioManager.AudioRecordingCallback`; Location comes from the
    OS-posted privacy notifications captured by the existing
    `PrivacyNotificationListener`. Renders as "Used Camera ·
    Microphone".
  - `tvAlertPermsHeld` (muted) — every dangerous permission the app
    currently holds, full granular labels, with anything already in
    `tvAlertPermsObserved` filtered out so the same op never repeats.
    Renders as "Could also access Precise Location · Background
    Location · Approximate Location · Read Contacts · Read SMS".
  - `tvAlertReason` (subtle) — the "Why active: …" line described in
    section 3.

A scored alert now reads, for example:

```
Messages                       SUSPICIOUS   10:02
Messages used microphone with silent background activity
2.4 MB while backgrounded · matches typical behaviour
Used Microphone
Could also access Precise Location · Background Location · Read SMS · Camera
Why active: Microphone accessed at 10:01:47 · 2.4 MB while
            backgrounded, 100 KB foreground · all over mobile data ·
            Likely: microphone foreground service
```

---

## 2. Manifest-derived "what mechanisms can this app use" inspector

New `util/BackgroundReasonInspector.kt`. Pure manifest introspection;
no privileged APIs.

```kotlin
data class Reasons(
    val foregroundServiceTypes: List<String>,  // dataSync, location, ...
    val hasSyncAdapter: Boolean,
    val autoStartOnBoot: Boolean,
    val pushMessaging: Boolean,
    val backgroundLocation: Boolean,
    val schedulesJobs: Boolean,
)
```

Decodes `ServiceInfo.foregroundServiceType` for every `<service>`
declared by the package. Recognises every public type from API 29
through API 34: dataSync, location, mediaPlayback, phoneCall,
mediaProjection, camera, microphone, connectedDevice, plus health,
remoteMessaging, shortService, specialUse, systemExempt on
Android 14+.

Detects sync adapters by looking for `android.content.SyncAdapter`
intent-filter services scoped to the package. Detects FCM by checking
for the `c2dm.permission.RECEIVE` permission or a registered
`com.google.firebase.MESSAGING_EVENT` service. Detects JobScheduler
use by scanning for a service that declares the `BIND_JOB_SERVICE`
permission. Boot-completed and background location are checked
against the granted-permissions array on `PackageInfo`.

Per-package result is cached (singleton-scoped) — the alert list can
re-bind freely without re-querying PackageManager.

---

## 3. AttributionEngine — "why" answered with evidence

New `util/AttributionEngine.kt`. The honest reality first:

> Real per-op attribution requires `WATCH_APPOPS`, which is
> `signature|privileged|appop` in AOSP. It is unavailable to a
> third-party app on a stock retail device — `pm grant` rejects
> signature-class permissions; Shizuku-style proxies require an ADB
> command on every boot or root. The plain answer to "can we get
> WATCH_APPOPS without root?" is **no**.

What we can do without it: combine three independent OS-attributed
signals into the most accurate explanation possible.

### Signal 1 — State-bucketed network bytes (true OS attribution)

`DataUsageHelper.getAppDataUsageBuckets(uid, start, end): StateBytes?`
uses `NetworkStatsManager.queryDetails` (not `queryDetailsForUid`,
which is privacy-locked to caller's UID on Samsung OneUI 5+) and
reads `NetworkStats.Bucket.state`. Each bucket is tagged
`STATE_FOREGROUND` or `STATE_DEFAULT` (background) by the OS at
flush time.

Result: a per-alert split of `(foregroundBytes, backgroundBytes,
mobileBytes, wifiBytes, totalBytes)`. We can finally say "2.4 MB sent
while backgrounded, 100 KB foreground", not just "2.5 MB total".

Returns `null` when the OEM blocks per-UID detail queries; the engine
drops only that piece of the explanation and keeps the rest.

### Signal 2 — OS-confirmed sensor accesses with timestamps

New DAO query `PermissionAccessDao.accessesInWindow(pkg, start, end)`
returns the full `PermissionAccessEntity` rows that overlap the alert
window, sorted by start time. Camera and Microphone events come from
the public `OpAccessTracker` callbacks; Location events come from
the existing `PrivacyNotificationListener` parsing system privacy
notifications (Samsung One UI 5+ / AOSP 12+).

Renders as concrete evidence: "Microphone accessed at 10:01:47 ·
Location accessed at 10:01:42, 10:02:01". When >3 accesses cluster
the engine collapses to "Camera accessed 5× (first 10:01:42)".

### Signal 3 — Manifest-declared mechanisms

The `BackgroundReasonInspector` output from section 2.

### Cross-referencing the three

`AttributionEngine.mostLikelyMechanism()` is the inference layer:

1. **High-confidence pairings** — if a Camera access was observed AND
   the app declared a `camera` foreground service type, the cause is
   named confidently as "camera foreground service". Same for
   microphone↔microphone, location↔location (or background-location
   permission), etc.
2. **Fallback ranking** — when no sensor was observed, declared
   mechanisms are ranked by how plausibly each drives unsolicited
   background bytes:
   `dataSync FG service > sync adapter > FCM push > mediaPlayback
    FG service > location FG service > scheduled job > boot-time
    auto-start`.

The engine runs on `Dispatchers.IO` from
`AlertsFragment.loadAlerts()`, in parallel with the existing
`BehaviorScorer.scoreAll` pass. Rows render with the manifest-only
fallback first, then re-render once the per-alert attribution map
arrives. Per-alert attribution is keyed by `alert.id` and cached for
the lifetime of one bind pass; scrolling the alert list never
re-queries.

---

## 4. Files changed

```
app/src/main/java/com/uow/scan/util/AttributionEngine.kt          (new)
app/src/main/java/com/uow/scan/util/BackgroundReasonInspector.kt  (new)
app/src/main/java/com/uow/scan/util/DataUsageHelper.kt            (+74)
app/src/main/java/com/uow/scan/data/dao/PermissionAccessDao.kt    (+18)
app/src/main/java/com/uow/scan/ui/alerts/AlertsFragment.kt        (rewrites
                                                                   buildAlertDetail,
                                                                   bindRow,
                                                                   grantedSummaryFor;
                                                                   adds
                                                                   buildPermissionsBreakdown,
                                                                   buildBackgroundReason)
app/src/main/res/layout/item_v4_alert_row.xml                     (+45;
                                                                   3 new TextViews)
app/build.gradle.kts                                              (versionCode 5→6,
                                                                   versionName 1.4.3→1.4.4)
```

No database schema change. The Room version stays at 7; the new DAO
query is a `SELECT *` against the existing `permission_access_events`
table.

---

## 5. Honest limitations

- Camera, Mic, Location are the only ops a non-privileged app can
  *observe* in real time. SMS / Contacts / Calendar / Phone / Body
  Sensors fall back to capability-only labels in `tvAlertPermsHeld`
  ("could access Read SMS"), never to observed-fact labels.
- State-bucketed bytes are unavailable for non-self UIDs on Samsung
  OneUI 5+. The Pixel / AOSP path is reliable; on Samsung you'll see
  the explanation without the bg/fg byte split.
- "Likely: …" is an inference, not a measurement, when no sensor was
  observed in the window. The engine names the strongest single
  signal it cited so the user can judge confidence.
- The "could access" list reflects permissions held *now*, not at
  alert time. If a user revokes a permission after an alert is logged,
  the held-permissions line will reflect the revocation on next
  render. (Persisting the snapshot at alert time would require an
  AlertEntity schema change and a Room migration; not done here to
  keep the patch additive.)
