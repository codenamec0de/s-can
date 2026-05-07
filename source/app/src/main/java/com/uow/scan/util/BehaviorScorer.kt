package com.uow.scan.util

import android.content.Context
import com.uow.scan.data.ScanDatabase
import com.uow.scan.data.entity.AlertEntity
import com.uow.scan.model.PermissionAlert
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Translates raw alerts (bytes + duration + observed sensors) into a plain-language
 * verdict that a non-technical user can act on.
 *
 * Design philosophy:
 *   • A single number ("105 MB used in background") is meaningless without context.
 *     Was that more or less than this app usually does? At an unusual hour?
 *     Accompanied by sensor access we observed?
 *   • Per-app behaviour baselines built from the last 7 days of stored alerts
 *     give us that context cheaply, without an external service or model.
 *   • The verdict is *opinionated*: NORMAL means "this matches what this app
 *     normally does, no need to look closer"; SUSPICIOUS means "we have direct
 *     evidence this is unusual or sensor-related and the user should look".
 *
 * No data leaves the device. The scorer reads only the local Room DB.
 */
object BehaviorScorer {

    enum class Verdict { NORMAL, UNUSUAL, SUSPICIOUS }

    /** Result attached to one rendered alert row. */
    data class Score(
        val verdict: Verdict,
        val headline: String,
        val supporting: String
    )

    /** Per-app rolling stats over the baseline window. */
    private data class Baseline(
        val sampleSize: Int,
        val medianBytes: Long,
        val p90Bytes: Long,
        /** Hour-of-day buckets (4-hour wide) where this app typically transmits. */
        val typicalHourBuckets: Set<Int>,
        /** True when sensor observations are *normal* for this app — i.e. happened
         *  in ≥30 % of past background events. Used to dampen "sensor used" panic
         *  when the app routinely uses that sensor. */
        val sensorIsNormal: Boolean
    )

    private const val BASELINE_WINDOW_DAYS = 7L
    private const val MIN_SAMPLES_FOR_BASELINE = 3

