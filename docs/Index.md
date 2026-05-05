---
tags: [scan, index, v1.4.2]
type: index
created: 2026-05-06
---

# S'CAN — V1.4.2 Vault

Snapshot of the **S'CAN** Android privacy & security audit suite as of
**V1.4.2** (R8 release-ready, 2026-05-06). This vault documents what
shipped, what it depends on, and how the pieces fit together.

> Repository: <https://github.com/codenamec0de/s-can>
> Release: <https://github.com/codenamec0de/s-can/releases/tag/v1.4.2>
> This vault lives in-tree at `docs/` — open the folder as an Obsidian vault.

---

## Start here

- [[Project Overview]] — what S'CAN is, in one screen.
- [[Architecture]] — Android app + AI sidecar diagram and data flow.

## Dependencies

- [[Dependencies - Android]] — Gradle libraries, what each one is for.
- [[Dependencies - Python Sidecar]] — `requirements.txt` annotated.
- [[Dependencies - External Services]] — Firebase, HIBP, Exodus,
  Ollama, and the rest.

## Building and running

- [[Build & Run]] — debug build, release build, sidecar bring-up.
- [[Build Config]] — Gradle, ProGuard, R8, signing, BuildConfig fields.

## App internals

- [[Activities & Screens]] — every Activity in the manifest.
- [[Permissions]] — manifest permissions and why each is needed.
- [[Components - Receivers Services Workers]] — non-Activity Android
  components.
- [[Data Layer - Room]] — entities and DAOs.

## Sidecar

- [[AI Sidecar API]] — FastAPI endpoints, auth model, classifier.

## Forward-looking

- [[Roadmap & Status]] — V1.4 scope freeze, V1.5+ plans.

---

## Related vaults

- `scan_vault` (separate) — the project's *why-memory* (decisions,
  syntheses, runbooks). This vault is the *what-is* snapshot; that one
  is the *why-it-is* archive.
