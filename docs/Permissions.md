---
tags: [scan, permissions, manifest, security]
type: reference
created: 2026-05-06
---

# Permissions

Every `<uses-permission>` in `AndroidManifest.xml` and why it's there.
Permissions are deliberately minimal — each one is a Play Store policy
line item.

## Network

| Permission | Why |
|---|---|
| `INTERNET` | Firebase Auth, HIBP, Exodus, AI sidecar. |
| `ACCESS_NETWORK_STATE` | Detect connectivity for retry/backoff in workers and `BreachChecker`. |

## Wi-Fi

| Permission | Why |
|---|---|
| `ACCESS_WIFI_STATE` | Read SSID/BSSID/auth/cipher of the connected network for [[Activities & Screens#WifiSecurityActivity\|WifiSecurityActivity]]. |
| `ACCESS_FINE_LOCATION` | Required by Android to expose Wi-Fi BSSIDs and scan results. |
| `NEARBY_WIFI_DEVICES` (API 33+) | Replaces `ACCESS_FINE_LOCATION` on Android 13+ for read-only Wi-Fi access. Declared with `usesPermissionFlags="neverForLocation"` so Android knows we don't infer location. |

> No `CHANGE_WIFI_STATE` — V1.4 is **read-only**. Reintroduce only when
> the "disconnect from unsafe network" UX actually lands.

## App audit

| Permission | Why |
|---|---|
| `QUERY_ALL_PACKAGES` | Inventory installed apps for `util/AppScanner.kt`. Lint suppressed because the audit feature legitimately needs it. |
| `PACKAGE_USAGE_STATS` | Data-usage stats for the dashboard (`util/DataUsageHelper.kt`). Protected — user must grant via Special Access settings. |

## SMS

| Permission | Why |
|---|---|
| `RECEIVE_SMS` | `SmsBroadcastReceiver` listens for `SMS_RECEIVED` broadcasts. |

> No `READ_SMS` today. Adding it would unlock the planned **inbox
> bootstrap** flow (V1.4 SMS roadmap §8.2) — reintroduce when that
> lands. Restricted permissions are individually reviewed at Play
> submission.

## Background

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE` | Required to start `ScanMonitorService`. |
| `FOREGROUND_SERVICE_DATA_SYNC` | Subtype for `TestDataUsageService`. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Subtype for `ScanMonitorService` (security monitoring; subtype declared via the manifest `<property>` element). |
| `POST_NOTIFICATIONS` | Show alerts and ongoing-scan notifications (Android 13+ runtime grant). |
| `RECEIVE_BOOT_COMPLETED` | `BootReceiver` re-arms the monitoring service after reboot. |
| `WAKE_LOCK` | Keep CPU alive during background scans. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Battery-optimisation exemption prompt — surfaced from settings, not auto-granted. |

## Privacy posture

Per the [[Architecture#Persistence boundaries]] section: scan results,
SMS verdicts, breach state, and monitored apps **never leave the
device**. SMS bodies are forwarded only to the user's own configured
sidecar.

The application uses
`android:networkSecurityConfig="@xml/network_security_config"` to scope
allowed cleartext traffic — see `source/app/src/main/res/xml/`.

`android:allowBackup="true"` is **on** with `data_extraction_rules` and
`backup_rules` constraining what's backed up.
