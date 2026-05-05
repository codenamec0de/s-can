---
tags: [scan, data, room, persistence]
type: reference
created: 2026-05-06
---

# Data Layer — Room

Local persistence is **Room** ([[Dependencies - Android#Persistence]]),
KSP-generated. Database class is `data/ScanDatabase.kt`. All persistence
is on-device — see [[Architecture#Persistence boundaries]].

## Entities

| Entity | File | What it stores |
|---|---|---|
| `ScanResultEntity` | `data/entity/ScanResultEntity.kt` | One row per completed device scan: timestamp, score, app counts, tracker counts. |
| `MonitoredAppEntity` | `data/entity/MonitoredAppEntity.kt` | Apps the user has flagged for monitoring (alerts on permission change). |
| `BreachResultEntity` | `data/entity/BreachResultEntity.kt` | Per-email HIBP breach record: breach name, data classes exposed, resolved/unresolved state. |
| `SmsVerdictEntity` | `data/entity/SmsVerdictEntity.kt` | Classifier output for an inbound SMS: sender, snippet, verdict (`SCAM`/`SUSPICIOUS`/`SAFE`), URLs, brand flags. |
| `AlertEntity` | `data/entity/AlertEntity.kt` | Generic alert row surfaced in the dashboard (permission change, new tracker, etc.). |
| `AppSettingsEntity` | `data/entity/AppSettingsEntity.kt` | Per-app preference state. |
| `DeviceCheckEntity` | `data/entity/DeviceCheckEntity.kt` | Snapshot of the result of `util/DeviceSecurityChecker.kt` (root, dev options, etc.). |
| `WeeklyStatsEntity` | `data/entity/WeeklyStatsEntity.kt` | Pre-aggregated weekly metrics for the dashboard charts. |

## DAOs

Each entity has a paired DAO in `data/dao/`:

| DAO | File | Notes |
|---|---|---|
| `ScanResultDao` | `data/dao/ScanResultDao.kt` | Insert + history queries; consumed by `ScoreHistoryActivity`, `ReportHistoryActivity`. |
| `MonitoredAppDao` | `data/dao/MonitoredAppDao.kt` | List + add/remove monitored apps. |
| `BreachResultDao` | `data/dao/BreachResultDao.kt` | Insert + per-email queries; resolution-state updates. |
| `SmsVerdictDao` | `data/dao/SmsVerdictDao.kt` | Insert from `SmsForwardWorker`; observed by `SmsScamActivity`. |
| `AlertDao` | `data/dao/AlertDao.kt` | Insert + dismiss; powers the alerts list. |
| `AppSettingsDao` | `data/dao/AppSettingsDao.kt` | Per-app settings get/put. |
| `DeviceCheckDao` | `data/dao/DeviceCheckDao.kt` | Latest device-check snapshot. |
| `WeeklyStatsDao` | `data/dao/WeeklyStatsDao.kt` | Insert weekly aggregates; query for charts. |

## Domain models (non-persisted)

`com.uow.scan.model` holds plain DTO/UI types that don't need Room
persistence:

| Class | File | Purpose |
|---|---|---|
| `AppInfo` | `model/AppInfo.kt` | Inventoried app — package, label, icon, installer source. |
| `PermissionAlert` | `model/PermissionAlert.kt` | UI alert payload. |
| `PermissionGroup` | `model/PermissionGroup.kt` | Permission group descriptor for the audit table. |
| `SmsVerdict` | `model/SmsVerdict.kt` | In-memory shape of the sidecar's response before it lands in Room. |
| `TrackerInfo` | `model/TrackerInfo.kt` | Exodus tracker metadata. |

## Migrations

KSP generates the schema for Room 2.6.1. No explicit migrations are
checked in for V1.4.2 — the schema has not changed since V1.4.1.

## Privacy guard rails

- Scan results, breach state, monitored apps, SMS verdicts: **device
  only**, never uploaded.
- Encryption-at-rest is **not** enabled today (no SQLCipher); roadmap
  item if shipped to non-trusted devices.
- `data_extraction_rules` and `backup_rules` constrain what Android
  Auto Backup includes — see [[Permissions#Privacy posture]].
