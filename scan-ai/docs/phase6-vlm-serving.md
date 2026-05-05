# Phase 6 VLM Serving Topology

Status: design (research only, no implementation)
Scope: how to co-host Llama 3.1 8B (text classifier, already live) and Qwen2-VL-7B Q4 (new visual brand checker) on a single 16 GB RTX 5070 Ti without OOM.
Locked upstream: Ollama (not vLLM), Qwen2-VL-7B Q4 (not LLaVA/Internvl/cloud), Llama 3.1 8B stays, all-local inference.

## 1. VRAM math

The 16 GB ceiling is real. Approximate per-model footprint:

| Component                         | Estimate           |
|-----------------------------------|--------------------|
| Llama 3.1 8B Q4_K_M weights       | ~4.7 GB            |
| Llama KV cache @ 4k ctx           | ~0.4 GB            |
| Qwen2-VL-7B Q4 weights (LM)       | ~4.5 GB            |
| Qwen2-VL vision tower (fp16)      | ~1.3 GB            |
| Qwen2-VL KV cache @ ~3k ctx + img | unknown — measure during bring-up |
| Image preprocessing + activations | unknown — measure during bring-up |
| CUDA context + driver overhead    | ~0.5–1.0 GB        |
| Ollama runner overhead per model  | ~0.2 GB each       |

Both warm with no extra headroom: ~11–12 GB resident weights + ctx, ~4–5 GB free for activations + a single concurrent inference. This is the regime we are targeting; the strategy below treats that 4–5 GB as a single shared activation budget that **only one model may use at a time**.

## 2. Decision: keep both warm, serialize at FastAPI

Chosen: option (a) — both models stay resident in VRAM, requests are gated so only one inference runs at any moment.

Why this beats the alternatives:

- **(b) cold-load Qwen on demand.** First Phase 6 request after idle pays a multi-second model load (Qwen2-VL Q4 ≈ 5.8 GB streamed from disk, plus vision tower init). `/v1/url-check` already does WHOIS/TLS/DNS; tacking a cold load on top makes URL verdict latency unpredictable for the user-facing path. Hot/cold flapping also wears the SSD and complicates `keep_alive` tuning.
- **(c) cold-load Llama.** Llama is the **hot path** — every received SMS hits `/v1/classify`. Cold-loading it is a non-starter.
- **(d) tighter quantization.** Qwen2-VL at Q3 visibly degrades OCR and small-logo grounding, which is the entire point of Phase 6. Llama at Q3 hurts JSON adherence in the classifier, which we already tuned around. Q5 goes the wrong way (more VRAM). Reject.
- **(e) CPU offload of one model.** Qwen2-VL on CPU is too slow to be useful (vision encode alone). Llama on CPU is feasible (~3–5 tok/s on 7700X) but turns the SMS hot path into a UX regression. Rejected, but kept as a documented fallback if (a) hits real OOM in load testing.

Option (a) trades concurrency (we cannot run an SMS classify and a visual brand check at the same instant) for predictable latency and zero cold-start penalty. For this workload — single-user-ish thesis app, SMS arrivals are bursty but rare, URL checks are even rarer — serialization is acceptable.

### Ollama config to pin both warm

```
# systemd drop-in or env file for the ollama service
OLLAMA_KEEP_ALIVE=24h         # never evict
OLLAMA_MAX_LOADED_MODELS=2    # both resident
OLLAMA_NUM_PARALLEL=1         # one inference slot per model; we gate above this anyway
OLLAMA_FLASH_ATTENTION=1      # smaller KV cache footprint
```

Modelfile fragment for Qwen2-VL with capped context (keep KV cache predictable):

```
FROM qwen2-vl:7b-q4_K_M
PARAMETER num_ctx 3072
PARAMETER num_predict 512
PARAMETER temperature 0.1
```

Cap `num_ctx` because screenshots already consume vision tokens; we do not need a large text context for a brand-match prompt.

## 3. Request gating in `main.py`

A single `asyncio.Semaphore(1)` guarding all GPU-bound calls (classify + visual-brand-check). Same lock for both, because they share the activation budget. Sketch:

```python
# top of main.py
GPU_LOCK = asyncio.Semaphore(1)
GPU_LOCK_TIMEOUT_S = 30          # /v1/classify
VLM_LOCK_TIMEOUT_S = 60          # /v1/visual-brand-check (image preprocess + longer infer)

async def acquire_gpu(timeout: float):
    try:
        await asyncio.wait_for(GPU_LOCK.acquire(), timeout=timeout)
    except asyncio.TimeoutError:
        raise HTTPException(503, "GPU busy; retry")
    return GPU_LOCK
```

Wrap the `httpx` call to Ollama inside `async with` semantics: acquire → call → release in `finally`. Place the lock acquisition **inside** each endpoint, not in middleware, because non-GPU paths (`/v1/health`, `/v1/url-check` static stage, `/v1/feedback`, `/v1/admin/token`) must not block.

Behavior under contention:

