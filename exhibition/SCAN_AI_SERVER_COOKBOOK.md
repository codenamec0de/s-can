# S'CAN AI Server — Cookbook

**Last updated:** 2026-05-07 (post-app-traffic landing)
**Owner:** Çağrı Alaf (single-author, UOW final-year)
**Purpose:** Boot the public AI sidecar for S'CAN's SMS scam classifier — graduation fair demo (V1.4).

> **Future-Claude / future-Çağrı reading this:** server side is provisioned, persistent (systemd), and the Android app talks to it.
> - **For demo day ops:** run § **Demo-day quickstart** below — one paste, four lines of expected output. If any line is red, the same section's recovery table tells you exactly what to do.
> - **For Claude on demo day:** § **Notes for future-Claude** is written for you specifically — read it before touching anything.
> - **For background/architecture:** § **Architecture** + § **App-side architecture (post-public-traffic)**.

## Recent change history

- **2026-05-07** — Debug-build-only `SmsTestBroadcastReceiver` added (`app/src/debug/...`) — adb-triggerable hook that enqueues `SmsForwardWorker` directly, bypassing the system-protected `SMS_RECEIVED` broadcast. Used to verify end-to-end pipeline on demo phone without a real cellular SMS; release APKs do not include it. Round-trip verified ~1 s on Samsung A17 — Linkt-typosquat injection produced SCAM verdict with matching reasoning, notification on `scan_sms_scam_channel`, verdict card visible in SMS Verdicts list with green status pill.
- **2026-05-07** — `cloudflared.service` system unit installed (analogue to `scan-ai-shim.service`). Both runtime pieces now boot-survive. Linkt + AusPost brand-typosquat few-shots added to `SYSTEM_PROMPT` (main.py + run_eval.py); AU eval now **29/29 = 100%** (was 28/29 with Linkt miss). Connection-status pill added to the SMS Verdicts screen (`SmsScamActivity` — green/yellow/red on resume + tap-to-recheck). Dead `urlSignals` plumbing removed from `SmsVerdict` model + activity (column kept in entity for back-compat with existing rows). `app-debug.apk` rebuilt clean (16M).
- **2026-05-07** — Working tree migrated from `~/Desktop/Scan1.4.2-R8/source/` to `~/Scan1.4.2/Scan1.4.2 (Copy)/source/`. The R8 copy is frozen (release-build artefacts, do not edit); all forward work happens in the Home tree. Public-traffic implementation reconstructed from cookbook spec into the new tree, `compileDebugKotlin` clean. PrivacyPolicyActivity section 3 rewrite (Remaining-work item 1) landed in the same pass. Stale "self-hosted" copy on home / SMS / onboarding / signup / privacy screens reworded to match public-by-default reality.
- **2026-05-07** — FastAPI shim made persistent as `scan-ai-shim.service` (systemd, `Restart=on-failure`, `WantedBy=multi-user.target`, MainPID stable). Survives reboot. Replaces the foreground `nohup uvicorn` setup. Remaining-work item 3 partially closed — only cloudflared is still foreground.
- **2026-05-07** — Android-side public-traffic implementation landed. App now defaults to `scan-api.scan-ai.xyz`, bearer token removed entirely, OkHttp pinned to Cloudflare LE E7 + ISRG X1, `/classify` request/response shapes aligned with the public shim, cached-fallback emergency toggle added to AI Server screen. See **App-side architecture (post-public-traffic)** and the rewritten **Remaining work** section.
- **2026-05-07** — Few-shot prompt v1 (Hi Mum + phone-back fraud) shipped to `scan-classifier:v1`. AU acc 86.2% → 96.6%, AU scam recall 76.5% → 94.1%, TR/EN acc 92.6% → 100%.
- **2026-05-06** — Public Cloudflare Tunnel (`scan-api`) + FastAPI shim + qwen3.5 9.7B as `scan-classifier:v1` brought up. Domain `scan-ai.xyz` registered, edge cert provisioned.

---

## Demo-day quickstart

**Both services auto-start on boot via systemd** (`scan-ai-shim.service`, `cloudflared.service`). You should not need to run anything manually. Power on the laptop, wait ~60 s for things to settle, then paste the single block below to verify + pre-warm the model:

```bash
systemctl is-active scan-ai-shim cloudflared && \
  curl -sS https://scan-api.scan-ai.xyz/health && echo && \
  curl -sS https://scan-api.scan-ai.xyz/classify -X POST \
    -H 'content-type: application/json' \
    --data-raw '{"sms":"You won 500 USD click http://shady.link"}' \
  | python3 -m json.tool
```

**Expected output (all four lines must appear):**

```
active
active
{"status":"ok","model":"scan-classifier:v1"}
{
    "verdict": "scam",
    "reasoning": "...",
    "confidence": 0.95-0.99,
    "model": "scan-classifier:v1",
    "latency_ms": ~700  ← (~2500 on the very first call, that's Ollama loading the model into VRAM)
}
```

The first /classify after a cold boot is ~2.5 s (model loading); after that, p50 ≈ 700 ms. The block above doubles as a warmup, so you're warm immediately. If the demo will pause >10 min, repeat the second curl shortly before resuming.

### Recovery — if anything is red

| Symptom | Recovery |
|---|---|
| `scan-ai-shim` inactive | `sudo systemctl restart scan-ai-shim` ; `journalctl -u scan-ai-shim -n 30` to diagnose if it won't start |
| `cloudflared` inactive | `sudo systemctl restart cloudflared` ; `journalctl -u cloudflared -n 30` (look for 4 `Registered tunnel connection` lines) |
| `/health` returns 502 | Edge is fine, origin (shim) is dead. Restart shim. |
| `/health` returns 530 / 1033 | Tunnel has no live connectors. Restart cloudflared. |
| `/classify` returns 422 with "JSON decode error" | Your terminal mangled the JSON in the curl. Quote the body more carefully. |
| `/classify` is consistently >3 s | Ollama evicted the model from VRAM (rare). Re-warm with another `/classify` call. |
| DNS fails (`Could not resolve host`) | See § Troubleshooting → "DNS" entries. |
| App pill is red but laptop checks pass | Phone's network can't reach Cloudflare edge. Switch the phone to 4G/another wifi. |

