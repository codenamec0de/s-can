---
tags: [scan, activities, screens, ui]
type: reference
created: 2026-05-06
---

# Activities & Screens

Every Activity declared in `AndroidManifest.xml` for V1.4.2. The launcher
is `SplashActivity`; everything else is `exported="false"`.

## Auth flow

| Activity | File | Role |
|---|---|---|
| `SplashActivity` | `SplashActivity.kt` | Launcher. Routes to login or main based on Firebase auth state. Uses `Theme.Scan.Splash`. |
| `LoginActivity` | `LoginActivity.kt` | Email/password + Google sign-in. Renders the V1.4.2 logo (post-2026-05-06). |
| `SignUpActivity` | `SignUpActivity.kt` | Account creation. |
| `ForgotPasswordActivity` | `ForgotPasswordActivity.kt` | Password reset flow (V4 onboarding). |
| `PermissionsActivity` | `PermissionsActivity.kt` | First-run runtime-permission walkthrough. |

## Main shell

| Activity | File | Role |
|---|---|---|
| `MainActivity` | `MainActivity.kt` | Bottom-nav host with the dashboard, app list, and settings tabs. |
| `AppDetailActivity` | `AppDetailActivity.kt` | Detail sheet for an installed app — permissions, trackers, score. |

## Scan and reports

| Activity | File | Role |
|---|---|---|
| `ScanActivity` | `ScanActivity.kt` | Drives a fresh full-device scan (UI-side). Backed by `util/ScanRunner.kt`. |
| `ScoreHistoryActivity` | `ScoreHistoryActivity.kt` | MPAndroidChart line/bar of historical scan scores. |
| `ReportHistoryActivity` | `ReportHistoryActivity.kt` | List of past report exports. |
| `ExportReportActivity` | `ExportReportActivity.kt` | PDF / JSON / CSV export entry point. |
| `ScanComparisonActivity` | `ScanComparisonActivity.kt` | Side-by-side diff of two scan snapshots. |

## V1.4 features

| Activity | File | Role |
|---|---|---|
| `WifiSecurityActivity` | `WifiSecurityActivity.kt` | Renders findings from `util/WifiSecurityAnalyzer.kt` for the connected network. |
| `BreachCheckerActivity` | `BreachCheckerActivity.kt` | Manages monitored emails and shows HIBP results (`util/BreachChecker.kt`). |
| `SmsScamActivity` | `SmsScamActivity.kt` | Verdict feed for classified SMS — backed by `SmsVerdictDao`. |
| `SmsOnboardingActivity` | `SmsOnboardingActivity.kt` | Onboarding screen for SMS scanning consent (V1.4 item 2.1 — disclosure copy outstanding). |
| `AiServerActivity` | `AiServerActivity.kt` | Configure sidecar URL + bearer token. `windowSoftInputMode="adjustResize"`. |
| `TerminatorActivity` | `TerminatorActivity.kt` | Exposes the `TerminatorEngine` controls. Auto-revoke is V1.5 — UI scaffolding only today. |

## Settings

| Activity | File | Role |
|---|---|---|
| `AboutActivity` | `AboutActivity.kt` | Version, license, contact. |
| `PrivacyPolicyActivity` | `PrivacyPolicyActivity.kt` | Privacy policy text. |
| `ProfileActivity` | `ProfileActivity.kt` | User profile editor (display name, photo). |
| `NotificationsActivity` | `NotificationsActivity.kt` | Notification preferences. |
| `ScanScheduleActivity` | `ScanScheduleActivity.kt` | Periodic-scan scheduler. |
| `DataStorageActivity` | `DataStorageActivity.kt` | Reset / export / wipe local data. |

## Counts

24 activities total (including V1.4 onboarding and SMS screens).
- Auth: 5
- Main shell: 2
- Scan & reports: 5
- V1.4 features: 6
- Settings: 6

See [[Components - Receivers Services Workers]] for the non-Activity
pieces and [[Permissions]] for what each screen requires.
