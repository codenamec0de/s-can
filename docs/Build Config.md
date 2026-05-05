---
tags: [scan, build-config, proguard, r8, signing]
type: reference
created: 2026-05-06
---

# Build Config

Everything Gradle, ProGuard, R8, signing, and `BuildConfig` for V1.4.2.

## SDK floors and version

| Field | Value |
|---|---|
| `compileSdk` | 34 |
| `minSdk` | 26 (Android 8.0) |
| `targetSdk` | 34 (Android 14) |
| `versionCode` | 4 |
| `versionName` | 1.4 |
| `applicationId` / `namespace` | `com.uow.scan` |

`viewBinding` and `buildConfig` are both enabled in `buildFeatures`.

## BuildConfig fields

Generated into `com.uow.scan.BuildConfig` and accessible at runtime.

| Field | Type | Value | Purpose |
|---|---|---|---|
| `SCAN_AI_CERT_PIN` | String | `sha256/3u/pW+fQlDD96hrBeFQO7qNH5E7kIPHczvYxOOG9R+c=` | SHA-256 of the AI sidecar's SubjectPublicKeyInfo, base64. Rotation = code change. Consumed by V1.4 item 2.2 (`OkHttpClient` `CertificatePinner`). |

## Signing

Release signing reads from `keystore.properties` at the project root —
**gitignored**. Format:

```properties
storeFile=scan-release.jks
storePassword=...
keyAlias=scan
keyPassword=...
```

When the file is absent (e.g., on a fresh clone with no secrets), the
`signingConfigs.release` block is skipped and `assembleRelease` fails
loudly — that's the point.

The keystore itself (`scan-release.jks`) lives next to `app/` and is
also gitignored. Losing it means losing app identity on the Play Store.

## R8 / minification

In `release`:

- `isMinifyEnabled = true` — strips and renames.
- `isShrinkResources = true` — drops unused res entries.
- ProGuard files: `proguard-android-optimize.txt` (default) +
  `proguard-rules.pro` (project rules).

R8 runs in **full mode** (default in AGP 8). Result: ~6 MB APK from
~16 MB unminified.

## ProGuard rules — what's kept and why

From `app/proguard-rules.pro`:

| Rule group | Why |
|---|---|
| `-keepattributes Signature, *Annotation*` | Reflection used by Retrofit/Gson needs annotations and generic type signatures intact. |
| `retrofit2.**` kept | Retrofit relies on reflection for service interfaces. |
| `okhttp3.**`, `okio.**` warnings suppressed | OkHttp ships some optional deps it conditionally references. |
| `com.uow.scan.model.**` kept | Gson serialises these by reflection. |
| `com.uow.scan.api.**` (interfaces and nested types) kept | Retrofit + Gson reflect on `@SerializedName` and property names — must survive R8. |
| `kotlin.Metadata`, `**$Companion`, `@SerializedName` field holders | Kotlin reflection / KotlinDefaultsConverter compatibility. |
| `kotlinx.coroutines.internal.MainDispatcherFactory`, `CoroutineExceptionHandler` | ServiceLoader entries — names must survive. |
| `com.google.firebase.**` | Firebase SDK has reflective hooks. |
| Room (`extends RoomDatabase`, `@Entity`, `@Dao`) | Room's generated impls need real names. |
| `com.github.mikephil.charting.**` | MPAndroidChart uses reflection for theming. |
| `com.uow.scan.data.**` | Room entities — same as above plus room-ktx flow. |
| `com.uow.scan.api.ScanAiApiService$*` | Nested request/response classes — Gson again. |
| `com.uow.scan.BuildConfig` | Cert pin field needs to survive. |

See [[Dependencies - Android]] for what each library is doing.

## Compile and target

```kotlin
compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
kotlinOptions { jvmTarget = "1.8" }
```

Java 8 desugar isn't enabled — the codebase doesn't use Java 8+ APIs
that need it.

## Plugins

See [[Dependencies - Android#Toolchain / plugins]].
