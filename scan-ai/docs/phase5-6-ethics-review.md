# Phase 5/6 Ethics and Privacy Review

Pre-implementation review for Phase 5 (sandboxed crawler) and Phase 6 (local VLM brand judgement). Locked decisions (Mullvad egress, Docker + seccomp + AppArmor sandbox, all-local inference) are not relitigated. Focus: privacy posture, bias across the 15 AU brands, and what becomes disclosable for Phase 9.

## 1. Phase 5 privacy posture

### 1.1 What gets logged

Existing logs (`main.py:269-273`, `main.py:296-301`) capture verdict, risk, brand match, latency, and the first 80 chars of the URL. Minimum responsible Phase 5 crawl log line:

- `crawl_id` — opaque token (`secrets.token_urlsafe(12)`), not derived from SMS or user.
- `url_hash` — SHA-256 of normalised URL (lowercase host, stripped query). Enables dedup.
- `host` — registrable domain only (`tldextract`). Already exposed by static analysis.
- `egress_country` — country code only, to confirm Mullvad routing. Never the exit IP.
- `verdict_summary`, `latency_ms`, `bytes_downloaded`, `requests_count`.

MUST NOT appear in any log, journald sink, or screenshot filename:

- SMS body or any substring.
- Sender phone number.
- Bearer tokens (`tokens.json` lives outside logs today; keep it that way).
- The full URL with query/fragment. Phishing kits encode victim IDs in `?u=`, `/r/<b64>`; those become tracking primitives once logged. Keep the full URL only in the audit record (1.4).
- Anything linking the crawl to a specific phone install. The current `verify_token` does not store token → user binding; do not start now.

### 1.2 Screenshot retention

- **Retention: 7 days default**, then auto-delete. Long enough for user-initiated review and feedback; longer is dead weight that becomes a breach surface.
- **Path**: `/var/lib/scan-ai/crawls/<YYYY-MM-DD>/<crawl_id>.png`. Filename is the opaque `crawl_id` — not derived from URL, hash, sender, or any SMS field. Filenames leak via shell history, error reports, tarballs.
- **Cleanup**: single systemd timer (`scan-ai-crawl-cleanup.timer`), daily, deletes mtime > 7 days. ~10 lines total. Do not build a per-request TTL daemon.
- **Permissions**: `0700`, FastAPI service user only.

### 1.3 What returns to the phone

Existing contract `{verdict, risk, brand_match, signals}` (`UrlCheckResponse`, `main.py:111-117`). Phase 5/6 adds only:

- `crawl_id` (opaque) — handle for "show me what S'CAN saw".
- `screenshot_available: bool` — true if Phase 5 ran.
- One extra `UrlSignal` entry `visual_brand_match` with brand + confidence. No schema break.

Do not push raw screenshot bytes to the phone unsolicited. Reasons: (a) inflates response ~150 KB, (b) the screenshot may contain victim email or session-bound content reflected from a personalised URL, (c) once on the phone it falls under Android scoped-storage lifecycle, out of scope. If Phase 9 wants to render it, the phone fetches on demand from `/v1/crawl/<crawl_id>/screenshot` over the same bearer-token channel. Data stays server-side until the user explicitly asks.

### 1.4 Audit trail

One JSONL line per crawl in `/var/lib/scan-ai/crawls/<date>/audit.jsonl`: `crawl_id`, `ts_utc`, `url_full` (the only place the full URL is retained), `url_hash`, `egress` (e.g. `mullvad-au`), `screenshot_path`, `dom_hash`, `request_log_path`, `vlm_top_match`, `vlm_confidence`, `static_signals`. Same 7-day rotation, same `0700` directory. No per-user index — lookup is by `crawl_id` returned to the phone, supplied back by the user. This avoids any DB mapping users to scanned URLs.

Privacy Act 1988 APP 11.2 (destruction/de-identification once not needed) applies — 7-day TTL with a documented purpose is defensible; indefinite retention is not. APP 1.2 is the hook for Phase 9 disclosure.

### 1.5 Failure modes that are also privacy bugs

1. **Mullvad reconnect storm leaks host IP.** WG goes down, kernel falls back to default route, Chromium fetches over the home connection. Mitigation: container netns has default route only via `wg0`, plus `iptables OUTPUT -j DROP` for everything off `wg0`. Startup canary hits an IP-echo and aborts if egress country is wrong.

2. **Container escape touching SMS-bearing data.** The crawler must not see `/var/log/scan-ai/` or `tokens.json`. Mount only the screenshot output dir, read-write. The locked "no host bind mounts" decision covers this — do not relax under deadline pressure.

3. **journald sinking FastAPI logs to syslog.** Default uvicorn-systemd pipes stdout to journald (`0640 root:systemd-journal`). If a full URL ever gets logged (regression of 1.1), it lands in journalctl exports and bug-report tarballs. Mitigation: file handler only, set `StandardOutput=null` in the unit.

