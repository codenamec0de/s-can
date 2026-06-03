# S'CAN — Project State (accurate as of 2026-06-03)

> **🟢 LIVE STATUS — 2026-06-04 (demo-ready):**
> - **App:** v1.5 Pre-Release (Stable), versionCode 12, built from GitHub `release/v1.5 @ 3ff2894` and **installed on the demo phone** (Galaxy A17 `R5GL139AA5L`) — launches clean, no crashes.
> - **AI SMS classifier** (`scan-api.scan-ai.xyz` → Cloudflare Tunnel → shim → Ollama `scan-classifier:v1`): UP, verified **end-to-end on-device** (warm ~0.6 s, cold ~2.5 s — pre-warm before showing). Cert pin matches the app; edge cert valid to 2026-08-04.
> - **DNS Tier-B** (`dnsprobe-api.scan-ai.xyz`, Hetzner): the `/result` API had been DOWN (a TLS listening-socket wedge that blocked the accept loop); **FIXED 2026-06-04** — `api.py` now does per-connection TLS — and verified end-to-end (resolver org/ASN/geo enrichment returns). *This resolves `external-unverifiable` item (1) below.*

> **CURRENT DEMO BUILD = v1.5 Pre-Release (Stable) — CODE-VERIFIED.** versionName "1.5 Pre-Release (Stable)", versionCode 12, `com.uow.scan`, cloned and re-verified line-by-line on the monorepo `s-can`, branch `release/v1.5`, HEAD `3ff2894` (2026-06-03 22:01), working tree clean. Android app under `/home/cagri/s-can/source`; minSdk 26 / compileSdk 34 / **targetSdk 34**.
> **FRAMING FLIP:** v1.5 is no longer "device-verified-on-Mac / not re-verifiable here". It is **`code-verified (release/v1.5 @ 3ff2894)`** — that is now the dominant provenance tag, and it points file:line into `/home/cagri/s-can/source`. The old `macos-device-verified-doc` framing is RETIRED for everything the code confirms.
> **Provenance legend:**
> - `code-verified (release/v1.5 @ 3ff2894)` — re-verifiable line-by-line in `/home/cagri/s-can/source`, working tree clean. The dominant tag.
> - `external-unverifiable` — depends on off-repo infrastructure. **Exactly four items remain:** (1) the Hetzner Tier-B `/result` SERVER origin liveness (reported DOWN); (2) the Tier-B server-side geo dataset (MaxMind GeoLite2 vs iptoasn); (3) the live FastAPI shim + Ollama `scan-classifier:v1` model identity/health/latency; (4) the product marketing domain (`scan.app` vs `scan.xyz`).
> - `linux-code-verified (v1.4.7.5 baseline)` — history tag, kept only where a v1.4.7.5 fact is unchanged in v1.5.
> - `flag-gated-off` — real, built, working code that ships disabled by default behind a pref/flag (NOT demo fakery, NOT a stub).
> - `stub-or-scaffold` — present in the tree but deliberately not wired into the live flow (returns a fixed value, raises, or is canned demo data).
>
> Companion doc: `/home/cagri/Desktop/arch.md` (architecture). The two are kept consistent — same 4-tab shell, same s-can hashes, same `0.0.0.0/::` sinkhole, same Cloudflare-only DoH, same provenance framing.

---

## 1. What it is

S'CAN (formerly Guardify) is a final-year UOW Android privacy/security app by Çağrı (Charlie) Alaf, single author.

**Tagline:** On-device privacy and security scanning + a self-hosted AI SMS scam classifier + DNS leak detection/protection + (new in v1.5) a real network traffic monitor that sees, names, and blocks trackers on the phone.

---

## 2. Identity

| Field | Value |
|---|---|
| App name | S'CAN (formerly Guardify) |
| **Demo build** | **1.5 Pre-Release (Stable)** — versionName "1.5 Pre-Release (Stable)", versionCode 12. `code-verified` on `s-can` branch `release/v1.5`, HEAD `3ff2894` (2026-06-03 22:01, clean). **THIS IS THE DEMO BUILD.** (`source/app/build.gradle.kts:21-29`) |
| **Last distinct baseline** | **1.4.7.5** — versionCode 10, s-can `7d3a312`. Retained only as history (`linux-code-verified (v1.4.7.5 baseline)`). |
| Package | `com.uow.scan` (namespace `com.uow.scan`; old `com.uow.guardify` fully gone) |
| Platform | Android — minSdk 26, compileSdk 34, **targetSdk 34**; classic Views/Fragments + ViewBinding + Material3 DayNight (no Compose, no DI framework); Kotlin |
| Author | Çağrı (Charlie) Alaf — single author, final-year UOW student |
| Source tree | `/home/cagri/s-can` — monorepo, app under `source/`; `scan-ai/` Python sidecar (dead in live flow), `sensorprobe/`, `docs/`, `README.md`, `CHANGELOG.md`. No `dnsprobe/` dir (Tier-B server is external). |
| Release config | `buildTypes.release` sets `isMinifyEnabled=true` + `isShrinkResources=true` (R8/ProGuard + 48-line `proguard-rules.pro`) — **but there is NO `signingConfigs` block**, so a release build would be unsigned; the demo runs **debug builds** (default debug keystore). NO Play Billing dependency (`grep billing` → 0). (`source/app/build.gradle.kts:36-45,59-121`) |
| Toolchain | AGP 8.13.2 / Kotlin 1.9.0 / KSP 1.9.0-1.0.13 / Gradle 8.13; JDK 21. AGP 9 / Kotlin 2.2 / KSP bump breaks Room 2.6.1/KSP and is intentionally declined (move Room to 2.7.x first). **targetSdk 35 + Play Billing are FUTURE Pro requirements, NOT current code** — Pro monetization is unbuilt in this tree. |

---

## 3. AI model — real & running

