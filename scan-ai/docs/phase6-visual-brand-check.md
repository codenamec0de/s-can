# Phase 6 — Visual Brand Impersonation Check (design draft)

Owner: Cagri Alaf
Status: Design only. Skeleton code in `~/scan-ai/visual_brand_check.py`. Not wired to `/v1/url-check` yet.
Model: Qwen2-VL-7B (Q4) via Ollama, local. Single concurrent call (FastAPI gate handled elsewhere).
Budget: < 4 s for the VLM call. Overall URL-check budget is 8 s.

## 1. Goal

Phase 5 hands us `screenshot.png` + DOM hash for a suspect URL. Phase 6 answers one question: **does this page visually impersonate one of our 15 AU brands?** Static analysis already flags `auspost-delivery.xyz` as suspicious. The screenshot lets us upgrade the verdict from "suspicious domain" to "phishing impersonating Australia Post" with cited visual evidence.

We do not try to be a general logo classifier. The brand set is closed (15 entries). We pass the brand list into the prompt and ask the VLM to score each one.

## 2. Screenshot preprocessing

### 2.1 Crawl viewport

The Phase 5 crawler should render at **1280 x 800** (desktop-like) and capture **above-the-fold only** — phishing kits put logo + login form in the first viewport. A full-page scroll capture wastes tokens on footer/legal cruft that does not change the verdict.

### 2.2 Resize before VLM

Qwen2-VL splits the image into 28x28 patches; each patch is ~1 token. A raw 1280x800 PNG is ~1300 visual tokens which is fine but slow. We downscale to **896 x 560** before sending. That is ~640 visual tokens, keeps logo and text legible, and shaves ~1 s off inference.

### 2.3 Format

**JPEG quality 85**. Logos and text survive at 85 with no perceptible loss for the VLM's purpose, and file size drops ~6x vs PNG. We keep the original PNG on disk for audit; only the JPEG goes to Ollama.

### 2.4 Pre-flight checks

- Reject screenshots smaller than 200 px on either side (likely a crawler error).
- Reject if file > 5 MB after compression (defensive; should never happen at q=85).
- Strip EXIF.

## 3. Brand list in the prompt

15 brands, each with a name, 1-3 aliases, official domains, and a short visual identity hint (primary colour, logo description). The full record is too verbose for every prompt. We compress to one line per brand:

```
- Australia Post (AusPost): red & white, stylised "P" logo, auspost.com.au
- Commonwealth Bank (CommBank, CBA): yellow & black diamond, commbank.com.au
- ANZ: blue, three vertical bars logo, anz.com.au
... (12 more)
```

Total brand block is ~1.2 KB of text, well under any context concern. The visual hints are authored once in `brands.json` under a new `visual_hint` field (added in Phase 6 prep).

## 4. Prompt template

The prompt is a module-level constant in `visual_brand_check.py`. Key design choices:

1. **System role** pins the model as a fraud analyst, not a helpful assistant — this materially reduces hedging ("I cannot be certain...").
2. **Closed-set instruction**: "Only score brands from the list. If none match, return all zeros." Prevents the model inventing a 16th brand.
3. **Required JSON output** with explicit schema in the prompt. Qwen2-VL-7B follows JSON schemas reliably when the schema is shown inline.
4. **Score anchoring**: 0.0 = no visual similarity, 0.5 = uses similar colours / generic resemblance, 0.8 = logo or layout clearly mimics, 1.0 = pixel-level clone. Anchored scales reduce drift across runs.
5. **Evidence is required, not optional** — model must cite at least one anchor per non-zero score. This is the main mechanism against hallucinated matches.

See `VLM_PROMPT_TEMPLATE` in the skeleton file.

## 5. Output schema

Returned by `check_brand_impersonation()` and surfaced through `/v1/url-check`:

```json
{
  "top_match": "Australia Post",
  "confidence": 0.87,
  "per_brand": [
    {
      "brand": "Australia Post",
      "match_score": 0.87,
      "rationale": "Page displays the red AusPost logo top-left and a parcel-tracking form matching the official site layout.",
      "evidence": [
        "logo top-left matches AusPost red P mark",
        "colour palette dominated by AusPost red (#E51C23)",
        "layout mimics auspost.com.au tracking page",
        "URL bar shows auspost-delivery.xyz, not auspost.com.au"
      ]
    },
    { "brand": "Commonwealth Bank", "match_score": 0.0, "rationale": "...", "evidence": [] }
  ],
  "model": "qwen2-vl:7b-q4",
  "latency_ms": 3120,
  "image_dims": [896, 560]
}
```

`top_match` is the brand with highest `match_score`, or `null` if all scores < 0.5. `confidence` equals the top score. The orchestrator (Phase 4 + 6 fusion) will combine this with the static signals; e.g. `top_match=AusPost` AND domain has high Levenshtein distance to `auspost.com.au` is the strong-signal case.

## 6. Eval harness

### 6.1 Layout

```
~/scan-ai/eval/visual_brand/
  samples/
    0001_auspost_clone/
      screenshot.png
      meta.json          # { "ground_truth_brand": "Australia Post", "expected_min_score": 0.7, "source": "synthetic" }
    0002_legit_auspost/
      screenshot.png
      meta.json          # { "ground_truth_brand": "Australia Post", "expected_min_score": 0.8, "source": "real_legit" }
    0003_random_blog/
      screenshot.png
      meta.json          # { "ground_truth_brand": null, "expected_min_score": 0.0 }
    ...
  run_eval.py
  results/
    YYYY-MM-DD_HHMM.json
```

### 6.2 Sample sourcing (~30–50 samples target)

- **~15 synthetic clones**: spin up small static HTML pages copying the logo + colour scheme of each of the 15 brands, screenshot them. Built once, reused. Acts as the "obvious phishing" set.
- **~15 real legit captures**: screenshot the actual official login pages for each brand. Tests that we do not over-flag the genuine sites (their `top_match` should equal the brand, with high score — this is correct, the URL-check fusion layer will see the official domain and treat it as benign).
- **~10 negatives**: random unrelated pages (news sites, blogs, GitHub). All scores should be near zero.
- **~5 real phishing**: captures from public reports (PhishTank AU entries, ACSC alerts) where the original URL is dead but the screenshot survives. Hard cases.

### 6.3 `run_eval.py` plan

Skeleton only:

1. Iterate `samples/*/`, load `screenshot.png` + `meta.json`.
2. Call `check_brand_impersonation(path, brands)`.
3. For each sample compute: pass/fail vs `expected_min_score`, latency, top_match correctness.
4. Aggregate per-brand precision/recall (treat `match_score >= 0.5` as positive).
5. Write timestamped JSON to `results/`. Print summary table.

No CI integration in Phase 6. Run manually after prompt changes.

## 7. Open risks

- **Latency under 4 s is not yet verified** — Q4 7B on a single mid-tier GPU might land at 5-6 s with 640 visual tokens. If we miss the budget the fallback is downscaling further (640x400) or moving to Qwen2-VL-2B for a quality hit.
- **Score calibration drift** between Qwen2-VL versions. Pin the Ollama model digest, not the tag.
- **Real phishing samples are scarce** for AU brands specifically. May need to lean more on synthetic clones than is ideal.

## 8. Out of scope for Phase 6

- Multi-page crawl (Phase 5 owns that).
- Logo-only matching without page context (we always have a screenshot).
- Brands outside the AU 15. International brands are a Phase 7+ conversation.
