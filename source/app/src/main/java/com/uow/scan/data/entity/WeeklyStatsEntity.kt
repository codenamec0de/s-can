package com.uow.scan.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Snapshot of the Alerts page metrics for one completed ISO week.
 * Stored once per week so the live counters can reset weekly while history
 * is retained locally for the PDF report.
 */
@Entity(tableName = "weekly_stats")
data class WeeklyStatsEntity(
    /** Local-time millis at the start of the week (Monday 00:00:00.000). */
    @PrimaryKey
    val weekStartMs: Long,
    /** Local-time millis at the end of the week (Sunday 23:59:59.999). */
    val weekEndMs: Long,
    /** Total alerts whose timestamp falls inside [weekStartMs, weekEndMs]. */
    val totalAlerts: Int,
    /** Subset of totalAlerts whose severity was BAD (≥1 MB background data used). */
    val criticalAlerts: Int,
    /** Distinct (app, primary-permission) pairs that recurred ≥2 times in the week. */
    val patternCount: Int,
    /** When the snapshot row was inserted (informational only). */
    val generatedAt: Long
)
