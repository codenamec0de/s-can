# S'CAN — Android Privacy &amp; Background-Activity Monitor

**S'CAN** is an Android app that tells you, in plain language, what your phone is doing behind
your back — which apps touch the camera/mic/location in the background, whether your Wi-Fi and
DNS are safe, whether your accounts have been breached, and whether an incoming SMS is a scam —
and, where it can, **fixes** the problem with one tap.

- **Package:** `com.uow.scan` &nbsp;·&nbsp; **Version:** 1.4.7.5 (versionCode 10)
- **Platform:** Android 8.0+ (minSdk 26, targetSdk 34) &nbsp;·&nbsp; **Language:** Kotlin
- **Status:** active development / demo-readiness. Several tools are **Beta**; two are marked *Coming soon*.

---

## Features

| Tool | What it does | State |
|---|---|---|
| **Background-activity monitor** | A foreground service + notification-listener track which apps access camera, mic and location while in the background, with real OS-signed evidence (`OpAccessTracker`). The core of the app. | ✅ |
| **Wi-Fi Security** | Scans the connected and nearby networks for MITM risk, captive portals, weak crypto, and **evil-twin** APs (same SSID, different BSSID). V4 overview + per-network detail. | ✅ |
| **SMS Scam Detection** | Classifies incoming SMS as scam/safe. Works **fully offline** via an on-device classifier, and can optionally use a remote AI sidecar over TLS — failing back to on-device so it always produces a verdict. | ✅ |
| **Breach Checker** | Checks whether your email addresses appear in known data breaches (Have I Been Pwned). API key is injected from a gitignored `local.properties`. | ✅ |
| **DNS Leak Detection &amp; Protection** | Answers “can my network see or redirect the sites I visit?” — and now **fixes** it with a one-tap encrypted-DNS VPN. See below. | 🧪 Beta |
| **Terminator** | One-tap revocation of camera/mic/location for apps caught running in the background. | 🔜 Coming soon |
| **Network Traffic Monitor** | Shows where apps actually talk to on the network. | 🔜 Coming soon |

Plus: onboarding, Google/email auth (Firebase), scheduled scans (WorkManager), score history,
PDF report export, and a full settings suite (notifications, quiet hours, data retention).

---

## ⭐ DNS Leak Detection &amp; Protection (new in 1.4.7)

The DNS tool is built as **three cooperating layers**, all behind one screen (`DnsLeakActivity`):

1. **On-device detection (Tier A).** Reads the live network posture via `ConnectivityManager` /
   `LinkProperties` — the active resolver, whether Android Private DNS (DoT) is enforcing
   encryption, VPN status — and runs a **hijack probe** (`DnsHijackProbe`) that resolves stable
   anchor domains via the system resolver *and* a trusted public DoH baseline, flagging a
   rewritten/redirected lookup as SUSPECT. Runs in ~3 s; nothing leaves the device.

2. **Server-backed egress test (Tier B · “Deep test”).** Proves *where* your DNS actually exits.
   The app mints a one-time token and resolves `<token>.dnsprobe.scan-ai.xyz`; an authoritative
   name-server we run logs **which resolver IP actually queried it**, and a `/result` API enriches
   it with owner / ASN / geo (offline MaxMind GeoLite2). This is the half on-device detection
   physically cannot see. *(Backend lives in a separate `dnsprobe/` project — not in this repo.)*

3. **DNS Protection — the one-tap fix.** A local **DNS-only VPN** (`ScanDnsVpnService`) captures
   every DNS query and re-issues it **encrypted over DNS-over-HTTPS** to Cloudflare (`DohClient`,
   RFC 8484). The leak score then climbs into **PRIVATE** *honestly* — the verdict is keyed to the
   live tunnel, so it can never be cosmetic. It is a split tunnel: only the two virtual resolver
   IPs are routed in, so all other traffic is untouched.