    /**
     * Score every alert in [current]. Pulls the last [BASELINE_WINDOW_DAYS] of
     * alert history from Room once and bands per-package.
     */
    suspend fun scoreAll(
        context: Context,
        current: List<PermissionAlert>
    ): Map<String, Score> {
        if (current.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis()
        val from = now - TimeUnit.DAYS.toMillis(BASELINE_WINDOW_DAYS)

        val historyByPkg: Map<String, List<AlertEntity>> = try {
            ScanDatabase.getInstance(context)
                .alertDao()
                .getAll()
                .filter { it.timestamp in from..now }
                .groupBy { it.packageName }
        } catch (_: Exception) {
            emptyMap()
        }

        // Per-alert baseline computation: exclude only the alert being scored
        // from its own history (otherwise it appears in its own median/p90
        // and self-flattens the spike), but keep every other historical
        // observation in scope.
        return current.associate { alert ->
            val pkgHistory = historyByPkg[alert.packageName].orEmpty()
                .filter { it.id != alert.id }
            val baseline = if (pkgHistory.isEmpty()) null else baselineFrom(pkgHistory)
            alert.id to scoreOne(alert, baseline)
        }
    }

    private fun baselineFrom(history: List<AlertEntity>): Baseline {
        val sample = history.size
        if (sample < MIN_SAMPLES_FOR_BASELINE) {
            return Baseline(
                sampleSize = sample,
                medianBytes = history.firstOrNull()?.dataUsedBytes ?: 0L,
                p90Bytes = history.firstOrNull()?.dataUsedBytes ?: 0L,
                typicalHourBuckets = emptySet(),
                sensorIsNormal = false
            )
        }
        val sortedBytes = history.map { it.dataUsedBytes }.sorted()
        val median = sortedBytes[sortedBytes.size / 2]
        val p90 = sortedBytes[(sortedBytes.size * 0.9).toInt().coerceAtMost(sortedBytes.lastIndex)]

        val cal = Calendar.getInstance()
        val buckets = history.map { entity ->
            cal.timeInMillis = entity.timestamp
            cal.get(Calendar.HOUR_OF_DAY) / 4   // 0-5
        }.toSet()

        val withSensor = history.count { it.permissions.isNotBlank() }
        val sensorRate = withSensor.toFloat() / sample.toFloat()

        return Baseline(
            sampleSize = sample,
            medianBytes = median,
            p90Bytes = p90,
            typicalHourBuckets = buckets,
            sensorIsNormal = sensorRate >= 0.30f
        )
    }

    private fun scoreOne(alert: PermissionAlert, baseline: Baseline?): Score {
        val sensorObserved = alert.permissions.isNotEmpty()
        val cal = Calendar.getInstance().apply { timeInMillis = alert.timestamp }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val bucket = hour / 4
        val isNightHour = hour < 6 || hour >= 23

        // Rule 1: SUSPICIOUS — observed sensor access while the app's normal
        //   behaviour does NOT include sensor use. Direct evidence of something
        //   the user should know about.
        if (sensorObserved && (baseline == null || !baseline.sensorIsNormal)) {
            val sensors = alert.permissions.joinToString(", ") { it.lowercase() }
            return Score(
                verdict = Verdict.SUSPICIOUS,
                headline = "${alert.appName} used your $sensors while you weren't using it",
                supporting = "Verified — happened at ${formatClock(alert.timestamp)}, ${alert.formattedDataUsed} of data."
            )
        }

        // Rule 2: SUSPICIOUS — large data spike at an unusual night-hour.
        //   The baseline's hour buckets ensure we don't false-flag night-owl apps.
        if (baseline != null && baseline.sampleSize >= MIN_SAMPLES_FOR_BASELINE) {
            val ratio = if (baseline.medianBytes > 0)
                alert.dataUsedBytes.toFloat() / baseline.medianBytes.toFloat()
            else 1.0f
            val unfamiliarHour = bucket !in baseline.typicalHourBuckets

            if (isNightHour && unfamiliarHour && ratio >= 4f && alert.dataUsedBytes >= 1024L * 1024L) {
                return Score(
                    verdict = Verdict.SUSPICIOUS,
                    headline = "${alert.appName} sent ${alert.formattedDataUsed} at ${formatClock(alert.timestamp)}",
                    supporting = "${ratio.roundToInt()}× the usual amount, at an hour this app rarely runs."
                )
            }

            // Rule 3: UNUSUAL — same kind of activity but materially more
            //   data than the recent baseline.
            if (alert.dataUsedBytes >= max(baseline.p90Bytes * 2, 5 * 1024L * 1024L) &&
                alert.dataUsedBytes > baseline.medianBytes * 3) {
                return Score(
                    verdict = Verdict.UNUSUAL,
                    headline = "${alert.appName} sent more data than usual",
                    supporting = "${alert.formattedDataUsed} now vs typical ${formatBytes(baseline.medianBytes)} per check — ${ratio.roundToInt()}× higher."
                )
            }

            // Rule 4: UNUSUAL — runs at an hour that doesn't match prior behaviour.
            if (unfamiliarHour && isNightHour && alert.dataUsedBytes > 0) {
                return Score(
                    verdict = Verdict.UNUSUAL,
                    headline = "${alert.appName} active at ${formatClock(alert.timestamp)}",
                    supporting = "This app usually doesn't run at this hour. ${alert.formattedDataUsed} sent."
                )
            }
        }

        // Rule 5: SUSPICIOUS — observed sensor on an app that NORMALLY uses
        //   sensors. Still worth surfacing but as a milder flag.
        if (sensorObserved) {
            val sensors = alert.permissions.joinToString(", ") { it.lowercase() }
            return Score(
                verdict = Verdict.UNUSUAL,
                headline = "${alert.appName} used $sensors in the background",
                supporting = "Verified at ${formatClock(alert.timestamp)} — typical for this app, but still worth knowing."
            )
        }

        // Rule 6: NORMAL fallback. Wording differs slightly when we have no
        //   baseline yet — set expectations honestly.
        val noBaseline = baseline == null || baseline.sampleSize < MIN_SAMPLES_FOR_BASELINE
        return Score(
            verdict = Verdict.NORMAL,
            headline = "${alert.appName} sent data in the background",
            supporting = if (noBaseline) {
                "${alert.formattedDataUsed} — first observation; baseline still building."
            } else {
                "${alert.formattedDataUsed} — matches what this app usually does."
            }
        )
    }

    private fun formatClock(ts: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        return String.format(
            Locale.getDefault(), "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
        )
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
