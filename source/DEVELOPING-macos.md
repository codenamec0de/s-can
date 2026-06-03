# Developing &amp; debugging S'CAN on macOS

This is the **macOS working copy** of the S'CAN Android app — for continuing development and for
**debugging the app live during the presentation**. The code mirrors `scan-app` (and the `source/`
tree of the `s-can` monorepo); `main` tracks the latest release — currently **1.4.7.5**
(versionCode 10). See `README.md` for the full feature/architecture overview.

---

## 1. Prerequisites (macOS)

| Tool | Version | Notes |
|---|---|---|
| Android Studio | latest stable | Simplest way to get the SDK + an emulator |
| JDK | 17 | Required by AGP 8.13 — Android Studio's bundled JBR 17 is fine |
| Android SDK | compile/target **34**, min **26** | Install via Studio → SDK Manager |
| AGP / Kotlin / Gradle | 8.13.2 / 1.9.0 / 8.13 | **Pinned — do not bump.** The AGP 9 / Kotlin 2.2 / KSP jump breaks Room/KSP. |

A physical device (the test phone is a **Galaxy A17 / SM-A176B, Android 16**) or an emulator (API 26+).

## 2. Clone

```bash
git clone https://github.com/codenamec0de/scan-macos.git
cd scan-macos
```

## 3. Required local config (gitignored — you create these)

`local.properties` is **not** committed (it holds your machine's SDK path + an optional API key).
Create it in the repo root:

```properties
# macOS Android SDK path (Android Studio's default location)
sdk.dir=/Users/<you>/Library/Android/sdk
# Optional — only the Breach Checker tool uses it; blank is fine (it just won't query)
HIBP_API_KEY=
```

> `app/google-services.json` (Firebase auth config) **is** included, so Google / email sign-in
> works out of the box on a clone. That committed config is why this repo is **private** — keep it so.

## 4. Build &amp; run

```bash
# from the repo root
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# install on a connected device (USB debugging on)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and hit **Run**. First sync downloads Gradle 8.13 + deps.

## 5. Debugging during the presentation

**Deterministic DNS demo (the key one).** The DNS Leak screen has a hidden demo override:
**long-press the screen title** to cycle `Live → INTERCEPTED → PROTECTED` and back. Use it so the
on-stage result never depends on the venue Wi-Fi — a toast confirms the active mode.
- **INTERCEPTED** = worst-case pole: red gauge, "Lookups appear to be redirected", a confirmed-tamper
  (High) finding. Its deep test names the ISP egress.
- **PROTECTED** = green all-clear: encrypted to Cloudflare, no leak. Its deep test names Cloudflare.
- **Live** = real on-device detection of the current network.

**DNS Protection (the one-tap VPN fix)** cannot be started from `adb` — it's `BIND_VPN_SERVICE`-guarded,
so drive it from the UI (the OS shows its own consent dialog). Tail its logs with:

```bash
adb logcat -s ScanDnsVpn:V DnsHijackProbe:V
```

**Inspect screen state without fighting the UI.** The animated "V4" result screens don't reliably
`uiautomator dump` (the animation never idles) — prefer screenshots and `dumpsys`:

```bash
adb exec-out screencap -p > shot.png
adb shell dumpsys activity activities | grep -i mResumedActivity
```

**Live "Deep test" (Tier B)** reaches a small backend we run, so it needs network. On a clean network
the on-device check reports "No tampering detected," and the deep test names the real egress resolver.
Not required to demo the rest of the app.

## 6. Where everything else lives

- **`scan-app`** — standalone backup / release repo (same source tree, at repo root).
- **`s-can`** — the canonical monorepo; this app lives under `source/`, alongside the `dnsprobe/`
  Tier-B backend and other components.
- The **Tier-B egress backend** is deployed separately and is **not** needed to build or run the app —
  it's only exercised by the optional "Deep test".

> Release flow (for reference): commit `Release X.Y.Z` on the version branch → push to `scan-app` →
> mirror into `s-can` under `source/` via a clone→PR (never a direct history push). This `scan-macos`
> repo is a development copy; it does not need that ceremony.

---

*S'CAN is a final-year project. © Çağrı (Charlie) Alaf.*