### What "everything OK" looks like on the phone

Open S'CAN → **SMS Scam Detection** (home tile) → **Verdicts** screen. You should see:

- Subtitle: *SMS analyzed by S'CAN's AI sidecar.*
- A pill below the subtitle: **green dot + "AI server connected"** within ~1 s of opening the screen.
- Tap the pill anytime to re-check.

States: green = OK · yellow = WARN or "Cached on-device mode" (toggle on AI Server screen is enabled) · red = unreachable · gray = checking. Verified working on Samsung A17 (`R5GL139AA5L`) on 2026-05-07.

---

## DNS Tier-B (DNS-leak "deep test") — SEPARATE Hetzner service

> **Different box from the AI shim above.** This powers the DNS-leak **deep test** (the app resolves
> `<token>.dnsprobe.scan-ai.xyz`; the server reports which resolver the DNS egressed through). If it's
> down, the app's DNS deep test shows **"couldn't confirm"** — the SMS/AI path is unaffected. Full
> runbook: `~/Scan1.4.2/Scan1.4.2 (Copy)/dnsprobe/README.md` § 7.
>
> **Host:** `ssh root@178.105.255.209` (Hetzner Falkenstein, key `~/.ssh/id_ed25519`). Two systemd units,
> no Docker: `dnsprobe-responder` (:53 DNS) + `dnsprobe-api` (:443 `/result`, behind Cloudflare "Full").

**Health check (from the laptop):**
```bash
curl -sS -m10 https://dnsprobe-api.scan-ai.xyz/health     # -> {"status":"ok","geo_city":true,"geo_asn":true}
T="scanchk$(head -c8 /dev/urandom|od -An -tx1|tr -d ' \n')"; getent hosts "$T.dnsprobe.scan-ai.xyz" >/dev/null
curl -sS -m10 "https://dnsprobe-api.scan-ai.xyz/result/$T"  # -> {"status":"ok","resolver_ip":...,"org":...,"kind":"isp"}
```

**Recovery (if `/health` times out / app says "couldn't confirm"):**
```bash
ssh root@178.105.255.209 'systemctl restart dnsprobe-api && sleep 2 && systemctl is-active dnsprobe-api && curl -skm6 https://127.0.0.1/health'
```

⚠ **Gotcha:** `systemctl is-active` can say **active while the API is wedged and serving nothing.** Confirm
it's *really* serving: on the VPS `ss -tlnp | grep ':443'` with **Recv-Q > Send-Q** = wedged accept loop,
and a **local** `curl https://127.0.0.1/health` that times out. Restart clears it.

**Root cause (fixed 2026-06-04):** `api.py` wrapped the *listening* socket in TLS, so the TLS handshake ran
inside `accept()` on the single accept thread — one stalled Cloudflare keep-alive / port-scan connection
wedged the whole server (it ran ~1 day then hung silently). Fixed to accept plain TCP and wrap each
connection per-thread with a 30 s timeout (`class TLSServer` in `dnsprobe/api.py`). The fix is in the source
tree; redeploy steps are in `dnsprobe/README.md` § 7. A plain restart unwedges it; the code fix stops it recurring.

---

## Notes for future-Claude reading this on demo day

If Çağrı opens this cookbook during a session and asks you to "make sure everything's working" or "boot the demo":

