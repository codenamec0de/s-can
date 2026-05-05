package com.uow.scan.util

import android.content.Context
import com.uow.scan.data.ScanDatabase
import com.uow.scan.data.entity.AlertEntity
import com.uow.scan.data.entity.WeeklyStatsEntity
import java.util.Calendar

/**
 * Snapshots Alerts page metrics into the `weekly_stats` table once per week.
 *
 * The Alerts page metrics ("Today / Critical / Patterns") are scoped to the
 * current ISO week — so they visually reset every Monday at 00:00. The values
 * for completed weeks are preserved here and surfaced in the PDF report's
 * Weekly Activity History section.
 */
object WeeklyStatsRecorder {

    /** 1 MB — same threshold the Alerts UI uses to flag an alert as "critical". */
    private const val CRITICAL_BYTES = 1024L * 1024L

    /**
     * Insert one snapshot row for every completed week between the latest
     * snapshot and the current (still-in-progress) week. No-op if up to date.
     */
    suspend fun snapshotIfNeeded(context: Context) {
        val db = ScanDatabase.getInstance(context)
        val dao = db.weeklyStatsDao()
        val latestSnapshotWeek = dao.getLatestWeekStart()

        val currentWeekStart = startOfWeek(System.currentTimeMillis())
        val firstWeekToConsider = if (latestSnapshotWeek != null) {
            advanceOneWeek(latestSnapshotWeek)
        } else {
            // No history yet — find the earliest alert and start from its week.
            val earliest = db.alertDao().getAll().minByOrNull { it.timestamp }
                ?: return
            startOfWeek(earliest.timestamp)
        }

        if (firstWeekToConsider >= currentWeekStart) return

        val allAlerts = db.alertDao().getAll()
        var cursor = firstWeekToConsider
        while (cursor < currentWeekStart) {
            val end = endOfWeek(cursor)
            val weekAlerts = allAlerts.filter { it.timestamp in cursor..end }
            dao.insert(buildSnapshot(cursor, end, weekAlerts))
            cursor = advanceOneWeek(cursor)
        }
    }

    private fun buildSnapshot(
        weekStart: Long,
        weekEnd: Long,
        alerts: List<AlertEntity>
    ): WeeklyStatsEntity {
        val critical = alerts.count { it.dataUsedBytes >= CRITICAL_BYTES }
        // Pattern definition mirrored from AlertsFragment: a (package, primary perm) pair
        // with ≥2 occurrences inside the bucket counts as one pattern.
        val patternCount = alerts
            .groupBy { "${it.packageName}|${primaryPermissionToken(it.permissions)}" }
            .count { it.value.size >= 2 }
        return WeeklyStatsEntity(
            weekStartMs = weekStart,
            weekEndMs = weekEnd,
            totalAlerts = alerts.size,
            criticalAlerts = critical,
            patternCount = patternCount,
            generatedAt = System.currentTimeMillis()
        )
    }

    private fun primaryPermissionToken(comma: String): String =
        comma.split(',').firstOrNull()?.trim().orEmpty()

    /** Local-time millis for Monday 00:00:00.000 of the week containing [ts]. */
    fun startOfWeek(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        firstDayOfWeek = Calendar.MONDAY
        // Move to Monday of this week.
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        // If the original day was Sunday, the line above moves us to NEXT Monday;
        // step back one week in that case.
        if (timeInMillis > ts) add(Calendar.DAY_OF_YEAR, -7)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Local-time millis for Sunday 23:59:59.999 of the week containing [ts]. */
    fun endOfWeek(ts: Long): Long = advanceOneWeek(startOfWeek(ts)) - 1L

    private fun advanceOneWeek(weekStartMs: Long): Long = Calendar.getInstance().apply {
        timeInMillis = weekStartMs
        add(Calendar.DAY_OF_YEAR, 7)
    }.timeInMillis
}