4. **Screenshot of a personalised page.** Phishing kits accept `?email=alice@example.com` and pre-fill the form; the email then appears in the screenshot. Mitigation: crawl with query/fragment stripped where feasible; audit JSONL stays local-admin only; Phase 9 disclosure warns the screenshot may contain URL-reflected content.

5. **DOM hash includes session-tagged attributes.** Capturing `data-csrf` / `data-session` defeats dedup and risks logging session tokens. Mitigation: hash structural DOM (tag tree + visible text), not full attribute set.

## 2. Phase 6 false-positive bias across the 15 brands

Qwen2-VL-7B is trained on global web data; the brand set is AU-specific and confusability inside it is uneven.

### 2.1 Visual confusability — highest-risk pairs

- **CommBank vs ANZ.** Both run yellow-led campaign pages (CommBank yellow + black; ANZ blue with yellow accents). `brands.json` lists identical keywords for both, so static text won't disambiguate.
- **Westpac vs NAB.** Two red banks; on a 896x560 JPEG the dominant-colour cue collapses. The VLM at Q4 frequently reads either as "red bank logo".
- **ATO vs myGov vs Centrelink vs ServiceNSW.** All four use Coat of Arms and Commonwealth sans-serif typography. A government-framed phishing page without an explicit logo pulls positive scores on all four. Worst cluster in the set.
- **AusPost vs Linkt.** Both lean red, both run "pay now" delivery/toll flows. A logo-stripped red page pulls weak signal toward both.
- **Woolworths vs Coles.** Lower risk (green vs red) but "supermarket gift card" scams present generic framing that scores 0.5 on both.

### 2.2 Underrepresentation in VLM training

Globally visible brands (AusPost, the four banks, Telstra) have a web footprint Qwen2-VL has likely seen. AU-only services with thinner global presence are likely under-detected:

- **Linkt** — toll roads in three states; almost certainly under-trained.
- **ServiceNSW** — state government, narrow geography.
- **Centrelink** — visually subsumed under the myGov shell.
- **Medicare** — name is globally generic; the AU card design is underrepresented.

These will under-fire (false negatives), the more dangerous direction (see 2.3). The `visual_hint` field already in `visual_brand_check.py` partially compensates by giving the model an explicit anchor — treat it as mitigation, not fix.

### 2.3 Asymmetric harm

FN and FP costs are not symmetric:

- **Banks, ATO, myGov, Medicare, Centrelink, ServiceNSW** — FN dominates: credentials or identity docs lost. FP is mild annoyance.
- **AusPost, Linkt** — FN dominates moderately: card-details theft → CNP fraud.
- **Telstra, Optus** — FN dominates: account takeover → SIM-swap.
- **Woolworths, Coles** — symmetric or FP-leaning: gift-card scams exist but bounded; over-flagging the real weekly-specials SMS is disruptive.

Mitigation follows: high-FN brands need lower thresholds and static-corroboration; supermarket pair gets a higher threshold to suppress nuisance flags.

### 2.4 Mitigations sized for one student

All sit inside what the codebase already has.

- **Prompt-level: require evidence anchors before any non-zero score.** The prompt at `visual_brand_check.py:45-69` already demands evidence. Tighten `_parse_vlm_response` so any result with `match_score >= 0.5` and `evidence == []` is rewritten to `0.0`. Five lines, removes confident hallucinations.
- **System-level: static-signal corroboration.** `analyze_static`/`analyze_domain` in `url_analyzer.py` already produce a static `brand_match`. Rule: surface a Phase 6 brand to the phone only if (a) the static `brand_match` agrees, or (b) VLM score >= 0.8 AND the domain is not on the official allowlist for that brand. This kills the gov/bank cross-confusion cluster in 2.1.
- **Threshold per brand class.** 0.6 for high-FN brands (banks, government, telco, AusPost, Linkt, Medicare, ServiceNSW); 0.8 for the supermarket pair. One-line change in the fusion layer.
- **Human in the loop: reuse the existing feedback button.** `/v1/feedback` (`main.py:313`) already records `original → correct`. Extend the payload to optionally include `crawl_id` so feedback ties to the audit record without storing user identity. Phase 9 copy should name this as the disclosed correction channel.

## 3. What this enables for Phase 9

Disclosable items for the in-app explainer, all derived from decisions above: (a) what does and does not leave the phone (SMS body and sender stay; URL goes; verdict comes back); (b) 7-day screenshot retention with systemd-timer cleanup as the published mechanism; (c) on-demand screenshot-fetch endpoint as the user's "show me what the system saw" affordance; (d) the 15-brand set plus the static-corroboration rule as the explanation for why a brand verdict can be wrong; (e) the feedback button as the named correction route. Privacy Act APP 1.2 and APP 5 map onto (a) and (b); APP 11.2 onto (b) and (c). Nothing here adds features outside the existing implementation path.
