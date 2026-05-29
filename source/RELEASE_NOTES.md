# S'CAN — Release Notes

## v1.4.5 (2026-05-30)

A reliability and accuracy release. The headline features now behave correctly and honestly,
the UI is consistent end-to-end, and the app is more responsive and crash-resistant.

### 🛡️ Smarter, honest risk categorisation
- **HIGH risk now means a real concern, not just permissions.** The old logic branded ordinary
  camera/social apps HIGH simply for *holding* permissions (and even counted near-universal
  storage/media access). Risk is now recalibrated and **behaviour-gated**: an app is only HIGH
  when there is a genuine observed finding — a sensor (camera/mic/location) used **in the
  background**, or a **critical app-integrity issue**. Otherwise it's capped at MEDIUM
  ("elevated exposure, nothing detected").
- Permission *exposure* itself is recalibrated so storage/media no longer inflate ratings, and
  HIGH is reserved for genuine surveillance-capable profiles.
- The rating is consistent across every surface (App Info badge, Audit list, charts, reports,
  new-install alerts) from a single source of truth.
- **Audit → Flagged now reflects real findings and resets on every re-scan** (previously it
  flagged any app holding a sensitive permission and never updated).

### 📩 SMS scam detection that always works
- **On-device classification is now the default** — no message ever needs to leave the device,
  and detection no longer silently dies when the AI server is unreachable.
- When the optional remote server *is* enabled, the worker now **automatically fails over to the
  on-device classifier** on any error instead of retrying forever with no verdict.
- New **"Try a sample scam"** action to demonstrate detection on demand.
- Connection status reads as a healthy "On-device classifier active · private" state.

### 🔎 App Info & activity now show real evidence
- App Info "Findings" now report **what was actually observed** — the sensor used, the real
  active duration, when it happened, and whether it was a background access or while-in-use —
  instead of a vague "caught accessing sensitive data".
- The same real camera/mic/location **access timeline is now shown consistently** across App
  Info, Home "Needs attention", and the Alerts timeline.
- **Ordinary background network data is no longer flagged** — virtually every app uses it
  (push, sync), so surfacing it was a false alarm.
- Alerts rows decluttered: removed the noisy "Could access" capability list; rows now show only
  what was actually used.
- App Info version field no longer overflows for long version strings.

### 🎨 Consistent, polished UI
- **Every dialog now matches the app's dark theme** (rounded card, brand font, accent buttons)
  via a shared dialog component — replacing the stock Android pop-ups across the breach checker,
  SMS, profile, data & storage, alerts, about, and more.
- The app now pins its **dark theme**, fixing screens where light text was invisible on a light
  background.
- "Coming soon" features are framed honestly (no leftover developer placeholder text).

### 🧱 Stability & crash hardening
- Added a **global crash handler** that gracefully relaunches the app instead of showing the
  system "app keeps stopping" dialog.
- Fixed crashes when switching tabs quickly (Audit/Home) by scoping work to the view lifecycle
  and guarding against detached screens.
- Hardened onboarding: permission requests no longer interrupt each other, the Usage-Access
  shortcut is guarded against devices that lack it, and the progress counter reads correctly.
- The scan flow can no longer get stuck on "Scanning…"; results are always reachable, even on
  error.
- The first background-monitoring cycle no longer floods the alert list before a scan has run.

### ⚡ Performance & responsiveness
- **Instant SMS verdicts** — on-device classification removes the network round-trip entirely;
  and when using the remote server, request timeouts were cut from **60s → ~12s** so a stalled
  server fails over fast instead of hanging.
- **Smoother Alerts list** — removed per-row package-manager permission lookups (the old
  "Could access" computation), so the activity timeline scrolls lighter.
- **Less wasted work** — screen background tasks are now cancelled the moment you leave a
  screen (view-lifecycle scoped), avoiding leaked coroutines and redundant CPU.
- Findings and the sensor-access timeline use **bounded database queries**.

### 🔐 Security
- The bundled HIBP API key is **no longer hardcoded in source** — it's injected at build time
  from a gitignored `local.properties` (`HIBP_API_KEY`) via `BuildConfig`, so it never lives in
  version control.

### 🐛 Other fixes
- Fixed Home "Needs attention" rows opening a blank screen (wrong navigation key).
- The device-security score is now actually computed, so the exported PDF report contains real
  data.

---

## v1.4.4
- Small visual bug fixes and version bump.

## v1.4.2
- Initial release: Android privacy & background-activity monitor.
