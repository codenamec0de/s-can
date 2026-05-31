# S'CAN — Release Notes

## v1.4.7 (2026-05-31)

The DNS tool goes from *informational* to *actionable*: one tap now encrypts your DNS for real.

### 🔒 DNS Protection — the one-tap fix
- A new **DNS Protection** brings up a local **DNS-only VPN** that captures the device's DNS and
  re-issues every query **encrypted over DNS-over-HTTPS** to Cloudflare.
- The leak score reaches **PRIVATE** only while the tunnel is genuinely up — it is keyed to the
  live tunnel and can never be cosmetic.
- A **split tunnel** routes only DNS, so the rest of your traffic is untouched. The OS shows its
  own consent dialog and a persistent key icon; you can turn it off at any time.

### 🛰️ Deep test — server-backed egress proof
- Proves **where** your DNS actually exits. Resolves a one-time domain that our own authoritative
  name-server logs, then names the real resolver (your ISP, a public resolver, or a VPN) with
  owner, ASN and geo.

### 🛠️ Fixes
- The tunnel now actually establishes — an invalid IPv6 literal had made `establish()` throw every
  time (the score was stuck at EXPOSED).
- The Deep test no longer false-flags a leak: the app no longer excludes itself from its own tunnel,
  and a public-resolver exit correctly reads as *no leak* (the only real leak is DNS still reaching
  your ISP).

### ⚠️ Limitations
- Protects the **system resolver**; an app that hard-codes its own DNS bypasses it — same as
  Android's built-in Private DNS. Only one VPN runs at a time.

---

## v1.4.6 (2026-05-30)

A capability release: two new network-privacy tools, a much deeper Wi-Fi Security area, and
demo-ready SMS detection — all on a refreshed, reproducible build toolchain.

### 🌐 DNS Leak Detection (Beta)
- A new **DNS Leak Detection** tool answers "is my browsing private right now?" It inspects
  your current network's active DNS resolver, **Private DNS (DoT)**, and VPN state and grades
  it **PRIVATE / PARTIAL / EXPOSED**.
- Plain-language **findings** explain what's protecting you and what's exposing your lookups,
  with per-resolver detail tiles and a one-tap shortcut toward the Private DNS setting.
- Runs **entirely on-device** from the system network configuration — no DNS queries are sent
  to us.
- Launchable from a new cyan **Beta** card on the Home screen.

### 📶 Wi-Fi Security — nearby networks & evil-twin detection
- Wi-Fi Security now shows **every network in range**, not just the one you're connected to —
  sortable by signal or risk, each with its own security grade.
- **Evil-twin detection** flags a rogue access point that copies a nearby network's name to
  lure a connection — the one nearby case that genuinely warrants a warning.
- A new **per-network detail screen** breaks down any network's encryption, signal, vendor and
  posture, with a clear "do not connect" call-out for evil twins.
- **Manufacturer lookup** — networks now show their hardware vendor (router/AP maker) via a
  bundled IEEE OUI database, resolved **entirely offline**.
- **Trusted networks** — mark an access point you recognise as trusted so it's never flagged as
  an evil twin again.
- Nearby networks **refresh automatically** as Android scans (passive and read-only — the app
  never changes your Wi-Fi).
- **Quieter by design:** a nearby open/weak network is *its* posture, not a threat to you while
  you're not connected to it, so it no longer raises an alarm.

### 📩 SMS scam — sharper live detection
- **"Try a sample scam"** now cycles through **seven varied, realistic examples** (parcel, road
  toll, tax refund, bank, myGov, family-impersonation, streaming) instead of a single one.
- New **Clear verdicts** action wipes the verdict history for a clean slate.
- Two new on-device fallback patterns (myGov account-suspension, streaming-billing phishing).

---

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