**The honesty rule:** every number is earned. The screen reports “protected” only while the tunnel
is genuinely up, and the deep test names the *real* egress even when that is unflattering. A hidden
demo override (long-press the title) exists for deterministic stage runs, but never stands in for a
real posture.

> **Limitations (same as Android’s built-in Private DNS):** protects the *system* resolver — an app
> that hard-codes its own DNS/DoH bypasses it; only one VPN runs at a time; TCP/53 and &gt;4 KB answers
> aren’t handled yet.

**1.4.7.5 — hardening &amp; polish.** A consistency pass over the DNS tool (no change to the protection
tunnel itself): the scan now runs concurrently with its progress checklist for a snappier reveal; the
demo, verdict and finding-severity states were reconciled so the headline grade always matches its
evidence; the system-resolver lookups shared by both probe tiers were consolidated behind one helper;
and stale placeholder code/strings left over from the pre-wired deep test were removed.

A standalone architecture reference (diagrams + flows + screenshots) is generated alongside releases.

---

## Tech stack

- **Kotlin**, Android Views + ViewBinding, Material Components
- **Room** (KSP) for local persistence · **WorkManager** for scheduled scans
- **Retrofit / OkHttp / Gson** for the breach, SMS-sidecar and DNS-egress APIs
- **Firebase Auth** + Google Sign-In · **Coroutines** throughout
- **VpnService** (DNS-only DoH tunnel) for DNS Protection
- Glide (app icons), MPAndroidChart (dashboards), Biometric

## Project structure

```
app/src/main/java/com/uow/scan/
├─ DnsLeakActivity.kt, WifiSecurityActivity.kt, SmsScamActivity.kt, …   # screens
├─ vpn/        ScanDnsVpnService · DohClient · DnsPacket                # DNS Protection
├─ api/        DnsLeakProbe(Client/Service) · ScanAi* · HIBP*           # network clients
├─ util/       DnsLeakAnalyzer · DnsHijackProbe · DnsLeakProbe ·        # analyzers
│              PreferencesManager · …
├─ service/    ScanMonitorService · PrivacyNotificationListener         # background monitoring
├─ data/       Room database, entities, DAOs
└─ receiver/   Boot, app-install, SMS receivers
```

## Build

Toolchain (known-good / green):

| | Version |
|---|---|
| Android Gradle Plugin | 8.13.2 |
| Kotlin / KSP | 1.9.0 / 1.9.0-1.0.13 |
| Gradle | 8.13 |
| compileSdk / targetSdk / minSdk | 34 / 34 / 26 |

```bash
# Debug APK
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

**Required local config (gitignored):**

- `local.properties` — add `HIBP_API_KEY=<your key>` for the Breach Checker (blank is fine; the
  tool just won’t query). May also be supplied via a `HIBP_API_KEY` env var.
- `app/google-services.json` — Firebase config for auth.

> Note: the AGP 9 / Kotlin 2.2 / KSP upgrade currently breaks Room/KSP and is intentionally
> excluded — upgrade Room before retrying.

## Permissions (and why)

Each restricted permission is deliberate and minimal: `PACKAGE_USAGE_STATS` (background-activity
attribution), `ACCESS_FINE_LOCATION` / `NEARBY_WIFI_DEVICES` (Wi-Fi scan), `RECEIVE_SMS` (scam
detection), `BIND_VPN_SERVICE` (DNS Protection), foreground-service + notification permissions
(monitoring). No permission is requested before its tool is used.

## Privacy

S'CAN is privacy-first: SMS bodies are classified on-device by default; the DNS deep test logs only
the *resolver’s* IP (never the device’s); DNS Protection encrypts your lookups to a trusted resolver
rather than your ISP/router. The user is always in control — the OS shows its own VPN consent dialog
and a persistent key icon, and protection can be turned off at any time.

---

*S'CAN is a final-year project. © Çağrı (Charlie) Alaf.*
