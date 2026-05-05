---
tags: [scan, build, run]
type: how-to
created: 2026-05-06
---

# Build & Run

End-to-end recipes for getting a debug or release Android build out of
`source/`, plus bringing up the sidecar in `scan-ai/`. Assumes
`Scan1.4.2-R8/` is the project root.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Android Studio | Hedgehog (2023.1.1)+ | Open the `source/` folder. |
| JDK | 17 (Studio bundled is fine) | Compile target stays at 1.8. |
| Gradle | 8.x | Wrapped — use `./gradlew`. |
| Python | ≥ 3.10 | For the sidecar. |
| Ollama | ≥ 0.1.30 | Independent install. |

## Android — debug build

Works out of the box; Firebase init reads the bundled
`google-services.json`.

```bash
cd source
./gradlew :app:assembleDebug
# output: source/app/build/outputs/apk/debug/app-debug.apk  (~16 MB)
```

The debug APK is **not** R8-shrunk and is signed with the platform debug
key — fine for sideload testing on dev devices.

## Android — release build

Requires keystore secrets that are gitignored. See [[Build Config#Signing]].

```bash
cd source
./gradlew :app:assembleRelease
# output: source/app/build/outputs/apk/release/app-release.apk  (~6 MB)
```

R8 (full mode) shrinks, optimises, and obfuscates. The signed release
APK is what gets attached to GitHub Releases — see
[[Roadmap & Status#V1.4.2 GitHub Release]].

## AI sidecar

```bash
cd scan-ai
python3 -m venv venv && source venv/bin/activate
pip install -r requirements.txt
ollama pull qwen3.5:latest                    # ~6 GB Q4_K_M
MODEL_NAME=qwen3.5:latest \
ADMIN_TOKEN=<your-admin-token> \
python main.py
```

- Server binds `0.0.0.0:8000`.
- Mint per-tester tokens via `POST /v1/admin/token` with
  `Authorization: Bearer <ADMIN_TOKEN>`.
- See [[AI Sidecar API]] for the full endpoint list.

## Tester sideload (no source needed)

1. Download `scan-v1.4.2-release.apk` from the
   [Releases](https://github.com/codenamec0de/s-can/releases) page.
2. On Android: **Settings → Apps → Special app access → Install unknown
   apps** — allow your browser.
3. Open the APK, install, sign in with Google.

The SMS scam detector won't classify until the device can reach a
sidecar — point [[Activities & Screens#AiServerActivity]] at your
running instance.

## Useful Gradle tasks

```bash
./gradlew :app:assembleDebug         # quick debug build
./gradlew :app:bundleRelease         # release AAB (Play Store form)
./gradlew :app:lint                  # lint
./gradlew :app:test                  # JVM unit tests
./gradlew :app:connectedAndroidTest  # instrumented tests (device required)
./gradlew :app:dependencyTree        # dump resolved dep graph
./gradlew clean                      # nuke build/
```