1. **First action — run § Demo-day quickstart.** Don't ask questions; verify state first. The block is a single paste; the expected output tells you what's wrong.
2. **Both services are systemd-managed** — never start them with `nohup` or foreground unless the unit itself is broken. If you do start a foreground process to recover, kill it cleanly before re-enabling the unit (port :8000 conflict for shim; redundant connectors for cloudflared).
3. **`pkill -f cloudflared` (or any `pgrep -f` against a string in your own bash command line) will self-kill your shell** — exit code 144. Use `systemctl show -p MainPID --value <unit>` to get the systemd PID, or `ss -tlnp | grep :8000` for the shim.
4. **`sudo cloudflared service install` is opaque/hung in 2026.3.0** — it printed nothing and never created a unit during the 2026-05-07 session. The current `cloudflared.service` is hand-written (see § Persistent services). If it's gone, recreate from the unit body documented there.
5. **The shim caches `SYSTEM_PROMPT` in memory at startup.** If you edit `~/scan-ai-shim/main.py` (e.g., add a few-shot example), you MUST `sudo systemctl restart scan-ai-shim` for it to take effect. Update `~/scan-ai-shim/run_eval.py` separately too — evals call Ollama directly, not through the shim.
6. **Sudo from this exec context needs a TTY** — when you need a sudo command and have no terminal, ask Çağrı to paste it via `! sudo ...` in the prompt (sudo's password cache covers a few minutes once authenticated). Keep each pasted line short (~70 chars) so terminal wrap doesn't split it.
7. **APK location:** `~/Scan1.4.2/Scan1.4.2 (Copy)/source/app/build/outputs/apk/debug/app-debug.apk`. Phone serial: `R5GL139AA5L` (Samsung A17). To push code: `adb install -r <apk>`.
8. **Working tree is `~/Scan1.4.2/Scan1.4.2 (Copy)/source/`** — NOT `~/Desktop/Scan1.4.2-R8/source/` (that's the frozen release-build copy, do not edit).
9. **Don't `cloudflared tunnel info` without sudo** — needs `~/.cloudflared/cert.pem` privileges. Just check `journalctl -u cloudflared` instead.
10. **The Anthropic prompt cache is 5 minutes** — if you're going to wait for a build/eval, sleep ≤270 s or commit to ≥1200 s. Don't pick 5 minutes.

---

## Scope

Public AI server serving SMS scam classification to S'CAN/Guardify Android clients. Phone POSTs an SMS body, server returns structured JSON (`scam` / `not_scam` + reasoning). Reachable from any network (fair wifi, 4G) via Cloudflare Tunnel — no port forwarding, works even while host is on Mullvad VPN.

**In V1.4 scope:** item 2.2-v2 (TLS+pin, public), item 2.1 (onboarding disclosure refers to this server).
**Out of scope:** Smart Risk Scoring V2, Phase 3+ work, A3 crawler, anything previously deferred to V1.5/V1.6 (all dead).

---

## Architecture

```
Phone (any network: fair wifi, 4G/5G, hotspot)
  └─ HTTPS → scan-api.scan-ai.xyz       (Cloudflare edge, Let's Encrypt wildcard)
        └─ cloudflared tunnel "scan-api" (outbound from host, no inbound port — works behind Mullvad)
              └─ FastAPI shim @ localhost:8000  (~/scan-ai-shim/main.py, ~80 LoC)
                    └─ POST /classify  → Ollama @ localhost:11434
                          └─ Model: scan-classifier:v1 (cloned from qwen3.5 9.7B Q4)
                                System prompt: 7 few-shot examples (TR/EN + Hi Mum + phone-back)
                                think=false, format=json, temperature 0.1
```

**Public endpoint:** `https://scan-api.scan-ai.xyz/classify` returns
`{verdict, reasoning, confidence, model, latency_ms}`.
**Warm latency:** p50 ≈ 700 ms (Sydney edge), p95 ≈ 850 ms.

**Why this shape:**
- cloudflared is outbound-only → works behind Mullvad WG without touching VPN config.
- No Docker anywhere → avoids the Mullvad/Docker iptables trap that bricked Ubuntu on 2026-04-29.
- Cloudflare edge gives free real TLS, no Let's Encrypt cron.
- Stable model tag `scan-classifier:v1` is local-only → upstream `qwen3.5:latest` rotation cannot silently break the demo.

---

## Project tree — `~/scan-ai-shim/`

```
~/scan-ai-shim/
├── main.py                       FastAPI shim (auth, /health, /classify)
├── run_eval.py                   Eval harness — accepts eval-set path arg, EVAL_MODELS env
├── generate_report.py            Builds PDF report on Desktop (uses reportlab)
├── eval_set.json                 27 cases, TR/EN baseline (13 scam, 13 safe, 1 edge)
├── au_eval_set.json              29 cases, AU regional (16 scam, 11 safe, 2 edge)
├── eval_set_results.json         Per-case verdicts + latency from last TR/EN run
├── au_eval_set_results.json      Per-case verdicts + latency from last AU run
└── .venv/                        Python venv (fastapi, uvicorn, httpx, pydantic-settings, reportlab)
```

PDF report (regenerated by `generate_report.py`): `~/Desktop/SCAN_AI_Model_Performance_Report.pdf`

---

## Current state (as of 2026-05-07)

| Component | Status | Notes |
|-----------|--------|-------|
| Cloudflare account | done | dash.cloudflare.com, registered with Çağrı's email |
| Domain `scan-ai.xyz` | done | Cloudflare Registrar, registered 2026-05-06 |
| `cloudflared` installed | done | v2026.3.0, via .deb from GitHub releases |
| Cloudflare auth | done | `~/.cloudflared/cert.pem` |
| Tunnel `scan-api` | done | UUID `868f0d63-5ef2-41a5-8443-9ac60dea386e` |
| Tunnel credentials | done | `~/.cloudflared/868f0d63-5ef2-41a5-8443-9ac60dea386e.json` (SECRET) |
| DNS route `scan-api.scan-ai.xyz` | done | CNAME → tunnel, propagated, resolves to Cloudflare anycast (`104.21.34.74` / `172.67.156.27`) |
| Universal SSL (edge cert) | done | Let's Encrypt E7, SAN `*.scan-ai.xyz, scan-ai.xyz`, expires 2026-08-04 (auto-rotates) |
| `~/.cloudflared/config.yml` | done | points to `http://localhost:8000` |
| Ollama on host | done | from earlier AI sidecar work; qwen3.5:latest |
| End-to-end smoke test (`Tunnel works ✅`) | done | 2026-05-06, `HTTP/2 200` from Sydney edge |
| FastAPI shim `/classify` | done | `~/scan-ai-shim/main.py`, venv `~/scan-ai-shim/.venv/`. Endpoints: `/health`, `/classify`. Bearer auth via `SCAN_API_TOKEN` env (optional, off by default — set before fair). |
| Model selection eval | done | qwen3.5:9.7B picked (92.6% acc, 100% safe-recall, p50 597ms local / 700ms public). Beat qwen3:4b (too many false positives) and qwen2.5:14b (slower, no accuracy gain). Eval set + harness in `~/scan-ai-shim/eval_set.json` + `run_eval.py`. |
| Stable model tag `scan-classifier:v1` | done | `ollama cp qwen3.5:latest scan-classifier:v1`. Local-only tag, immune to remote `:latest` updates. |
| Public `/classify` smoke test | done | Turkish + English scams + safe OTP all classified correctly via `https://scan-api.scan-ai.xyz/classify`. |
| App-side cert pin (Cloudflare intermediate) | done | 2026-05-07. LE E7 intermediate + ISRG X1 backup hardcoded in `ScanAiClient.kt`; pinned host `scan-api.scan-ai.xyz`. LAN pins removed. |
| App build flavor split (dev=LAN, demo=public) | **not done (decided against)** | The runtime URL field in `AiServerActivity` already covers dev override — flavors would only have given different pins. Single APK ships with public defaults. |
| App "demo mode" cached fallback | done | 2026-05-07. Toggle in AiServerActivity topbar (top-right `SwitchCompat`). Bypasses server, uses on-device keyword + example match against `assets/scam_fallback.json`. |
| App API contract aligned with public shim | done | 2026-05-07. `v1/`-prefix dropped, `{sms}`-in / `{verdict, reasoning, confidence, model, latency_ms}`-out, `url-check` + `feedback` endpoints removed. Verdict cased uppercase at worker boundary. |
| App bearer-token policy | done — removed | 2026-05-07. Token field, prefs key, and Authorization interceptor all stripped. Server stays open by default. |
| Network Security Config tightened | done | 2026-05-07. Strict `cleartextTrafficPermitted="false"` + system anchors only in main; debug NSC retained for LAN dev testing. `res/raw/scan_ai_cert.crt` deleted. |
| Few-shot prompt v1 (Hi Mum + phone-back fraud) | done | 2026-05-07. AU acc 86.2%→**96.6%**, AU scam recall 76.5%→**94.1%**, TR/EN acc 92.6%→**100%**, safe recall held at 100% on both sets. See `~/Desktop/SCAN_AI_Model_Performance_Report.pdf`. |
| AU regional eval (n=29) | done | Patterns from Scamwatch/ACMA (Linkt, AusPost, ATO, myGov, Big 4 banks, Hi Mum). |
| AU regional eval — Linkt + AusPost typosquat closed | done | 2026-05-07. Two new few-shots in `SYSTEM_PROMPT` (main.py + run_eval.py). AU acc 96.6% → **100%** (29/29). |
| `scan-ai-shim.service` (systemd) | done | 2026-05-07. `User=cagri`, `Restart=on-failure`, `WantedBy=multi-user.target`. Boot-survives. |
| `cloudflared.service` (systemd) | done | 2026-05-07. Hand-written unit (the `service install` command was hung in 2026.3.0). Boot-survives, 4 edge connectors register on start. |
| App connection-status pill on SMS Verdicts screen | done | 2026-05-07. Tappable pill in `SmsScamActivity` polling `/health` on resume. States: green (ok) / yellow (warn or cached mode) / red (unreachable) / gray (checking). Verified green on Samsung A17 (`R5GL139AA5L`). |
| Working tree on Home (canonical) | done | 2026-05-07. `~/Scan1.4.2/Scan1.4.2 (Copy)/source/` is the live tree. R8 copy is frozen. |
| Debug APK installed on demo phone | done | 2026-05-07. `app-debug.apk` (16M) at `app/build/outputs/apk/debug/`. Installed on `R5GL139AA5L`, end-to-end pill green via Android OkHttp + cert pinning. |

---

## Boot sequence (manual recovery / reference only)

> **Both services auto-start on boot.** For day-of-demo verification, use § **Demo-day quickstart** — not this section. The commands below describe what each piece is and how to start it by hand if the systemd unit is broken or you want to debug in the foreground.

### 0. Sanity checks

```bash
mullvad status 2>/dev/null || echo "mullvad not active"
cloudflared --version
ollama list | head
ss -tlnp | grep -E ':(8000|11434)'
```

> **Hard rule:** Do not start a Docker daemon while Mullvad is connected. The iptables conflict bricked Ubuntu once already (2026-04-29). cloudflared is fine — it's just outbound HTTPS.

### 1. Start Ollama

```bash
# If not already running as a user service:
ollama serve &
# Verify:
curl -s http://localhost:11434/api/tags | head
```

### 2. Start FastAPI shim

> **Normal path:** the `scan-ai-shim.service` systemd unit handles this automatically on boot. The command below is for manual foreground debugging only — make sure the unit is stopped first (`sudo systemctl stop scan-ai-shim`) so :8000 isn't already taken.

```bash
cd ~/scan-ai-shim
SCAN_MODEL=scan-classifier:v1 .venv/bin/uvicorn main:app --host 127.0.0.1 --port 8000
```

Optional env vars:
- `SCAN_MODEL` (default `qwen3.5:latest`) — Ollama model tag. Use the stable `scan-classifier:v1` for demo.
- `SCAN_API_TOKEN` — if set, `/classify` requires `Authorization: Bearer <token>`. The Android app no longer sends a token (removed 2026-05-07), so leave this unset on demo day.
- `OLLAMA_URL` (default `http://127.0.0.1:11434`).

Health check:
```bash
curl -s http://127.0.0.1:8000/health
# {"status":"ok","model":"scan-classifier:v1"}
```

### 3. Start cloudflared tunnel

> **Normal path:** the `cloudflared.service` systemd unit handles this on boot. Manual launch is only for debugging.

```bash
cloudflared --config ~/.cloudflared/config.yml tunnel run scan-api
```

Expect 4 `Registered tunnel connection` lines (Sydney/Melbourne edge). Leave running.

**Lessons from 2026-05-07:** `sudo cloudflared service install` was opaque/hung in version 2026.3.0 — printed nothing and never created a unit. The current `cloudflared.service` was hand-written instead (see § Persistent services in Remaining work). If you ever need to recreate it, copy the unit body from there rather than retrying `service install`.

### 4. Verify end-to-end

```bash
# DNS — DoH bypasses local resolver / VPN weirdness:
curl -s -H 'accept: application/dns-json' \
  'https://cloudflare-dns.com/dns-query?name=scan-api.scan-ai.xyz&type=A'

# Health (public):
curl -s https://scan-api.scan-ai.xyz/health
# Expect: {"status":"ok","model":"scan-classifier:v1"}

# Real classification (public):
curl -s https://scan-api.scan-ai.xyz/classify \
  -X POST -H 'content-type: application/json' \
  -d '{"sms":"You won $500 Click http://shady.link"}' | python3 -m json.tool
# Expect: {"verdict":"scam","reasoning":"...","confidence":~0.95,"model":"scan-classifier:v1","latency_ms":~700}
```

### 5. (Demo day only) Pre-warm the model

The first request after Ollama loads the model into VRAM is ~2.5 s. Pre-warm before showing the demo:
```bash
curl -s http://127.0.0.1:11434/api/generate \
  -d '{"model":"scan-classifier:v1","prompt":"warmup","stream":false,"think":false}' >/dev/null
```
Subsequent requests stabilise around 700 ms.

---

## Pre-demo checklist (run morning of fair)

Tick everything before walking in:

```bash
# 1. Network: home internet up, Mullvad in a known state (off is simplest)
ping -c 1 1.1.1.1

# 2. Cloudflare edge cert still valid (LE rotates every 90d, currently expires 2026-08-04):
openssl s_client -connect scan-api.scan-ai.xyz:443 -servername scan-api.scan-ai.xyz </dev/null 2>/dev/null \
  | openssl x509 -noout -dates

# 3. SPKI pin for Cloudflare intermediate hasn't changed (compare to APK pin):
openssl s_client -connect scan-api.scan-ai.xyz:443 -servername scan-api.scan-ai.xyz -showcerts </dev/null 2>/dev/null \
  | awk '/BEGIN CERT/{i++} i==2' \
  | openssl x509 -pubkey -noout | openssl pkey -pubin -outform DER \
  | openssl dgst -sha256 -binary | base64
# Expect: y7xVm0TVJNahMr2sZydE2jQH8SquXV9yLF9seROHHHU=

# 4. Stack alive end-to-end:
curl -s https://scan-api.scan-ai.xyz/health
curl -s https://scan-api.scan-ai.xyz/classify -X POST -H 'content-type: application/json' \
  -d '{"sms":"Tebrikler iPhone kazandiniz http://bit.ly/odul"}'

# 5. Pre-warm model (see step 5 of Boot sequence)

# 6. Confirm router/home internet has at least 4G failover or backup hotspot
```

If pin (step 3) has changed: LE rotated to E8 (or new) intermediate. Two options before fair:
- **A**: Re-extract pin, rebuild APK with both old + new pin (backup-pin pattern).
- **B**: Use the demo-mode cached fallback for the fair, fix pins post-graduation.

---

## Re-running the evals

When tweaking the prompt or trying a new model, re-run the eval sets to see impact. Both sets are independent.

```bash
cd ~/scan-ai-shim

# TR/EN baseline (n=27, ~30s on qwen3.5):
EVAL_MODELS=scan-classifier:v1 .venv/bin/python run_eval.py eval_set.json

# AU regional (n=29, ~30s on qwen3.5):
EVAL_MODELS=scan-classifier:v1 .venv/bin/python run_eval.py au_eval_set.json

# Compare 3 models head-to-head (~3 min total — qwen2.5:14b is slow):
EVAL_MODELS=qwen3:4b,scan-classifier:v1,qwen2.5:14b-instruct-q6_K \
  .venv/bin/python run_eval.py eval_set.json

# Regenerate the PDF on Desktop after running new evals:
.venv/bin/python generate_report.py
```

Per-case results land in `<set>_results.json`. The summary table is printed to stdout.

**If you change the prompt:** edit `SYSTEM_PROMPT` in BOTH `main.py` AND `run_eval.py` (kept identical so eval predicts what production will do). Restart the shim after editing main.py.

---

## Eval results — record (2026-05-07, prompt v1)

| Set | Cases | Acc | Scam recall | Safe recall | p50 ms |
|---|---|---|---|---|---|
| TR/EN baseline | 27 | **100.0%** | 100% | 100% | 688 |
| AU regional | 29 | **96.6%** | 94.1% | 100% | 729 |

Three-model selection (TR/EN baseline, prompt v0):

| Model | Acc | Scam recall | Safe recall | p50 ms | VRAM |
|---|---|---|---|---|---|
| qwen3:4b | 77.8% | 100% | 57% (unusable) | 373 | ~3 GB |
| **scan-classifier:v1 (qwen3.5 9.7B Q4)** | 92.6% → **100%** | 84.6% → **100%** | 100% | 597 → 688 | ~7 GB |
| qwen2.5:14b Q6 | 88.9% | 100% | 79% | 848 | ~12 GB |

Full per-case breakdown + commentary: `~/Desktop/SCAN_AI_Model_Performance_Report.pdf`.

**Single open failure:** `au-scam-linkt-01` — Linkt brand-name typosquat (`linkt-au.com`). Fix options:
- Add a typosquat few-shot example to `SYSTEM_PROMPT`, or
- Maintain a small brand allow-list and post-process (force-flag if SMS contains a brand name AND a URL whose host is not allow-listed).

---

## Shutdown

```bash
# Tunnel — Ctrl+C in its terminal, or:
pkill cloudflared

# FastAPI / placeholder server:
pkill -f "uvicorn main:app"
pkill -f "python3 -m http.server"

# Ollama: leave running, idle GPU draw is minimal.
```

---

## Troubleshooting

### Symptom: `Could not resolve host: scan-api.scan-ai.xyz`

**First, distinguish registry vs local DNS:**
```bash
curl -s -H 'accept: application/dns-json' \
  'https://cloudflare-dns.com/dns-query?name=scan-ai.xyz&type=NS'
```
- `"Status":3` (NXDOMAIN) with Authority `xyz / ns0.centralnic.net` → `.xyz` registry hasn't committed the new domain yet. Typical 15 min – few hours for fresh registrations. **Wait.**
- `"Status":0` with `*.ns.cloudflare.com` → registry done, problem is local. Check `resolvectl status`, try `dig @1.1.1.1`.

### Symptom: DoH/`dig @1.1.1.1` resolve, but `curl` says "Could not resolve host"

The host is using its router (`192.168.0.1`) as DNS, and the router cached the NXDOMAIN before propagation. systemd-resolved respects per-interface DNS even after `flush-caches`. Permanent fix:
```bash
sudo sed -i 's/^#\?DNS=.*/DNS=1.1.1.1 1.0.0.1/' /etc/systemd/resolved.conf
sudo sed -i 's/^#\?FallbackDNS=.*/FallbackDNS=8.8.8.8/' /etc/systemd/resolved.conf
sudo systemctl restart systemd-resolved
resolvectl status   # confirm "DNS Servers: 1.1.1.1 1.0.0.1"
```
Note: `resolvectl status` may still show the router for a specific interface — global config takes precedence for new lookups, so this is fine.

### Symptom: TLS handshake_failure right after domain registration

Cloudflare Universal SSL takes 5-15 min (sometimes up to ~1h) to provision after domain registration, separate from DNS propagation. Check dashboard → SSL/TLS → Edge Certificates → "Universal SSL" status should be **Active**. Verify the cert:
```bash
openssl s_client -connect 172.67.156.27:443 -servername scan-api.scan-ai.xyz </dev/null 2>/dev/null \
  | openssl x509 -noout -text | grep -A2 "Subject Alternative"
```
Should include `DNS:*.scan-ai.xyz`. Wait if still pending — no action needed beyond patience.

### Symptom: DoH says record exists, but `dig @1.1.1.1` empty

Mullvad with DNS content filters can intercept queries even when you specify `@1.1.1.1`. Either:
- Mullvad app → Settings → DNS → disable content blockers, or
- Set Mullvad custom DNS = `1.1.1.1`, or
- Disconnect Mullvad for the demo.

### Symptom: `Address already in use` on port 8000

```bash
ss -tlnp | grep :8000
# Identify the PID, then:
pkill -f "python3 -m http.server"
# or kill <pid>
```

### Symptom: curl with `--resolve hostname:443:1.1.1.1` fails with cert mismatch

`1.1.1.1` is Cloudflare's **DNS resolver**, not your tunnel's edge IP. `--resolve` forces curl to connect there, so it sees the `cloudflare-dns.com` cert. Don't pin curl to 1.1.1.1 for HTTPS — let it use system DNS, or `--resolve` to a real Cloudflare edge IP from `dig`.

### Symptom: Tunnel connects, but request returns 502

Origin is missing or wrong. Check:
```bash
curl -v http://localhost:8000   # is anything listening?
cat ~/.cloudflared/config.yml   # does ingress.service match?
```

### Symptom: Shim returns `Bad model output: Expecting value: line 1 column 1`

Qwen3-family models default to **thinking mode**: their JSON answer ends up in the response's `thinking` field, leaving `response` empty. Shim already passes `"think": false` to Ollama — if you ever bypass the shim and call Ollama directly, do the same. **Do not rely on `/no_think` as a prompt prefix** — Ollama's API layer doesn't act on it for `format: "json"` calls.

### Symptom: Shim's first request after model switch is very slow (~2-3s)

Cold start: Ollama loads the model into VRAM. Subsequent requests are ~600-700ms. To pre-warm before a demo:
```bash
curl -s http://127.0.0.1:11434/api/generate \
  -d '{"model":"scan-classifier:v1","prompt":"warmup","stream":false,"think":false}' >/dev/null
```

### Symptom: Tunnel won't start, "tunnel credentials file not found"

The credentials JSON path in `config.yml` must match an existing file under `~/.cloudflared/`. Recreate if needed:
```bash
cloudflared tunnel list
cloudflared tunnel token <name>   # for connector token mode
```

---

## App-side architecture (post-public-traffic)

**Working copy:** `~/Scan1.4.2/Scan1.4.2 (Copy)/source/` — canonical as of 2026-05-07. The earlier `~/Desktop/Scan1.4.2-R8/source/` is frozen (release-build artefacts, do not edit).

### Files touched on 2026-05-07

| Action | Path | Notes |
|---|---|---|
| modified | `app/src/main/java/com/uow/scan/api/ScanAiApiService.kt` | Collapsed to `POST classify` + `GET health`. Drops `v1/url-check`, `v1/feedback`. |
| modified | `app/src/main/java/com/uow/scan/api/ScanAiClient.kt` | OkHttp pinner hardcoded for `scan-api.scan-ai.xyz`. Authorization interceptor removed. |
| **new** | `app/src/main/java/com/uow/scan/api/ScanAiFallback.kt` | Loads `assets/scam_fallback.json`, returns `ClassifyResponse`-shaped objects. |
| modified | `app/src/main/java/com/uow/scan/worker/SmsForwardWorker.kt` | Routes through `ScanAiFallback` when toggle is on. Uppercases verdict at boundary. Drops `checkUrls()`. |
| modified | `app/src/main/java/com/uow/scan/util/PreferencesManager.kt` | Added `isSmsFallbackEnabled` / `setSmsFallbackEnabled` + `DEFAULT_SMS_SERVER_URL` const. Token getter/setter removed. |
| modified | `app/src/main/java/com/uow/scan/AiServerActivity.kt` | Added cached-fallback `SwitchCompat`. Token field bindings removed. Default URL pre-fill. New health OK condition. |
| modified | `app/src/main/res/layout/activity_ai_server.xml` | Cached toggle in topbar top-right. Token field block deleted. |
| modified | `app/src/main/res/xml/network_security_config.xml` | Strict policy only — dev `<domain-config>` block removed. |
| modified | `app/build.gradle.kts` | `SCAN_AI_CERT_PIN` `buildConfigField` removed. |
| modified | `app/src/main/res/values/strings.xml` | Token strings deleted, cached-toggle strings added, status meta reworded. Plus 7 new `sms_v4_conn_*` strings for the verdicts-screen pill (added 2026-05-07). |
| **new** | `app/src/main/assets/scam_fallback.json` | 10 hand-picked exact-substring entries + scam/safe keyword sweeps + unverifiable default. |
| modified | `app/src/main/java/com/uow/scan/SmsScamActivity.kt` | 2026-05-07: connection-status pill added (poll `/health` on resume + tap-to-recheck), dead `urlSignals`/`formatUrlSignals` plumbing removed. |
| modified | `app/src/main/res/layout/activity_sms_scam.xml` | 2026-05-07: tappable status pill row inserted under the subtitle (`@+id/statusPill`, `@+id/statusPillDot`, `@+id/statusPillLabel`). |
| modified | `app/src/main/java/com/uow/scan/model/SmsVerdict.kt` | 2026-05-07: `urlSignals` field removed (entity column kept for back-compat with existing rows). |
| modified | `app/src/main/java/com/uow/scan/PrivacyPolicyActivity.kt` | 2026-05-07: section 3 body rewritten — bearer-token / `SCAN_AI_CERT_PIN` references removed. |
| deleted | `app/src/main/res/raw/scan_ai_cert.crt` | LAN sidecar self-signed cert no longer referenced. |
| deleted | `app/src/main/res/layout/dialog_sms_settings.xml` | Dead resource — referenced removed token field. |

### Contract (matches `~/scan-ai-shim/main.py`)

```kotlin
// Request
ScanAiApiService.ClassifyRequest(sms = body)

// Response
data class ClassifyResponse(
    val verdict: String,        // "scam" | "safe" — lowercase from server!
    val reasoning: String,
    val confidence: Double,
    val model: String? = null,
    val latency_ms: Int? = null
)
```

### Verdict casing rule

Server returns `"scam"` / `"safe"` lowercase. Rest of the app's verdict contract is uppercase (`"SCAM"`, `"SAFE"`, also `"SUSPICIOUS"` for the cached-fallback unverifiable branch). **`SmsForwardWorker` uppercases at the boundary** before persisting. Don't reintroduce raw-server-verdict comparisons.

If you add new code that touches `SmsVerdictEntity.verdict` or `SmsVerdict.verdict`, treat it as uppercase. Defensive `.uppercase()` calls already exist in `SmsVerdictAdapter` and `PdfReportGenerator`; keep them as belt-and-suspenders.

### Cached-fallback toggle

UI: top-right of `AiServerActivity` topbar (`R.id.swCachedFallback`).
Pref key: `sms_fallback_enabled` (boolean, default false).
Wire: `SmsForwardWorker.doWork()` — early branch in classify dispatch.

When enabled, `ScanAiFallback.classify()`:
1. Lowercases the SMS body.
2. Substring-matches against the `examples` array first (hand-picked demo messages).
3. Falls through to `scam_keywords` sweep, then `safe_keywords` sweep.
4. Falls back to `default_unverifiable` (`SUSPICIOUS`, confidence 0.5).

`model` field on the cached response is set to `"cached-fallback"` so you can distinguish in the verdict history.

### Default URL behaviour

`PreferencesManager.getSmsServerUrl()` returns `DEFAULT_SMS_SERVER_URL` (= `https://scan-api.scan-ai.xyz/`) when the saved value is blank. Means the worker succeeds OOTB without anyone touching the AI Server screen — the user only opens that screen if they need to override or use cached mode.

### Re-extracting pins on rotation

The Cloudflare LE E7 intermediate rotates ~yearly. To regenerate the SPKI pin:

```bash
openssl s_client -connect scan-api.scan-ai.xyz:443 -servername scan-api.scan-ai.xyz -showcerts </dev/null 2>/dev/null \
  | awk '/BEGIN CERT/{i++} i==2' \
  | openssl x509 -pubkey -noout | openssl pkey -pubin -outform DER \
  | openssl dgst -sha256 -binary | base64
```

Edit `PIN_LE_E7_INTERMEDIATE` in `ScanAiClient.kt`. ISRG Root X1 is stable until 2035 — leave alone.

### Debug-build-only test SMS injector

Debug APK ships with a `SmsTestBroadcastReceiver` (under `app/src/debug/`) that lets you exercise the full classification path from a laptop without a real cellular SMS. Release APKs do not include it (Gradle merges `src/debug/AndroidManifest.xml` only into the debug variant).

**Trigger from laptop while phone is connected via adb:**

```bash
adb shell am broadcast -a com.uow.scan.TEST_SMS -p com.uow.scan \
  --es sender '"+61400111222"' \
  --es body  '"Linkt: Your account has unpaid tolls. Pay now to avoid penalty: https://linkt-au.com/pay"'
```

What it exercises (in order):
1. Receiver enqueues `SmsForwardWorker` via `WorkManager` (skips the system-protected `SMS_RECEIVED` action)
2. Worker reads URL from `PreferencesManager.getSmsServerUrl` (defaults to `scan-api.scan-ai.xyz`)
3. `ScanAiClient` Retrofit POSTs `{"sms": ...}` over HTTPS with LE E7 + ISRG X1 pin
4. Cloudflare edge → `cloudflared.service` tunnel → `scan-ai-shim.service` → Ollama
5. Response uppercased at boundary, inserted into `sms_verdicts` Room table
6. `Notification.Builder` fires on `scan_sms_scam_channel` if verdict ≠ SAFE
7. SMS Verdicts screen reflects the new row + green pill on next resume

To verify the verdict landed, stream the Room DB out (the device doesn't ship `sqlite3`, so use Python sqlite3):

```bash
adb exec-out run-as com.uow.scan sh -c 'cat databases/scan_db'      > /tmp/scan_db
adb exec-out run-as com.uow.scan sh -c 'cat databases/scan_db-wal'  > /tmp/scan_db-wal
adb exec-out run-as com.uow.scan sh -c 'cat databases/scan_db-shm'  > /tmp/scan_db-shm

python3 -c "
import sqlite3, json
con = sqlite3.connect('/tmp/scan_db')
con.row_factory = sqlite3.Row
for row in con.execute('SELECT * FROM sms_verdicts ORDER BY id DESC LIMIT 1'):
    print(json.dumps(dict(row), indent=2, default=str))
"
```

Verified working on Samsung A17 (`R5GL139AA5L`) on 2026-05-07 — Linkt-typosquat round-trip in ~1 s, verdict = SCAM, reasoning matched the few-shot.

### SMS Verdicts connection-status pill

Lives at top of `SmsScamActivity` (the screen you reach by tapping SMS Scam Detection from the home tile). A small pill rendered under the subtitle, tappable to re-check.

**State machine** (`SmsScamActivity.ConnState`):
- `CHECKING` (gray dot + `sms_v4_conn_checking`) — initial render and during a probe in flight.
- `OK` (green + `sms_v4_conn_ok`) — `/health` returned 200 with `status: "ok"`.
- `WARN` (yellow + `sms_v4_conn_warn`) — `/health` returned 2xx but body wasn't `{status: "ok"}`.
- `BAD` (red + `sms_v4_conn_bad` or `sms_v4_conn_bad_code`) — non-2xx response, network failure, or TLS pin mismatch.
- `CACHED` (yellow + `sms_v4_conn_cached`) — short-circuit: `PreferencesManager.isSmsFallbackEnabled` is true, so the pipeline is bypassing the network. No probe is sent.

**Trigger points:** `onResume()` (every time the screen becomes visible) and `statusPill.setOnClickListener` (tap-to-recheck). Probe runs on `Dispatchers.IO`; pill flip on `Dispatchers.Main`.

**Why this exists:** at-a-glance demo-day signal that the laptop side is alive without leaving the SMS Verdicts screen. If the demo is happening in front of fair attendees, you don't want to context-switch to the AI Server screen just to confirm the server is up.

---

## Remaining work

### 1. ~~Privacy policy disclosure rewrite~~ — done 2026-05-07

`PrivacyPolicyActivity.kt` section 3 body has been rewritten to the wording below; the now-stale `SCAN_AI_CERT_PIN` literal and bearer-token mention are gone. The user-facing strings (`privacy_v4_section_3` header, `privacy_v4_commit_ai_d` commitment) and other "self-hosted" copy across home/onboarding/signup were tightened in the same pass.

> If you enable SMS Scam Detection, message text is sent over HTTPS (TLS 1.2+, certificate-pinned) to S'CAN's classifier server (`scan-api.scan-ai.xyz`), routed via Cloudflare's edge for TLS termination. The server runs a local LLM and never logs your messages. You can switch to on-device cached classification at any time from Settings → AI Server.

### 2. ~~Close the Linkt typosquat hole~~ — done 2026-05-07

Two brand-typosquat few-shots added to `SYSTEM_PROMPT` (Linkt + AusPost): legit AU brand domains end in `.com.au`, scam variants use `brand-au.com` / `brand-track.com`. Re-running `EVAL_MODELS=scan-classifier:v1 .venv/bin/python run_eval.py au_eval_set.json` after the shim restart now lands **29/29 = 100%** with safe-recall preserved.

| Set | Cases | Acc | Scam recall | Safe recall | p50 ms |
|---|---|---|---|---|---|
| AU regional (prompt v1.1, both Linkt + AusPost typosquat shots) | 29 | **100.0%** | 100% | 100% | 628 |

### 3. ~~Persistent services~~ — done 2026-05-07

Both pieces of the runtime are now systemd services with `Restart=on-failure` + `WantedBy=multi-user.target`. Survive reboot, no manual launch needed on demo day.

**FastAPI shim — `/etc/systemd/system/scan-ai-shim.service`:**

```ini
[Unit]
Description=S'CAN AI sidecar (FastAPI shim on :8000)
Documentation=file:///home/cagri/Desktop/SCAN_AI_SERVER_COOKBOOK.md
After=network-online.target ollama.service
Wants=network-online.target

[Service]
Type=simple
User=cagri
Group=cagri
WorkingDirectory=/home/cagri/scan-ai-shim
Environment=SCAN_MODEL=scan-classifier:v1
Environment=OLLAMA_URL=http://127.0.0.1:11434
ExecStart=/home/cagri/scan-ai-shim/.venv/bin/uvicorn main:app --host 127.0.0.1 --port 8000
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
```

**Cloudflare tunnel — `/etc/systemd/system/cloudflared.service`:**

```ini
[Unit]
Description=Cloudflare Tunnel — scan-api (S'CAN AI sidecar)
Documentation=file:///home/cagri/Desktop/SCAN_AI_SERVER_COOKBOOK.md
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=cagri
Group=cagri
ExecStart=/usr/local/bin/cloudflared --config /home/cagri/.cloudflared/config.yml tunnel run scan-api
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Both: 4 edge connections register on each cloudflared start (syd / mel POPs); shim exits with bad-config error if `~/scan-ai-shim/.venv` is missing.

⚠ **Lesson:** `sudo cloudflared service install` in 2026.3.0 was opaque/hung — printed nothing, never created a unit. The hand-written unit above is the supported path. Don't retry `service install`.

**To recreate either unit if it disappears:**

```bash
# 1. Write the unit body above to /tmp/<name>.service (use your editor of choice)
# 2. Install + enable:
sudo cp /tmp/scan-ai-shim.service /etc/systemd/system/   # or cloudflared.service
sudo systemctl daemon-reload
sudo systemctl enable --now scan-ai-shim                 # or cloudflared
systemctl is-active scan-ai-shim                         # verify
```

**Day-to-day:**

```bash
systemctl is-active scan-ai-shim cloudflared            # one-line status check
systemctl status scan-ai-shim cloudflared               # detailed
journalctl -u scan-ai-shim -f                           # shim live logs
journalctl -u cloudflared -f                            # tunnel live logs (4 "Registered tunnel connection" lines on each start)
sudo systemctl restart scan-ai-shim                     # pick up edits to ~/scan-ai-shim/main.py (SYSTEM_PROMPT changes etc.)
sudo systemctl restart cloudflared                      # rare; only if config.yml changes
```

---

## References

**Local artefacts:**
- `~/Desktop/SCAN_AI_SERVER_COOKBOOK.md` — this file (canonical runbook)
- `~/Desktop/SCAN_AI_Model_Performance_Report.pdf` — formal eval report (7 pages)
- `~/scan-ai-shim/` — shim, eval harness, eval sets, PDF generator
- `~/.cloudflared/{cert.pem, config.yml, <uuid>.json}` — tunnel auth + config

**Memory** (auto-loaded by Claude Code from `~/.claude/projects/-home-cagri/memory/`):
- `project_v14_scope_freeze.md` — scope (reopened 2026-05-06 for fair demo)
- `project_fair_demo_infra.md` — this stack, eval results summary
- `project_mullvad_docker_iptables.md` — the Docker trap (avoid)
- `feedback_no_docker_socket_chmod.md` — never `chmod /var/run/docker.sock`

**S'CAN code:** `~/Scan1.4.2/Scan1.4.2 (Copy)/source/` (canonical). `~/Desktop/Scan1.4.2-R8/source/` is the frozen release-build copy — do not edit.

**External:**
- Cloudflare dashboard: https://dash.cloudflare.com → Websites → `scan-ai.xyz`, Zero Trust → Networks → Tunnels → `scan-api`
- Cloudflare Tunnel docs: https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/
- Ollama API: http://localhost:11434/api/tags , https://github.com/ollama/ollama/blob/main/docs/api.md
- Scamwatch (AU pattern source for eval set): https://www.scamwatch.gov.au

---

## Secrets — never commit, never paste publicly

- `~/.cloudflared/cert.pem` — Cloudflare account auth (equivalent to login)
- `~/.cloudflared/868f0d63-5ef2-41a5-8443-9ac60dea386e.json` — tunnel credentials
- Cloudflare dashboard password
- (Future) `SCAN_API_TOKEN` — bearer token for FastAPI shim

If any of these leaks: Cloudflare dashboard → revoke API token / delete & recreate tunnel.
