# S'CAN — Release Notes

## v1.5 Pre-Release (Stable) (2026-06-02)

The **demo build** — a brand refresh plus a fairness overhaul of the Wi-Fi safety score.
Everything below is **device-verified on the A17**; no faked fields.

### 🎨 New S'CAN identity
- Retired the legacy cyan/purple raster logo for a single **lime vector mark**, used everywhere:
  dashboard, launch screen, **adaptive launcher icon**, login, and about.
- **Launch screen** is a still "radar" composition — static rings + a frozen sweep wedge + a soft
  glow behind the mark.
- **Dashboard** carries an **always-on animated radar badge** (pulsing rings + a rotating sweep) in
  the top-left, directly above the greeting.
- Removed the old raster assets (`scan_logo.png`, `app_logo.png`).

### 📶 Wi-Fi Security — a fair, honest 0–100 score
- **Fixed an unreachable ceiling:** the safety score secretly maxed at **88** (and effectively **93**
  on modern phones, since Android hides the client MAC from apps), so a flawless network could never
  read 100. The rubric is now renormalized to a **true 0–100**.
- **PMF "not advertised" now earns partial credit** instead of a punishing 0 — most WPA2 routers apply
  802.11w without advertising the token, so zeroing it out over-penalized normal home networks.
- **Active verification can now *raise* the score, not just dock it:** passing **DNS / TLS / captive**
  tests and arming the **Shield (DoH)** add up to **+15**. Using the app's own protection is finally
  rewarded; tested tampering still docks the gauge.
- **MAC randomization credited by default on Android 10+** — the OS hides the MAC from apps but
  randomizes it per-network by default, so we credit that default instead of stranding 7 points.
- Evil-twin penalty rebalanced (−20 → −24) to stay proportional on the new scale.
- **Net effect:** a strong WPA2 home network moved from **52 ("Poor/Fair") → 78 ("Good")**, and **~86
  with the Shield on** — device-verified, no mock data.

### 📐 Architecture overview
- Added **`ARCHITECTURE.md`** — a plain-language, diagram-first tour (Hetzner relay, the scan.xyz domain
  + TLS, and a per-tool active/passive explainer) for the presentation.

## v1.5.0 (2026-06-02)

A **verification & control** release. The Network Traffic Monitor ships real per-app tracker
blocking, Wi-Fi Security gains **active on-device verification** with a one-tap Shield, and a new
privacy-first **Password Check** joins the Breach Checker. Everything is computed live on the
device — no demo data on the real paths.

### 📶 Wi-Fi Security — active verification & Shield
- **Live safety tests** now *test* the connected network instead of only reading its label:
  **DNS integrity** (control-domain resolution vs an encrypted baseline), **HTTPS / TLS integrity**
  (a cert-validated handshake that catches interception — Android excludes user-installed CAs, so a
  proxy's certificate fails), and **Captive / injection** (a `generate_204` byte-check). Each reports
  **Pass / Fail / Inconclusive**.
- The **Safety Score reacts to tested tampering** — a detected hijack or interception docks the gauge,
  so the number reflects what was *proven*, not just the encryption type.
- **Shield this network** arms an on-device **DoH + monitoring** tunnel for the current network in one tap.
- **Shield-aware:** the DNS probe resolves over the *underlying* network (so it still tests the network
  while shielded), the DNS row reads **"Protected — DNS encrypted by Shield"** when armed, tests **re-run
  on toggle**, and the DNS-hijack penalty is waived once the Shield mitigates it.

### 🔑 Private Password Check (Breach Checker)
- Check whether a password has leaked **without it ever leaving your phone** — **k-anonymity**: only the
  first 5 characters of a SHA-1 hash are sent to the Pwned Passwords range API and the match happens
  on-device. **No API key required.**
- A **local strength meter** (entropy + common-password list + pattern detection) grades the password and
  estimates crack-time, producing a dual verdict (**breached / weak / strong**).
- A **"How is this private?"** explainer walks through the k-anonymity flow. Opens from a new entry card on
  the Breach Checker overview.

### 🛰️ Network Traffic Monitor — real tracker control
- The per-app **Block** control now genuinely sinkholes a tracker (a user blocklist honored by the tunnel),
  device-verified.
- Tap any **tracker card** for a dialog explaining *which* company it is, *what data it collects*, and *why*
  it's in the app; non-tracker destinations get an honest "not a known tracker" card.
- The four overview **stat tiles are tappable** with breakdowns (which trackers are blocked, which apps are
  phoning home, busiest destinations, top data users) and are **block-aware** — block a tracker and
  "Trackers blocked" rises while "Phoning home" falls. The blocked-connections finding opens a
  *which-trackers* breakdown.

### 🔧 Under the hood
- Active probes reuse the existing DNS-hijack probe and the **single unified VpnService** tunnel — **no new
  permissions**. The strength engine and k-anonymity check are entirely on-device. No Room schema change.

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
