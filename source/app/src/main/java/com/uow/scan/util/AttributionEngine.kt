package com.uow.scan.util

import android.content.Context
import com.uow.scan.data.ScanDatabase
import com.uow.scan.data.entity.PermissionAccessEntity
import com.uow.scan.model.PermissionAlert

/**
 * Produces the most accurate per-alert "why did this app send data in the
 * background" explanation that's possible without the signature-only
 * `WATCH_APPOPS` permission.
 *
 * Three independent OS-attributed signals are combined:
 *
 * 1. **State-bucketed network bytes** — [DataUsageHelper.getAppDataUsageBuckets]
 *    splits the app's traffic into STATE_FOREGROUND vs. STATE_DEFAULT
 *    (background) using the network bucket state field. Lets us say
 *    "2.4 MB sent specifically while app was offscreen", not just "2.5 MB total".
 *
 * 2. **OS-confirmed sensor accesses** — [PermissionAccessEntity] rows written
 *    by the camera/audio availability callbacks AND by the OS-posted privacy
 *    notifications captured in `PrivacyNotificationListener` (which surface
 *    Camera, Microphone AND Location accesses). Lets us list concrete events
 *    with timestamps, e.g. "Location accessed at 10:01:42".
 *
 * 3. **Manifest-declared mechanisms** — [BackgroundReasonInspector]: which
 *    foreground service types the app declared, FCM registration, sync
 *    adapters, JobScheduler, RECEIVE_BOOT_COMPLETED. Establishes *capability*.
 *
 * The "most likely cause" inference cross-references (1)+(2)+(3): if a Camera
 * access was observed AND the app declared a `camera` foreground service, the
 * cause is named with high confidence. If no sensor was observed, we fall
 * back to ranking declared mechanisms by how likely each is to drive
 * background bytes (FG service > sync adapter > FCM > scheduled job > boot).
 *
 * No DB writes — all attribution is computed lazily on render and cached
 * in [AlertsFragment] for the lifetime of one bind pass.
 */
object AttributionEngine {

    /** Snapshot of everything we know about a single alert. */
    data class Attribution(
        /** Bytes sent while app was in STATE_FOREGROUND. -1 = unavailable on device. */
        val foregroundBytes: Long,
        /** Bytes sent while app was in STATE_DEFAULT (background). -1 = unavailable. */
        val backgroundBytes: Long,
        /** Bytes over mobile data. -1 = unavailable. */
        val mobileBytes: Long,
        /** Bytes over Wi-Fi. -1 = unavailable. */
        val wifiBytes: Long,
        /** OS-confirmed sensor accesses overlapping the alert window, sorted by start time. */
        val accesses: List<PermissionAccessEntity>,
        /** Static manifest signals about the app's background capabilities. */
        val reasons: BackgroundReasonInspector.Reasons,
    ) {
        val hasStateBreakdown: Boolean get() = backgroundBytes >= 0 && foregroundBytes >= 0
        val hasNetworkSplit: Boolean get() = mobileBytes >= 0 && wifiBytes >= 0
    }

    /**
     * Builds the [Attribution] for a single alert. Safe to call from any
     * coroutine context; performs DB and NetworkStats reads.
     */
    suspend fun attribute(context: Context, alert: PermissionAlert): Attribution {
        val (windowStart, windowEnd) = windowFor(alert)

        val uid = try {
            context.packageManager.getApplicationInfo(alert.packageName, 0).uid
        } catch (_: Exception) {
            -1
        }

        val state = if (uid >= 0) {
            try {
                DataUsageHelper.getAppDataUsageBuckets(context, uid, windowStart, windowEnd)
            } catch (_: Exception) {
                null
            }
        } else null

        val accesses = try {
            ScanDatabase.getInstance(context).permissionAccessDao()
                .accessesInWindow(alert.packageName, windowStart, windowEnd)
        } catch (_: Exception) {
            emptyList()
        }

        val reasons = try {
            BackgroundReasonInspector.inspect(context, alert.packageName)
        } catch (_: Exception) {
            BackgroundReasonInspector.Reasons(emptyList(), false, false, false, false, false)
        }

        return Attribution(
            foregroundBytes = state?.foregroundBytes ?: -1,
            backgroundBytes = state?.backgroundBytes ?: -1,
            mobileBytes = state?.mobileBytes ?: -1,
            wifiBytes = state?.wifiBytes ?: -1,
            accesses = accesses,
            reasons = reasons,
        )
    }

