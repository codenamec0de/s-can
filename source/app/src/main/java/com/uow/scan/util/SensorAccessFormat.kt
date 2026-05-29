package com.uow.scan.util

import com.uow.scan.data.entity.PermissionAccessEntity
import java.util.Calendar
import java.util.Locale

/**
 * One source of truth for how an observed sensor-access event reads to the user, so App Info,
 * Home "Needs attention", and the Alerts timeline all phrase the same real event identically.
 *
 * These are OS-confirmed accesses captured by [OpAccessTracker] (camera/mic) and the privacy
 * NotificationListener (camera/mic/location). [PermissionAccessEntity.foregroundAtStart] tells
 * us whether the app was on screen at the time — i.e. a normal "while in use" access vs. a
 * background one (the privacy concern).
 */
object SensorAccessFormat {

    fun device(op: String): String = when (op.uppercase()) {
        "CAMERA" -> "Camera"
        "MICROPHONE", "MIC" -> "Microphone"
        "LOCATION" -> "Location"
        else -> op.lowercase().replaceFirstChar { it.uppercase() }
    }

    /** "Camera accessed in the background" / "Microphone accessed (while in use)". */
    fun title(acc: PermissionAccessEntity): String {
        val d = device(acc.op)
        return if (acc.foregroundAtStart) "$d accessed (while in use)"
        else "$d accessed in the background"
    }

    /** "active 2m 14s · 2 hr ago", or "ongoing · just now" while still open. */
    fun detail(acc: PermissionAccessEntity, now: Long = System.currentTimeMillis()): String {
        val ended = acc.endedAt
        val durationPart =
            if (ended == null) "ongoing"
            else "active ${activeDuration((ended - acc.startedAt).coerceAtLeast(0))}"
        return "$durationPart · ${relative(acc.startedAt, now)}"
    }

    /** Compact one-liner for the Alerts "Why active" line: "Camera in background 2m14s at 10:01". */
    fun inlineLabel(acc: PermissionAccessEntity): String {
        val where = if (acc.foregroundAtStart) "in use" else "background"
        val ended = acc.endedAt
        val dur = if (ended == null) "ongoing"
        else activeDuration((ended - acc.startedAt).coerceAtLeast(0))
        return "${device(acc.op)} $where $dur at ${clock(acc.startedAt)}"
    }

    fun activeDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    fun relative(ts: Long, now: Long = System.currentTimeMillis()): String {
        val delta = now - ts
        val min = delta / 60_000
        val hr = min / 60
        val days = hr / 24
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            hr < 24 -> "$hr hr ago"
            else -> "$days d ago"
        }
    }

    fun clock(ts: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        return String.format(
            Locale.getDefault(), "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
        )
    }
}