| Arrives during | Behavior |
|---|---|
| `/v1/classify` while `/v1/visual-brand-check` is mid-flight | queue with 30 s timeout, then 503 |
| `/v1/visual-brand-check` while `/v1/classify` is mid-flight | queue with 60 s timeout, then 503 |
| Two `/v1/classify` back-to-back | queue, FIFO via semaphore |
| `/v1/url-check` static-only (no Phase 6 trigger) | does not touch the lock; runs in parallel |

Single-process uvicorn worker. **Do not run `--workers > 1`**; per-worker semaphores would not coordinate and we would lose the gate. If we ever need multi-worker, switch to a Redis lock or move the gate to an Ollama-fronting reverse proxy.

## 4. OOM and failure handling

User-facing endpoint contract:

| Condition | HTTP | Body |
|---|---|---|
| Lock acquire timeout | 503 | `{"error":"gpu_busy","retry_after_s":5}` + `Retry-After: 5` header |
| Ollama unreachable (connection refused) | 502 | `{"error":"ollama_down"}` |
| Ollama returns 500 / OOM signature | 503 | `{"error":"gpu_oom","model":"qwen2-vl"}`; log full Ollama body |
| Model not loaded (404 from Ollama on `/api/generate`) | 503 | `{"error":"model_not_loaded","model":"..."}`; trigger one-shot reload attempt in background |
| Inference timeout (httpx) | 504 | `{"error":"inference_timeout"}` |
| Bad request (empty image, missing brand list) | 400 | `{"error":"bad_request","detail":...}` |

OOM detection: parse the Ollama error body for `cudaMalloc`, `out of memory`, or HTTP 500 with the runner stderr. On OOM, **do not retry inside the request** — release the lock, return 503, and emit a metric. A retry while the activation memory is still fragmenting will just OOM again.

Recovery strategy on repeated OOM (>3 in 60 s for the same model): supervisor (systemd `Restart=on-failure` on ollama.service) is the floor. App-level: a `/v1/admin/reload` endpoint that calls `ollama stop <model>` then a warm-up generate. Out of scope for Phase 6 to automate — flag for Phase 11.

The Android client already retries `/v1/classify` once on 5xx; document that 503 with `Retry-After` is the canonical "try again shortly" signal so the client's existing backoff applies.

## 5. Observability hooks (minimum viable)

Expose `/v1/metrics` (Prometheus text format, ungated or admin-token-gated) with at minimum:

- `scan_ai_gpu_lock_wait_ms` (histogram, label `endpoint`)
- `scan_ai_gpu_lock_holders_total` (counter, label `endpoint`)
- `scan_ai_inference_ms` (histogram, label `model`)
- `scan_ai_ollama_errors_total` (counter, label `kind`: `oom|timeout|down|model_missing`)
- `scan_ai_vram_used_mb` and `scan_ai_vram_total_mb` (gauges, scraped via `nvidia-smi --query-gpu=memory.used,memory.total --format=csv,noheader,nounits` on a 5 s tick — dump from a background task, do not call per request)
- `scan_ai_model_loaded` (gauge 0/1, label `model`) — polled from `GET /api/ps` on Ollama

That is enough to answer "is the gate working", "are we close to OOM", "is anything timing out". Full dashboarding deferred to Phase 11.

Logging: extend the existing `RequestLogMiddleware` log line with a `wait_ms=` field when GPU lock was contended. Cheap, high signal.

## 6. Honest tradeoffs

What this design gives up:

- **Concurrency = 1 across GPU work.** Two simultaneous SMS will serialize; an SMS arriving during a visual-brand-check waits up to 30 s. Acceptable for a thesis-scale app, not acceptable at 100+ rps.
- **Throughput.** vLLM with continuous batching would beat this on tokens/s. We chose Ollama upstream; this is the cost.
- **Activation headroom is unmeasured.** The 4–5 GB free figure assumes both KV caches stay near their nominal ceiling. A long screenshot prompt or a long SMS thread could push past it. Bring-up must run a soak test with realistic payloads and `nvidia-smi dmon` recording. If we land within 500 MB of full, fall back to option (b) and accept the cold-load cost on `/v1/visual-brand-check`.
- **No graceful degradation if Phase 6 OOMs in production.** The `/v1/url-check` caller will see 503 and have to render a "visual check unavailable" state. Phase 9 (disclosure UI) needs to handle this.
- **Operational complexity.** One global semaphore is simple, but it is now a real coupling between two endpoints that previously had nothing to do with each other. Future endpoints that touch the GPU must remember to acquire the lock; easy to forget. Mitigation: a `@gpu_bound` decorator that wraps acquire/release.
- **Single-worker uvicorn.** Loses HTTP-level concurrency for the non-GPU endpoints too. Fine today; revisit if `/v1/url-check` static stage becomes a bottleneck.

## 7. Open questions for bring-up

1. Actual Qwen2-VL-7B Q4 KV-cache size at typical screenshot resolutions (1080×2400 phone screens). Unknown — measure with `nvidia-smi` while running the model against a real Phase 5 capture.
2. Does Ollama's `OLLAMA_FLASH_ATTENTION=1` apply to the Qwen2-VL vision tower or only the LM? Unknown — test and document.
3. Whether `num_ctx=3072` is enough for a screenshot + brand list of 15 AU brands + system prompt. If vision tokens overflow, raise to 4096 and re-measure VRAM.
