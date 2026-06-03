package com.uow.scan.util

import android.graphics.Color
import com.uow.scan.R

/**
 * Terminator (Privacy Enforcer) demo data — mirrors the designer's `terminator-data.jsx`.
 * Frontend-only: this drives the demo screen, no backend enforcement yet.
 *
 * Compliance (design brief §0): S'CAN never kills or force-stops apps. The only real powers are
 *  (a) cut an app's network through S'CAN's own VPN tunnel, and
 *  (b) DETECT sensor abuse and route the user to Settings (or Shizuku) to revoke.
 * Every label and state here reflects only those real powers. No em dashes in copy.
 */
object TerminatorDemoData {

    fun mb(n: Double): Long = (n * 1048576L).toLong()

    fun fmtBytes(bytes: Long): String = when {
        bytes >= 1073741824L -> trimZero(bytes / 1073741824.0) + " GB"
        bytes >= 1048576L -> trimZero(bytes / 1048576.0) + " MB"
        bytes >= 1024L -> (bytes / 1024L).toString() + " KB"
        else -> "$bytes B"
    }

    private fun trimZero(v: Double): String {
        val s = String.format("%.1f", v)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    data class Sensor(val s: String, val label: String, val detail: String, val background: Boolean, var perm: String = "granted")
    data class Event(val s: String, val t: String, val note: String, val bg: Boolean, val bytes: Long = 0L)

    data class TermApp(
        val id: String,
        val name: String,
        val mono: String,
        val brand: Int,
        val pkg: String,
        val sensors: List<Sensor>,
        val events: List<Event>,
        val bgBytes: Long,
        val fgBytes: Long,
        val trackerCalls: Int,
        val trackers: List<String>,
        var net: String,
        val primary: String,
        val overreach: Boolean = false,
    )

    /** The four network states, cycled in this order. The ONLY thing S'CAN does to another app. */
    val NET_ORDER = listOf("allowed", "bg", "all", "trackers")
    fun nextNet(s: String): String = NET_ORDER[(NET_ORDER.indexOf(s).coerceAtLeast(0) + 1) % NET_ORDER.size]

    data class NetMeta(val label: String, val short: String, val iconRes: Int, val colorRes: Int, val enforced: Boolean)
    fun netMeta(s: String): NetMeta = when (s) {
        "bg" -> NetMeta("Block in background", "BG blocked", R.drawable.ic_glyph_background, R.color.v4_warn, true)
        "all" -> NetMeta("Block all", "Locked", R.drawable.ic_glyph_block, R.color.v4_bad, true)
        "trackers" -> NetMeta("Trackers only", "Trackers cut", R.drawable.ic_glyph_trackers, R.color.v4_accent, true)
        else -> NetMeta("Allowed", "Allowed", R.drawable.ic_glyph_check, R.color.v4_fg2, false)
    }

    data class Pill(val label: String, val colorRes: Int)
    fun cardPill(net: String): Pill = when (net) {
        "all" -> Pill("Locked", R.color.v4_bad)
        "bg" -> Pill("Background blocked", R.color.v4_warn)
        "trackers" -> Pill("Trackers blocked", R.color.v4_accent)
        else -> Pill("Watched", R.color.v4_fg2)
    }

    data class SensorMeta(val iconRes: Int, val label: String, val colorRes: Int)
    fun sensorMeta(s: String): SensorMeta = when (s) {
        "camera" -> SensorMeta(R.drawable.ic_v4_glyph_camera, "Camera", R.color.v4_bad)
        "mic" -> SensorMeta(R.drawable.ic_v4_glyph_mic, "Mic", R.color.v4_warn)
        "location" -> SensorMeta(R.drawable.ic_v4_glyph_pin, "Location", R.color.v4_accent)
        else -> SensorMeta(R.drawable.ic_glyph_data, "Data", R.color.v4_fg2)
    }

    data class Tracker(val name: String, val cat: String)
    val TRACKERS = mapOf(
        "google" to Tracker("Google", "Analytics"),
        "facebook" to Tracker("Meta", "Advertising"),
        "inmobi" to Tracker("InMobi", "Advertising"),
        "amazon" to Tracker("Amazon Ads", "Advertising"),
    )

    data class Rule(val key: String, val iconRes: Int, val label: String, val sub: String)
    val RULES = listOf(
        Rule("mobile", R.drawable.ic_glyph_wifi, "Lock down on mobile data", "Block background data for watched apps whenever you leave Wi-Fi."),
        Rule("night", R.drawable.ic_glyph_moon, "Lock down 11pm to 7am", "Cut background data overnight, when you are not using your phone."),
        Rule("battery", R.drawable.ic_glyph_battery, "Lock down under 20 percent", "Save battery by cutting background data when low."),
    )
    val RULES_DEFAULT = mutableMapOf("mobile" to true, "night" to false, "battery" to true)

    /** The offender set (design brief §5). */
    fun apps(): List<TermApp> = listOf(
        TermApp(
            "weathernow", "WeatherNow", "W", Color.parseColor("#38BDF8"), "com.weathernow.app",
            sensors = listOf(Sensor("location", "Location", "3x today", true)),
            events = listOf(
                Event("location", "7:41am", "Background location", true),
                Event("location", "12:15pm", "Background location", true),
                Event("location", "4:52pm", "Background location", true),
                Event("data", "4:52pm", "Background data spike", true, mb(6.1)),
            ),
            bgBytes = mb(18.0), fgBytes = mb(7.0), trackerCalls = 41, trackers = listOf("google", "inmobi"),
            net = "allowed", primary = "location",
        ),
        TermApp(
            "chatwave", "ChatWave", "C", Color.parseColor("#A78BFA"), "com.chatwave.messenger",
            sensors = listOf(Sensor("mic", "Mic", "2:14pm", true)),
            events = listOf(
                Event("mic", "2:14pm", "Microphone, app in background", true),
                Event("data", "2:14pm", "Background upload", true, mb(4.2)),
            ),
            bgBytes = mb(4.2), fgBytes = mb(22.0), trackerCalls = 12, trackers = listOf("facebook"),
            net = "allowed", primary = "mic",
        ),
        TermApp(
            "shopdeals", "ShopDeals", "S", Color.parseColor("#FB7185"), "com.shopdeals.store",
            sensors = emptyList(),
            events = listOf(
                Event("data", "9:02am", "Background sync", true, mb(11.0)),
                Event("data", "1:30pm", "Tracker burst, 80+ calls", true, mb(9.0)),
                Event("data", "6:11pm", "Background sync", true, mb(6.0)),
            ),
            bgBytes = mb(26.0), fgBytes = mb(14.0), trackerCalls = 312, trackers = listOf("google", "facebook", "inmobi", "amazon"),
            net = "allowed", primary = "trackers",
        ),
        TermApp(
            "fittrack", "FitTrack", "F", Color.parseColor("#34D399"), "com.fittrack.health",
            sensors = listOf(Sensor("location", "Location", "ongoing", true)),
            events = listOf(
                Event("location", "now", "Ongoing background location", true),
                Event("data", "11:20am", "Background sync", true, mb(3.0)),
            ),
            bgBytes = mb(9.0), fgBytes = mb(31.0), trackerCalls = 0, trackers = emptyList(),
            net = "allowed", primary = "location",
        ),
        TermApp(
            "cleansweep", "CleanSweep", "CS", Color.parseColor("#FBBF24"), "com.cleansweep.flashlight",
            sensors = listOf(
                Sensor("camera", "Camera", "caught once", true),
                Sensor("location", "Location", "requested", false),
            ),
            events = listOf(
                Event("camera", "8:30am", "Camera access, app in background", true),
                Event("location", "8:30am", "Location requested at launch", false),
            ),
            bgBytes = mb(1.2), fgBytes = mb(2.0), trackerCalls = 53, trackers = listOf("inmobi", "google"),
            net = "allowed", primary = "camera", overreach = true,
        ),
    )

    const val TRACKER_CALLS_BLOCKED = 312
    val SAVED_BYTES = 1288490188L // ~1.2 GB
}