- **Served model identity (`external-unverifiable`):** the production classifier is `scan-classifier:v1` — a server-side Ollama tag. **The string `scan-classifier` appears NOWHERE in the repo** (no `.kt`/`.py`/`.md`/`.json` hit); it lives only on the author's host. Per memory it is a Modelfile / `ollama cp` clone of `qwen3.5:latest` (~7B params, ~Q4, 6.6 GB), a **prompt-engineered wrapper, NOT a fine-tuned model** — but that identity/health/latency cannot be confirmed from this repo.
- **Role:** optional remote SMS scam classifier sidecar; gives richer scam reasoning than the on-device keyword/example classifier. **It is strictly opt-in** (see §10).
- **How the app calls it (`code-verified` on the CLIENT side):** Android Retrofit `ScanAiApiService` (`@POST("classify")`, request `ClassifyRequest{sms}` → `ClassifyResponse{verdict, reasoning, confidence, model?, latency_ms?}`; `@GET("health")` → `HealthResponse{status, model}`) — `ScanAiApiService.kt:15-37`. Default URL `https://scan-api.scan-ai.xyz/` (`PreferencesManager.kt:26`) → Cloudflare Tunnel `scan-api` → **EXTERNAL** FastAPI/uvicorn shim (`scan-ai-shim/main.py`, on the dev host) → Ollama `scan-classifier:v1`. OkHttp is cert-pinned to `scan-api.scan-ai.xyz` (LE-E7 intermediate + ISRG Root X1; `ScanAiClient.kt:24-26,39-49`; package `com.uow.scan.api`, NOT `network/`). The Android data classes match the **external shim** contract, NOT the in-tree `/v1` schema.
- **The in-tree `scan-ai/main.py` is DEAD in the live flow (`stub-or-scaffold`):** it defaults `MODEL_NAME='llama3.1:8b'` and serves ONLY `/v1/*` endpoints (`/v1/health`, `/v1/classify`, `/v1/url-check`, `/v1/visual-brand-check`, `/v1/feedback`, `/v1/admin/token`), while the app calls `@POST("classify")` with **no `/v1` prefix** on the external shim. The in-repo sidecar server is NOT the production server (`scan-ai/main.py:3,19-20,68,236,252,308,343` vs `ScanAiApiService.kt:15-16`).
- **Fallback (`code-verified`):** on-device in Kotlin (`ScanAiFallback.kt:61-98`) reads `assets/scam_fallback.json` (118 lines): `examples` substring-match → `scam_keywords` (conf 0.75) → `safe_keywords` (conf 0.75) → `default_unverifiable` (SUSPICIOUS, conf 0.5); every response tagged `model='cached-fallback'`. AU-specific signatures (AusPost typosquat, ATO refund, myGov impersonation, "tebrikler").

> **DEFAULT POSTURE — RESOLVED (`code-verified`).** SMS is screened **on-device by default**; the LLM is strictly **opt-in**. `isSmsFallbackEnabled` defaults **TRUE** (`PreferencesManager.kt:114-115`), and `SmsForwardWorker.classifyWithFailover` checks it FIRST, returning `ScanAiFallback.classify()` **without building any network client** when true (the default). Only when the user turns fallback OFF does the worker hit the remote `ScanAiClient`, then fail over on-device on null/`!isSuccessful`/exception (`SmsForwardWorker.kt:83-101,46-65`). **Out of the box, the SMS body never leaves the device.** SMS detection itself is also opt-in via `isSmsScamDetectionEnabled` (default FALSE). If the LLM path is demoed, phrase the data flow honestly — see §10/§11.

---

## 4. Feature inventory

### REAL / LIVE (`code-verified (release/v1.5 @ 3ff2894)` unless noted)

