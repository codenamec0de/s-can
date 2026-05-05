---
tags: [scan, dependencies, android, gradle]
type: reference
created: 2026-05-06
---

# Dependencies — Android

From `source/app/build.gradle.kts`. All versions pinned (no dynamic
ranges). See [[Build Config]] for plugin and toolchain versions.

## Toolchain / plugins

| Plugin | Version | Purpose |
|---|---|---|
| `com.android.application` | 8.13.2 | AGP — Android build system. |
| `org.jetbrains.kotlin.android` | 1.9.0 | Kotlin compiler for Android. |
| `com.google.gms.google-services` | 4.4.0 | Wires `google-services.json` into Firebase init. |
| `com.google.devtools.ksp` | 1.9.0-1.0.13 | KSP — runs Room's annotation processor. |

| Setting | Value |
|---|---|
| `compileSdk` | 34 |
| `minSdk` | 26 |
| `targetSdk` | 34 |
| `versionCode` | 4 |
| `versionName` | 1.4 |
| `applicationId` | `com.uow.scan` |
| `namespace` | `com.uow.scan` |
| `sourceCompatibility` / `targetCompatibility` | Java 1.8 |
| `jvmTarget` | 1.8 |

## AndroidX core

| Dependency | Version | Why |
|---|---|---|
| `androidx.core:core-ktx` | 1.12.0 | Kotlin extensions over Android core. |
| `androidx.appcompat:appcompat` | 1.6.1 | Backport of new platform features to old SDKs. |
| `com.google.android.material:material` | 1.11.0 | Material 3 components — buttons, app bars, dialogs. |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 | Flat layout engine used across screens. |
| `androidx.activity:activity-ktx` | 1.8.2 | `viewModels()`, result contracts, etc. |
| `androidx.fragment:fragment-ktx` | 1.6.2 | Fragment KTX helpers (used by Navigation). |

## Navigation

| Dependency | Version | Why |
|---|---|---|
| `androidx.navigation:navigation-fragment-ktx` | 2.7.6 | Fragment-based nav graph for [[Activities & Screens#MainActivity\|MainActivity]] tabs. |
| `androidx.navigation:navigation-ui-ktx` | 2.7.6 | Bottom nav / app bar wiring. |

## Lifecycle

| Dependency | Version | Why |
|---|---|---|
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.7.0 | ViewModel base + `viewModelScope`. |
| `androidx.lifecycle:lifecycle-livedata-ktx` | 2.7.0 | LiveData KTX. |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.7.0 | `lifecycleScope`, lifecycle-aware coroutines. |

## RecyclerView / lists

| Dependency | Version | Why |
|---|---|---|
| `androidx.recyclerview:recyclerview` | 1.3.2 | Used by every list screen — see `adapter/` package. |
| `androidx.cardview:cardview` | 1.0.0 | Card containers in list items and dashboards. |

## Auth

| Dependency | Version | Why |
|---|---|---|
| `com.google.firebase:firebase-bom` | 32.7.0 | Firebase BOM — pins all firebase-* artefacts. |
| `com.google.firebase:firebase-auth-ktx` | (BOM) | Firebase Auth — Google sign-in backend. |
| `com.google.android.gms:play-services-auth` | 20.7.0 | Google Sign-In client (Activity result contracts). |

## Networking

| Dependency | Version | Why |
|---|---|---|
| `com.squareup.retrofit2:retrofit` | 2.9.0 | HTTP client for HIBP and Scan-AI sidecar. |
| `com.squareup.retrofit2:converter-gson` | 2.9.0 | Gson body converter. |
| `com.squareup.okhttp3:logging-interceptor` | 4.12.0 | Logs requests in debug — disabled in release. |

> **TLS pinning hookpoint**: V1.4 item 2.2 — install an OkHttp
> `CertificatePinner` using the SHA-256 SPKI pin from
> `BuildConfig.SCAN_AI_CERT_PIN`. See [[Build Config]].

## Concurrency

| Dependency | Version | Why |
|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.7.3 | Main-thread dispatcher for coroutines. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.7.3 | Core coroutines runtime. |

## Background work

| Dependency | Version | Why |
|---|---|---|
| `androidx.work:work-runtime-ktx` | 2.9.0 | WorkManager — `SmsForwardWorker`, `PermissionMonitorWorker`. |

## Images

| Dependency | Version | Why |
|---|---|---|
| `com.github.bumptech.glide:glide` | 4.16.0 | App-icon loading and Google profile photos. |

## Persistence

| Dependency | Version | Why |
|---|---|---|
| `androidx.room:room-runtime` | 2.6.1 | Room runtime. |
| `androidx.room:room-ktx` | 2.6.1 | Coroutines + Flow integration. |
| `androidx.room:room-compiler` (KSP) | 2.6.1 | Annotation processor — generates DAO impls. |

See [[Data Layer - Room]] for the entities and DAOs.

## Onboarding / UX

| Dependency | Version | Why |
|---|---|---|
| `androidx.viewpager2:viewpager2` | 1.0.0 | Onboarding pager. |
| `androidx.biometric:biometric` | 1.1.0 | Biometric prompt for sensitive actions. |

## Visualisation

| Dependency | Version | Why |
|---|---|---|
| `com.github.PhilJay:MPAndroidChart` | v3.1.0 | Dashboard charts (score history, weekly stats). Pulled from JitPack. |

## Test

| Dependency | Version | Why |
|---|---|---|
| `junit:junit` | 4.13.2 | Unit tests. |
| `androidx.test.ext:junit` | 1.1.5 | AndroidJUnit4 runner. |
| `androidx.test.espresso:espresso-core` | 3.5.1 | UI tests. |

## At a glance

29 direct dependencies across 12 categories. Heaviest categories are
**AndroidX core** (6) and **persistence + nav + lifecycle** (8). No
RxJava, no Dagger/Hilt, no Compose — the app is view-binding-based.