    /**
     * One-line user-facing explanation. Empty string if we have nothing
     * better than the existing "Why active" capability summary.
     *
     * Order of priority is highest-confidence first:
     *   • Concrete sensor accesses with timestamps (OS-confirmed)
     *   • State-bucketed bg/fg bytes
     *   • Mobile vs Wi-Fi split
     *   • Most-likely declared mechanism
     */
    fun explain(a: Attribution): String {
        val parts = mutableListOf<String>()

        // 1. Concrete sensor accesses — these are *observed* facts, not
        //    inferences. List them first.
        if (a.accesses.isNotEmpty()) {
            parts += formatAccesses(a.accesses)
        }

        // 2. Background vs foreground byte split — true OS attribution.
        if (a.hasStateBreakdown && (a.backgroundBytes > 0 || a.foregroundBytes > 0)) {
            val bgFmt = DataUsageHelper.formatBytes(a.backgroundBytes)
            val fgFmt = DataUsageHelper.formatBytes(a.foregroundBytes)
            parts += when {
                a.foregroundBytes == 0L -> "$bgFmt sent fully while backgrounded"
                a.backgroundBytes == 0L -> "$fgFmt sent only while foreground"
                else -> "$bgFmt while backgrounded, $fgFmt foreground"
            }
        }

        // 3. Mobile vs Wi-Fi split — both > 0 means we have meaningful info to share.
        if (a.hasNetworkSplit && a.mobileBytes > 0 && a.wifiBytes > 0) {
            parts += "${DataUsageHelper.formatBytes(a.mobileBytes)} mobile · " +
                DataUsageHelper.formatBytes(a.wifiBytes) + " Wi-Fi"
        } else if (a.hasNetworkSplit && a.mobileBytes > 0) {
            parts += "all over mobile data"
        }

        // 4. Most likely mechanism — the inference layer.
        mostLikelyMechanism(a)?.let { parts += "Likely: $it" }

        return parts.joinToString(" · ")
    }

    // ─── internals ──────────────────────────────────────────────────────────

    /**
     * Window the alert refers to. For alerts with a known background-duration
     * value we use [timestamp - duration, timestamp]. For "silent background"
     * alerts (duration == 0, i.e. detected purely via NetworkStats with no
     * Activity transitions) we use a 2-hour lookback that matches
     * `BackgroundUsageMonitor.DATA_LOOKBACK_MS`.
     */
    private fun windowFor(alert: PermissionAlert): Pair<Long, Long> {
        val end = alert.timestamp
        val start = if (alert.backgroundDurationMs > 0) {
            end - alert.backgroundDurationMs
        } else {
            end - 2 * 60 * 60 * 1000L
        }
        return start to end
    }

    private fun formatAccesses(accesses: List<PermissionAccessEntity>): String {
        // Each access reads consistently with App Info / Home via the shared formatter:
        // "Camera in background 2m14s at 10:01". Cap at 3 so the line stays scannable.
        val sorted = accesses.sortedBy { it.startedAt }
        val shown = sorted.take(3).joinToString(" · ") { SensorAccessFormat.inlineLabel(it) }
        return if (sorted.size > 3) "$shown · +${sorted.size - 3} more" else shown
    }

    /**
     * Cross-references observed accesses with declared foreground service
     * types — when both line up we can name the cause with high confidence.
     * Otherwise we rank declared mechanisms by how plausibly each drives
     * background bytes.
     */
    private fun mostLikelyMechanism(a: Attribution): String? {
        val r = a.reasons
        val ops = a.accesses.map { it.op.uppercase() }.toSet()

        // High-confidence pairings: observed sensor matches a declared FG service type.
        if ("CAMERA" in ops && "camera" in r.foregroundServiceTypes)
            return "camera foreground service"
        if ("MICROPHONE" in ops && "microphone" in r.foregroundServiceTypes)
            return "microphone foreground service"
        if ("LOCATION" in ops && "location" in r.foregroundServiceTypes)
            return "location foreground service"
        if ("LOCATION" in ops && r.backgroundLocation)
            return "background location"

        // No matching observation — rank declared mechanisms by likelihood
        // of explaining unsolicited background bytes.
        if ("dataSync" in r.foregroundServiceTypes) return "data-sync foreground service"
        if (r.hasSyncAdapter) return "account sync"
        if (r.pushMessaging) return "push notification (FCM)"
        if ("mediaPlayback" in r.foregroundServiceTypes) return "media playback service"
        if ("location" in r.foregroundServiceTypes) return "location foreground service"
        if (r.schedulesJobs) return "scheduled job"
        if (r.autoStartOnBoot) return "boot-time auto-start"
        return null
    }
}