| Feature | Status | Real since | One-line |
|---|---|---|---|
| **Network Traffic Monitor (NTM) Stages 0-3 — see → name → block** ⭐NEW in v1.5 | LIVE | 1.5 Pre-Release | Watch which apps phone home, name the trackers, and block them on-device; launched from a Home tool-card |
| **NTM Stage 4a — offline ASN/geo enrichment** ⭐NEW in v1.5 | LIVE | 1.5 Pre-Release | See the real org/ASN/country behind each connection, fully offline (bundled iptoasn) |
| `NetworkMonitorActivity` / `NetworkAppDetailActivity` (NTM screens) | LIVE — fully wired | 1.5 Pre-Release | Real phase machine (OFF/STARTING/EMPTY/LIVE) + per-app drilldown + per-host allow/user-block. (The activity's own KDoc `:62-63` "design-first/stubbed" is STALE — the code below it is wired.) |
| Background data + sensor egress monitor (the real engine) | LIVE | 1.4.7.5 + 1.5 | Flags background apps holding sensitive perms that move >50 KB or use camera/mic |
| SMS scam detection — on-device default path | LIVE | 1.4.7.5 + 1.5 | Inbound SMS screened privately on-device; text never leaves the phone (default) |
| DNS Leak Detection Tier A (on-device analyzer + hijack probe) | LIVE | 1.4.7.5 + 1.5 | On-device check of DNS posture + hijack probe vs trusted DoH anchors |
| DNS Protection — one-tap DoH VpnService | LIVE | 1.4.7 (DNS-only) / 1.5 ("encrypt" mode of unified tunnel) | One tap stands up a real DNS-only local VPN re-issuing every query over HTTPS to **Cloudflare only**; device scores 99/PRIVATE |
| Wi-Fi security analyzer (read-only) + v1.5 true 0-100 score + active verification | LIVE | 1.4.6 (analyzer) / 1.5 (0-100 rubric + active probes) | Read-only multi-signal Wi-Fi analysis with a fair 0-100 safety score plus real DNS/TLS/captive probes |
| Breach Checker (HIBP email) + v1.5.0 k-anonymity Password Check | LIVE | 1.4.7.5 (email) / 1.5.0 (Password Check) | Check email against HIBP; check passwords privately on-device (SHA-1, only a 5-char prefix leaves) |
| Device security score (0-100, nine-check posture audit) | LIVE | 1.4.7.5 + 1.5 | Nine-check device posture audit |
| App integrity checks | LIVE | 1.4.7.5 + 1.5 | Inspect each app's signature, debuggable/cleartext/backup flags, installer trust |
| Full device scan pipeline | LIVE | 1.4.7.5 + 1.5 | Enumerate + risk-classify every installed app in parallel |
| Camera/mic sensor-access attribution | LIVE | 1.4.7.5 + 1.5 | Which app used camera/mic in the background, with real evidence (camera+mic ONLY) |
| Per-app permission management action | LIVE | 1.4.7.5 + 1.5 | Deep-links to Android's app-settings page (honest, no fake revoke) |
| PDF/CSV/JSON report export | LIVE | 1.4.7.5 + 1.5 | Export scan/device/breach data via FileProvider |
| `sensorprobe` test harness | LIVE (internal QA) | 1.4.7.5 | Internal QA app that validates S'CAN's sensor detection — not user-facing |

### FLAG-GATED-OFF (real, built, working code; OFF by default)

| Feature | Status | Real since | One-line |
|---|---|---|---|
| **NTM Stage 4b — full-capture SNI forwarder** | **flag-gated-off** (NOT "demo-only") | 1.5 Pre-Release | Real userspace TCP/UDP forwarder + SNI sniffer (`vpn/capture/{IpPacket,SniSniffer,CaptureEngine}`, `MAX_FLOWS=512`, `DEBUG_CAP=false`). Two distinct prefs, both default FALSE: the visible **"Show full hostnames"** switch is `netmon_capture` = **display-only** and does NOT enable capture; the actual forwarder is `netmon_forwarder`, toggled ONLY by a **hidden long-press** on that switch. Device-run numbers (~39 flows, FACEBOOK AS32934) remain `external-unverifiable`. |

### EXTERNAL / OPT-IN

| Feature | Status | Real since | One-line |
|---|---|---|---|
| SMS scam detection — remote AI sidecar (`scan-classifier:v1`) | opt-in; client `code-verified`, server `external-unverifiable` | 1.4.7.5 (opt-in) / 1.5 | Optional cert-pinned call to a self-hosted ~7B LLM for richer scam reasoning (only when fallback toggled OFF) |
| DNS Leak Tier B — server-backed egress deep test | client `code-verified`; server `external-unverifiable` | 1.4.7 (deployed) / 1.5 | Proves WHERE DNS egresses by resolving a unique token against the author's own authoritative server. `/result` origin was reported DOWN. |
| DemoDataSeeder (hidden long-press) | demo aid (disclosed) | 1.4.x + 1.5 | Long-press the Home greeting head runs `DemoDataSeeder.seed(ctx)` — a stage aid that seeds sample alerts/SMS, NOT live detection (`HomeFragment.kt:151-162`) |

### STUB / SCAFFOLD / DEAD (`stub-or-scaffold`)

| Feature | Status | One-line |
|---|---|---|
| App Terminator (Privacy Enforcer) | stub/scaffold | Guided manual revoke works; `TerminatorEngine.tryShizukuRevoke()` is a **STUB that always returns false** (Shizuku auto-revoke NOT integrated). Screens render canned `TerminatorDemoData`. Present as guided-only / Coming-soon (`TerminatorEngine.kt:50-61,92-107`). |
| URL safety analyzer (server-side phishing) | dead | `scan-ai/url_analyzer.py` wired only to the dead `/v1/url-check`; nothing live calls it — do not present. |
| Visual brand-impersonation check (VLM) | skeleton | `scan-ai/visual_brand_check.py` = explicit "Phase 6 skeleton — do not call from production" (Qwen2-VL-7B); `__main__` raises `SystemExit` — do not present. |
| In-tree `scan-ai/main.py` (`/v1/*`) | dead | Defaults `llama3.1:8b`, serves only `/v1/*`; superseded by the external shim — not the production server. |
| `MonitorFragment` (per-app monitoring tab) | dead/removed | Exists at `ui/monitor/MonitorFragment.kt` but **0 instantiations**; `MainActivity` imports only Home/Audit/Alerts/Settings. There is NO Monitor tab (see §5). |

---

## 5. NTM resolution (RESOLVED — `code-verified`)

**RESOLVED YES.** The feature previously logged as the "Network Activity Monitor" Coming-soon card is the **Network Traffic Monitor (NTM)**, and it is **fully present and wired in versionCode 12** — `NetworkMonitorActivity.start(ctx)` is launched from `HomeFragment.kt:373`, and `ScanDnsVpnService.startMonitor(...)` brings the tunnel up in monitor mode (`ScanDnsVpnService.kt:406-415`). This was open-question #1; it is now closed by the code read. NTM is reached as a **Home tool-card Activity**, NOT a tab.

- **See (Stage 0-1):** `monitor` mode attributes each DNS query to its owning UID via `ConnectivityManager.getConnectionOwnerUid` (IPPROTO_UDP, src/dst `InetSocketAddress`), guarded SDK<Q → `INVALID_UID=-1`; 5-tuple parsed by `vpn/DnsPacket.parseUdp` (`ScanDnsVpnService.kt:213,273-285`). `NetworkMonitorActivity` uses `NtmLiveRepository` only when `isNetMonActive && !isNetMonDemoMode` (else `NtmDemoData`); the repository merges `DataUsageHelper.getAllAppsDataUsage` (`NetworkStatsManager.querySummary`, Wi-Fi+mobile, 24h, cross-UID — NOT `queryDetails`) with `NtmStore` hosts + `TrackerDomainMatcher`. Per-dest bytes/ports stay unset (proto `""`) on DNS-only so **nothing is fabricated**; sparkline uses real `TrafficStats` deltas (`NtmLiveRepository.kt:125-172`; `DataUsageHelper.kt:166-223`).
- **Name (Stage 2 + 4a):** `TrackerDomainMatcher` compiles **exactly 260** `network_signature` regexes (`Pattern.find`, anywhere-in-host, memoized) from `assets/trackers.json`, which holds **432 tracker objects (260 with a non-empty `network_signature`)** (`TrackerDomainMatcher.kt:11-20,42-70`).
- **Stage 4a — offline ASN/geo enrichment:** `IpAsnDb` binary-searches `assets/ip2asn_ranges.bin` (**5,368,216 bytes**) + `ip2asn_names.tsv` (**2,424,644 bytes**) for org/ASN/country from the public-domain **iptoasn** dataset, fully offline; fed by `DnsMessage.firstAnswerIp` → `ScanDnsVpnService.enrich()` → `NtmStore.enrichHost` (`IpAsnDb.kt:29-73`; `ScanDnsVpnService.kt:228-233`). Example: "GOOGLE-CLOUD-PLATFORM · AS396982 · US". **Accuracy note:** the `IpAsnDb` KDoc still references `.gz` filenames (`:9-11`), but `assets.open()` opens the **raw** `.bin`/`.tsv` (`:33,38`) — no `.gz` files exist on disk. The KDoc naming is stale; the raw names are correct.
- **Block (Stage 3) — full three-tier rule (`ScanDnsVpnService.kt:291-295`):**
  1. if `isNetMonAllowed(host)` → **return false** (user allow wins);
  2. else if `isNetMonUserBlocked(host)` → **return true** (explicit per-host user block);
  3. else → `cfg.block && NtmBlocklist.isBlocked(host)` (the curated list, only while the global "Block trackers & ads" toggle is on).
  Blocking returns `DnsMessage.buildBlockResponse` = **A=0.0.0.0 / AAAA=:: as an unroutable NOERROR answer (TTL 60), NXDOMAIN for other qtypes — this is the Pi-hole approach, NOT a 127.0.0.1 loopback rewrite** (`DnsMessage.kt:44-76`). `NtmBlocklist` loads `assets/ntm_blocklist.txt` — **3507 listed entries / 3503 unique domains (3508 lines incl. a 1-line `pgl.yoyo.org` / Peter Lowe header)**; if a single figure is wanted, "~3,500 curated domains." It deliberately **excludes first-party domains so apps don't break** (`NtmBlocklist.kt:24-54`).

**Key honesty points:** NTM Stages 0-3 run on the existing **DNS-only tunnel** (no packet proxy), add **NO new permissions** (reuse `BIND_VPN_SERVICE` + `getConnectionOwnerUid`), and keep their **live ledger in-memory** (`util/NtmStore`, `ConcurrentHashMap<Int,AppStat>`, bounded `MAX_HOSTS_PER_APP=256`, `reset()` on bring-up) to avoid a Room v7→v8 destructive wipe — the live view is forgotten when closed. The public README files NTM under "coming next" (badge still v1.4.7), which **understates** the shipped state; the honest status is **LIVE** (Stages 0-3 + 4a) with Stage 4b `flag-gated-off`.

---

## 6. Infrastructure (live state)

| Component | Role | Status | Notes |
|---|---|---|---|
| `scan-api.scan-ai.xyz` | Public HTTPS entry for the SMS classifier (`/classify`, `/health`); cert-pinned by the app | live (external) | `external-unverifiable` model identity behind it. |
| Cloudflare Tunnel `scan-api` | Exposes the dev host's local shim publicly; single ingress → `http://localhost:8000` | live (external) | dnsprobe hostnames are NOT in this tunnel. |
| `scan-ai-shim` (EXTERNAL FastAPI/uvicorn `127.0.0.1:8000`) | Production classifier server: validates input, applies few-shot prompt, calls Ollama, returns `ClassifyOut`. This — **NOT** the in-tree `scan-ai/main.py` — answers scan-api | live (external) | On the author's host; not in this repo. The in-tree server serves only `/v1/*` and defaults to `llama3.1:8b`. |
| Ollama (`127.0.0.1:11434`) | Local LLM backend holding `scan-classifier:v1` (server-side tag) | live (external) | `external-unverifiable`. |
| Hetzner VPS (DNS Tier B), Falkenstein | DNS Tier B as two bare systemd units (**NO Docker** — deliberate, to dodge the Mullvad/Docker iptables trap): `responder.py :53` + `api.py :443 /result` | external | No `dnsprobe/` dir in this repo; only the on-device CLIENT is here. |
| dnsprobe responder `:53` (`dnsprobe.scan-ai.xyz`) | Authoritative NS; logs which resolver IP queried each token, returns a sentinel | external | Proven end-to-end historically via dig + recursion. |
| `dnsprobe-api.scan-ai.xyz /result` | Tier B egress lookup API, Cloudflare-proxied → VPS `:443`; NOT cert-pinned by design | **`/result` origin REPORTED DOWN** (`external-unverifiable`) | The on-device CLIENT (`DnsLeakProbe` in `util/`, `DnsLeakProbeClient`/`DnsLeakProbeService` in `api/`) is `code-verified`: 24-char token, resolves `<token>.dnsprobe.scan-ai.xyz` via the **SYSTEM resolver** (never DoH), polls `/result/{token}`, deliberately NOT cert-pinned (`DnsLeakProbe.kt:31-77`; `DnsLeakProbeClient.kt:11-20`). AUTO shows "couldn't confirm"/ERROR rather than faking a pass. **Restart the VPS unit or use the long-press override before the demo.** |
| Cloudflare DNS zone `scan-ai.xyz` | Hosts the zone; NS-delegates `dnsprobe.scan-ai.xyz` to the VPS (grey), proxies `dnsprobe-api` (orange, SSL Full), runs the scan-api tunnel | live (external) | — |
| Eval harness (`run_eval.py`) | Multi-model SMS eval (accuracy, recall, p50/p95) backing the model choice | external | Outside the app tree. |
| Repo topology (`s-can`) | Canonical monorepo; app under `source/`, Python sidecar under `scan-ai/` (dead in live flow), `sensorprobe/`, `docs/` | `code-verified` | Branch `release/v1.5`, HEAD `3ff2894`, working tree clean. |
| Product website (`scan.app` vs `scan.xyz`) | Privacy policy / support site | doc-resolved → `scan.app`; live status `external-unverifiable` | `source/ARCHITECTURE.md` carries a dated (2026-06-02) accuracy note "the product site is **scan.app** … There is no scan.xyz"; `source/RELEASE_NOTES.md:33` has a single **stale** `scan.xyz` mention (a typo). Backend `scan-ai.xyz` is consistent everywhere. Whether `scan.app` is registered/live is not determinable from the repo. |

---

## 7. Version timeline (s-can `git log`, `code-verified`)

- **1.4.2** — `a236c01`.
- **1.4.3** — `37cfcce` — Phase 0/1/2 sensor-attribution overhaul (`OpAccessTracker` camera/mic attribution); `sensorprobe` QA harness; PDF export crash fixed.
- **1.4.4** — `13d562a` — baseline SMS UI + `ScanAiClient` + `scam_fallback.json` + full sensor-attribution pipeline.
- **1.4.5** — `f98234f` — on-device SMS classification made the default; behaviour-gated background monitoring; risk-gating reworked; HIBP key moved out of source into `local.properties`/BuildConfig.
- **1.4.6** — `acb1945` — DNS Leak Tier A; Wi-Fi nearby/evil-twin/OUI; RadarPulseView/SignalBarsView added.
- **1.4.7** — `a09af6e` — DNS Protection one-tap DoH VpnService (`ScanDnsVpnService`); DNS Leak Tier B deployed on Hetzner; DNS Leak/Protection shipped as Beta; IPv6-literal crash fixed.
- **1.4.7.5** — `7d3a312` — last fully-distinct baseline (versionCode 10). DNS tool hardening: `SystemDnsLookup` merged dup pools, `exposedScenario` grades INTERCEPTED, dead `openPrivateDnsSettings()`/stale strings removed, `startScan` concurrent, tamper finding WARN→BAD.
- **1.5 Pre-Release (Stable)** — **`3ff2894`** (HEAD of `release/v1.5`, 2026-06-03 22:01, versionCode 12) — **CURRENT DEMO BUILD.** Unified mode-driven `ScanDnsVpnService` (`Config(encrypt,monitor,block,capture)`); NTM Stages 0-3 (see→name→block via `0.0.0.0/::` DNS sinkhole) + Stage 4a offline iptoasn enrichment + Stage 4b full-capture forwarder `flag-gated-off`; v1.5.0 active Wi-Fi verification (DNS/TLS/captive probes) + true 0-100 rubric + "Shield this network"; v1.5.0 k-anonymity Private Password Check; lime-vector brand refresh (static splash radar + always-on animated dashboard radar badge **top-RIGHT**); DayNight theme (`MODE_NIGHT_FOLLOW_SYSTEM`).

> **Hash provenance:** the old doc cited `4f3a6ac / 9aece33 / 966e533 / c4bd634 / f00d6bc / 5de3972 / dd31fe0 / 79173f0` — those belong to the **separate `scan-macos-main` repo's unrelated history** and have been replaced with the s-can hashes above. The v1.5 doc now anchors to `3ff2894`.
>
> **Doc/marketing lags the code:** `CHANGELOG.md` only documents through 1.4.7; the `README.md` badge still reads **v1.4.7** and files **NTM + Terminator** under "Roadmap — V1.5 and beyond — coming next" (`README.md:160-167`) even though both are built and wired; `source/RELEASE_NOTES.md` documents v1.5.0 then v1.5 Pre-Release (Stable) (2026-06-02).

---

## 8. Environment / provenance notes

- **SINGLE CODE-VERIFIED ENVIRONMENT:** v1.5 is now cloned and re-verified line-by-line at `/home/cagri/s-can` (`release/v1.5` @ `3ff2894`, clean). Every fact tagged `code-verified (release/v1.5 @ 3ff2894)` points file:line into `/home/cagri/s-can/source`. The old "device-verified-on-Mac, not re-verifiable here" caveat is RETIRED.
- **Unified VpnService (`code-verified`):** ONE `ScanDnsVpnService`, `data class Config(encrypt, monitor, block, capture)` default `(encrypt=true, …false)`; `start()`=encrypt-only DNS Protection, `startMonitor(block,encrypt,capture)`=NTM/Shield. `onStartCommand` reconfigures **in place** — encrypt/block flip live via `@Volatile config`; **capture only on a fresh bring-up** because it changes routing. Stages 0-3 route ONLY `V4_DNS/32` (10.111.222.2) + best-effort V6; capture routes `0.0.0.0/0` + `addDisallowedApplication(self)`, leaving IPv6 unrouted. Liveness = process-global `@Volatile tunnelUp`. UDP-only DNS parse, **drops TCP/53**, MTU 4096 (`ScanDnsVpnService.kt:59-114,128-141,159-164,184-206,373,393-414`). New vpn files: `vpn/DnsMessage.kt`, `vpn/DnsPacket.kt`, `vpn/DohClient.kt`, `vpn/capture/{IpPacket,SniSniffer,CaptureEngine}`.
- **DoH is CLOUDFLARE-ONLY (`code-verified`):** `DohClient` resolves over `cloudflare-dns.com` only (RFC 8484, `application/dns-message`), bootstrapped from hardcoded anycast IPs `1.1.1.1 / 1.0.0.1 / 2606:4700:4700::1111/::1001`. **There is NO Google 8.8.8.8 fallback in the protection tunnel** — Google `dns.google` appears only in the separate Tier-A `DnsHijackProbe` baseline, a different code path (`DohClient.kt:39-56` vs `DnsHijackProbe.kt:60,151`).
- **DNS sinkhole = unroutable, NOT loopback (`code-verified`):** `DnsMessage.buildBlockResponse` returns `A=0.0.0.0`/`AAAA=::` NOERROR + NXDOMAIN for other qtypes — the Pi-hole approach so `connect()` fails fast and locally. NO 127.0.0.1 rewrite anywhere (`DnsMessage.kt:44-76`).
- **Shell is 4 TABS via a CUSTOM `LinearLayout` (`code-verified`):** `MainActivity.kt:26` `enum class Tab{HOME,APPS,ACTIVITY,SETTINGS}`; `selectTab()` instantiates only `HomeFragment`/`AuditFragment`/`AlertsFragment`/`SettingsFragment` (`:111-116`); click-nav wires `navHome`/`navApps`/`navActivity`/`navSettings` (`activity_main.xml:30,63,97,131`). `res/menu/bottom_nav_menu.xml` has 5 items but is a **DEAD ORPHAN** (0 references in `app/src/main`) — it is NOT a 5-tab nav. There is **NO Monitor tab**; `MonitorFragment` exists but is never instantiated; NTM is a Home tool-card Activity.
- **BRAND REFRESH (v1.5, `code-verified`):** one lime VECTOR mark everywhere; splash = static radar; dashboard = always-on ANIMATED radar badge (`view_scan_logo_live.xml`, `RadarPulseView` + rotating sweep) **TOP-RIGHT** of the top bar per its KDoc (`view_scan_logo_live.xml:4`; `HomeFragment.kt:90,95,98-113`); bell sits top-right too. System accent flipped cyan → lime.
- **DAYNIGHT THEME (v1.5, `code-verified`):** `Theme.Scan = Theme.Material3.DayNight.NoActionBar`, font `montserrat`; `ScanApp` sets `MODE_NIGHT_FOLLOW_SYSTEM`. Accent night `v4_accent=#22C55E` / light `#15803D` (`themes.xml:8,27`; `values-night/colors.xml:26`; `values/colors.xml:27`; `ScanApp.kt:27`). **Rule: never hardcode `@color/white`/hex on a `v4_*` surface (invisible in light mode).**
- **No app-set `FLAG_SECURE` (`code-verified`):** `grep FLAG_SECURE` across `source/app/src/main` returns **zero hits**. The app does NOT set FLAG_SECURE. Any blank-screencap behaviour on the device is OS/renderer-level (Vulkan), not an app-set flag — verify function via `topResumedActivity`/Displayed + logcat and hand the visual check to Çağrı.
- **Permissions (`code-verified`):** `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `ACCESS_FINE_LOCATION`, `NEARBY_WIFI_DEVICES (neverForLocation)`, `QUERY_ALL_PACKAGES`, `RECEIVE_SMS` (no `READ_SMS`), `PACKAGE_USAGE_STATS`, `FOREGROUND_SERVICE(+DATA_SYNC+SPECIAL_USE)`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. **NO `CHANGE_WIFI_STATE`** (Wi-Fi is read-only). `BIND_VPN_SERVICE` is a service-level `android:permission` on `ScanDnsVpnService` (`manifest:223`), not a uses-permission; `BIND_NOTIFICATION_LISTENER_SERVICE` via `PrivacyNotificationListener` (`manifest:241`). **FGS type = `specialUse`** on BOTH `ScanDnsVpnService` and `ScanMonitorService`; `TestDataUsageService` is `dataSync`; **`systemExempted` appears nowhere** (`manifest:33-35,205,215,224`).
- **Room (`code-verified`):** `ScanDatabase` is `@Database(version=7, fallbackToDestructiveMigration)`, KSP-generated; DAOs alert/monitoredApp/scanResult/deviceCheck/breachResult/appSettings/smsVerdict/weeklyStats/permissionAccess. **NTM writes nothing to Room** (`ScanDatabase.kt:27,38,43-51,64`).
- **Transport (`code-verified`):** `ScanAiClient` cert-pins `scan-api.scan-ai.xyz` (LE-E7 + ISRG Root X1); `DnsLeakProbeClient` deliberately NOT pinned; `network_security_config.xml` (`:11`) denies cleartext for ALL hosts.
- **Wi-Fi active verification lives in `util/WifiActiveTests.kt` + `WifiSecurityActivity.kt`, NOT the analyzer (`code-verified`):** `activeCredit(shielded)` = DNS-PASS +2 / TLS-PASS +3 / captive-PASS +2 / Shield +8 → max +15; `scoreDock(shielded)` = DNS-FAIL +40 only when NOT shielded / TLS-FAIL +45 / captive-FAIL +20; `applyDockedScore = score + credit − dock`, then `coerceIn(floor,100)` (`WifiActiveTests.kt:34-53`; `WifiSecurityActivity.kt:453-456`). Probes are real network calls: TLS = HTTPS HEAD to `https://cloudflare-dns.com/` with the default trust store (user-CA excluded → `SSLException`=FAIL); captive = `http://connectivitycheck.gstatic.com/generate_204`; DNS reuses `DnsHijackProbe` (`WifiActiveTests.kt:75-119`). **"Shield this network" = `startMonitor(block=true,encrypt=true,capture=false)`** (encrypt+block+monitor), NOT a pure DoH toggle. Passive scan weights include SIGNAL=4 and auth up-to-44; MAC's 7 points are credited for `macRandomized==true` OR `==null` (Android 10+ default) to avoid capping modern devices at 93.

---

## 9. Corrected stale beliefs

- **"v1.5 is device-verified-on-Mac / not re-verifiable here"** is RETIRED: v1.5 is now `code-verified` at `/home/cagri/s-can` (`release/v1.5` @ `3ff2894`, clean).
- **"Shipped shell is a 5-tab BottomNavigationView (… Monitor …)"** is WRONG: it is **4 TABS via a custom `LinearLayout`** (Home/Apps/Activity/Settings). `bottom_nav_menu.xml` (5 items) is a **dead orphan**; there is no Monitor tab; NTM is a Home tool-card.
- **"DNS sinkhole resolves to 127.0.0.1 loopback"** is WRONG: it returns **`A=0.0.0.0` / `AAAA=::`** (unroutable NOERROR, NXDOMAIN otherwise) — the Pi-hole approach, no loopback rewrite.
- **"DoH protection falls back to Google 8.8.8.8"** is WRONG: the protection tunnel is **Cloudflare-only**; Google appears only in the separate Tier-A hijack probe.
- **"The app sets FLAG_SECURE"** is WRONG: FLAG_SECURE is **not set anywhere** in the app source.
- **"Signed DEBUG build, no R8/ProGuard/release shrinking"** is corrected: **R8/ProGuard + resource shrinking ARE configured** for release; there is **no release `signingConfig`**, so the demo runs **debug** builds.
- **Commit hashes `4f3a6ac / 9aece33 / 966e533 / c4bd634 / f00d6bc / 5de3972 / dd31fe0 / 79173f0` are WRONG (scan-macos repo):** the real s-can hashes are 1.4.5 `f98234f`, 1.4.6 `acb1945`, 1.4.7 `a09af6e`, 1.4.7.5 `7d3a312`, **1.5 `3ff2894`**.
- **"targetSdk 35 / Play Billing in the shipped build"** is WRONG: shipped is **targetSdk 34**, no Billing dependency; those are FUTURE Pro requirements.
- **Stage 4b is `flag-gated-off`, NOT "demo-only":** it is real, built, working code behind two distinct default-FALSE prefs — `netmon_capture` (the visible "Show full hostnames" switch = **display-only**) vs `netmon_forwarder` (the actual forwarder, hidden long-press).
- **Terminator auto-revoke is a STUB:** `tryShizukuRevoke()` always returns false — guided-manual-revoke only.
- **The production classifier is the EXTERNAL shim, not in-tree `scan-ai/main.py`:** the in-tree server is dead (`/v1/*`, `llama3.1:8b`); `scan-classifier` appears nowhere in the repo (server-side tag, `external-unverifiable`).
- Package is **`com.uow.scan`**, NOT `com.uow.guardify`. App is **S'CAN**, formerly Guardify.
- **DB is Room `ScanDatabase` v7** (not "GuardifyDatabase v4").
- **`NetworkMonitorActivity`'s own KDoc ("design-first / data source stubbed", `:62-63`) is STALE** — the code below it is fully wired live; do not repeat the KDoc as fact.
- **`ScanMonitorService`/`MonitorFragment` "Monitor tab" reference is stale:** `MonitorFragment` is written but never instantiated.
- **`IpAsnDb` KDoc `.gz` asset names are stale:** the loader opens the **raw** `ip2asn_ranges.bin`/`ip2asn_names.tsv` (no `.gz` on disk).
- **README "Beta"/"coming next" tags and the v1.4.7 badge UNDERSTATE the code:** NTM Stages 0-3 + Terminator are built and wired.
- **FGS type expectation `systemExempted` is dropped:** the manifest declares **`specialUse`**.

---

## 10. Honest limitations

- **The LLM SMS classifier is opt-in and self-hosted.** It is a prompt-engineered wrapper on qwen3.5 (not fine-tuned), served as a local/tunneled demo (shim + Ollama on Çağrı's host behind a Cloudflare Tunnel). If the host or tunnel is down, the LLM path is unavailable and the app falls back on-device. The served model identity/health/latency are `external-unverifiable` from the repo (`scan-classifier` is purely a server-side Ollama tag).
- **When the LLM path IS used, SMS content DOES leave the device** — to Çağrı's OWN self-hosted model, not a third-party cloud. **Do NOT tell the jury "SMS never leaves the device" when demoing the LLM;** the on-device default path (fallback ON) is the one where text never leaves.
- **DNS Tier B is degraded:** the on-device CLIENT is `code-verified`, but the `dnsprobe-api.scan-ai.xyz/result` SERVER origin was reported DOWN (`external-unverifiable`). AUTO mode shows "couldn't confirm"/ERROR rather than faking a pass. Restart the VPS unit or use the long-press EXPOSED/PROTECTED override.
- **DNS Protection only protects the SYSTEM resolver's DNS:** apps that hardcode their own DNS or use DoT/DoH bypass it; only one VPN can be active at a time; DNS over TCP/53 is dropped; max real score is capped at 99 (`coerceIn(35,99)`). The "22" pole grades INTERCEPTED only because 22 < 35; the live floor is 35 and a confirmed-tamper SUSPECT caps at 59 (top of EXPOSED).
- **App Terminator: auto-revoke / force-stop does NOT work.** The screen renders canned `TerminatorDemoData`; the guided-revoke notification (deep-link to Settings, Android performs the revoke) works, but `tryShizukuRevoke()` is a STUB returning false. Force-stopping other apps is impossible for a non-privileged app. Present Terminator as guided-only / Coming-soon.
- **Background monitoring produces nothing without `PACKAGE_USAGE_STATS` / Usage Access.** Sensor attribution is **camera+mic ONLY** (no public API for location/SMS/contacts); there is NO auto-revoke anywhere.
- **Wi-Fi analyzer is read-only** (no `CHANGE_WIFI_STATE`); the mock fallback was removed, so there is no guaranteed evil-twin to show on stage — it depends on real nearby networks. The active TLS/captive probes need a real network; while Shielded the DNS-FAIL dock is suppressed but TLS/captive penalties still apply.
- **NTM (v1.5) is REAL but its live ledger is in-memory only** (no persistence — forgotten on close), and Stage 4b full capture is `flag-gated-off` for the demo; the demo runs the DNS-only sinkhole engine. NTM blocking is DNS-sinkhole-based, so apps that hardcode DNS / use DoH can bypass it.
- **URL safety analyzer and the visual brand-impersonation VLM check are dead/skeleton** — not wired to any running endpoint or client; the whole in-tree `scan-ai/main.py` (`/v1/*`) is dead. Do not present them.
- **Room uses `fallbackToDestructiveMigration`:** any schema bump WIPES user data (acceptable for a sideloaded demo, a pre-Play debt). R8/ProGuard + resource shrinking are configured for release, but there is no release `signingConfig`, so the demo ships **debug** builds. The HIBP API key remains un-rotated in published APKs + git history — Çağrı chose to keep as-is; do not nag.

---

## 11. Presentation talking points (v1.5-centric, all `code-verified` unless noted)

1. **The headline demo is the Network Traffic Monitor (NTM):** on a unified on-device VPN, S'CAN sees which apps phone home, names the trackers behind each connection, and blocks them — all on the phone, with no new permissions, launched from a Home tool-card.
2. **See → name → block, end to end:** per-app attribution via `getConnectionOwnerUid`, tracker naming from 260 `trackers.json` signatures, and Pi-hole-style DNS sinkholing from a curated ~3,500-domain blocklist — blocked domains return **`0.0.0.0` / `::`** (not a loopback redirect), and first-party domains are left untouched so apps don't break.
3. **You can show the real org behind a connection, fully offline** — Stage 4a maps IPs to org/ASN/country from bundled iptoasn data (`ip2asn_ranges.bin` ~5.4 MB, `ip2asn_names.tsv` ~2.4 MB); e.g. "GOOGLE-CLOUD-PLATFORM · AS396982 · US". No cloud lookup.
4. **Privacy by design:** NTM keeps its ledger in-memory only (forgotten on close), deliberately, to avoid a destructive Room migration. The experimental full-capture path (Stage 4b) is built and works but is `flag-gated-off` (hidden long-press); the demo runs the safe DNS-only engine.
5. **A real, self-hosted AI SMS classifier:** `scan-classifier:v1` (~7B, ~Q4) running on my own infrastructure. Be precise: it's a prompt-engineered wrapper on qwen3.5, not a fine-tune; the model identity lives server-side (the Android client + cert pins are what's code-verified).
6. **Honest data-flow story:** by default SMS is screened entirely on-device (text never leaves the phone — `isSmsFallbackEnabled` defaults TRUE); the LLM is an explicit opt-in, and when used the text goes only to my own self-hosted model — never a third-party cloud.
7. **DNS leak detection + one-tap protection are real:** an on-device Tier-A posture/hijack analyzer, plus a one-tap DoH VpnService (the "encrypt" mode of the unified tunnel, **Cloudflare-only**) — code-verified to score 99/PRIVATE.
8. **Tier B proves where your DNS actually egresses** by resolving a unique token against my own authoritative server on a Hetzner VPS — and it fails honestly ("couldn't confirm") rather than faking a pass when the origin is unreachable. (The on-device client is code-verified; the server is the off-device piece to restart.)
9. **A fairer Wi-Fi safety score in v1.5:** a true 0-100 rubric plus active verification — real DNS-integrity, TLS-MITM (HTTPS HEAD to `cloudflare-dns.com` with the default trust store), and captive/injection (`generate_204`) probes; "Shield this network" arms encrypt+block+monitor in one tap.
10. **Breach + password checks done privately:** HIBP email lookup plus a k-anonymity Pwned-Passwords check where only a 5-char SHA-1 prefix ever leaves the device (no API key).
11. **Brand refresh:** a single lime vector identity, a static splash radar, and an always-on animated radar badge top-right on the dashboard — now DayNight-aware (follows the system theme).
12. **Honesty as a feature:** the app deep-links to Android's settings instead of pretending to revoke permissions; App Terminator's auto-revoke is openly Coming-soon (guided revoke works, Shizuku auto-revoke is a stub); and the public README still understates what's actually shipped — no demo-fakery.

---

## 12. Confirm-before-demo checklist (ONLY the 4 remaining external items)

Everything the v1.5 code touches is now `code-verified` at `/home/cagri/s-can/source` — the long list of code-internal conflicts is resolved. Only these four off-repo dependencies remain to confirm:

1. **Hetzner Tier-B `/result` SERVER liveness** (`external-unverifiable`) — the on-device CLIENT (`DnsLeakProbe`, `DnsLeakProbeClient`, `DnsLeakProbeService`) is code-verified; the SERVER (responder/api on the VPS, no `dnsprobe/` in this repo) was reported DOWN. Restart `dnsprobe-api.service` on the VPS, or plan to use the long-press EXPOSED/PROTECTED override.
2. **Tier-B SERVER-SIDE geo dataset** (`external-unverifiable`) — the on-device dataset is iptoasn (verified). Whether the Tier-B server enriches with MaxMind GeoLite2 vs iptoasn lives on the VPS, not this repo. Keep the "no-MaxMind" claim scoped to the **on-device** path only.
3. **Live shim + Ollama `scan-classifier:v1` model identity/health/latency** (`external-unverifiable`) — the production path (`scan-api.scan-ai.xyz` → CF Tunnel → external `scan-ai-shim/main.py` → Ollama `scan-classifier:v1`) runs on the author's host; only the Android client + cert pins + the dead in-tree `/v1` server are code-verifiable. Have the shim/Ollama up if the LLM path will be shown, and phrase "to my own self-hosted model" honestly.
4. **Product marketing domain** (`external-unverifiable`) — source docs resolve toward **`scan.app`** (`source/ARCHITECTURE.md` dated note "There is no scan.xyz"); `source/RELEASE_NOTES.md:33` has a single stale `scan.xyz` typo. Backend `scan-ai.xyz` is consistent. Whether `scan.app` is actually registered/owned/live is not determinable from the repo — confirm before putting it on a slide.
