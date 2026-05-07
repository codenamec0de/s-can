package com.uow.scan.util

import android.content.Context
import android.content.SharedPreferences
import com.uow.scan.data.ScanDatabase
import com.uow.scan.data.entity.AppSettingsEntity
import kotlinx.coroutines.runBlocking

/**
 * Manages app preferences.
 * Onboarding/permissions flags stay in SharedPreferences (needed before DB is ready at startup).
 * Scan-related data uses Room via AppSettingsDao for consistency with the rest of the database.
 */
object PreferencesManager {

    private const val PREFS_NAME = "scan_prefs"
    private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    private const val KEY_PERMISSIONS_GRANTED = "permissions_granted"
    private const val KEY_LAST_SCAN_TIME = "last_scan_time"
    private const val KEY_SMS_DETECTION_ENABLED = "sms_detection_enabled"
    private const val KEY_SMS_SERVER_URL = "sms_server_url"
    private const val KEY_SMS_FALLBACK_ENABLED = "sms_fallback_enabled"
    private const val KEY_SMS_DISCLOSURE_ACCEPTED = "sms_disclosure_accepted"

    /** Public AI sidecar — Cloudflare Tunnel → FastAPI shim. Used when the user's saved URL is blank. */
    const val DEFAULT_SMS_SERVER_URL = "https://scan-api.scan-ai.xyz/"
    private const val KEY_TOOL_WIFI_ENABLED = "tool_wifi_enabled"
    private const val KEY_TOOL_TERMINATOR_ENABLED = "tool_terminator_enabled"
    private const val KEY_TOOL_VERDICT_ENABLED = "tool_verdict_enabled"
    private const val KEY_TOOL_BREACH_ENABLED = "tool_breach_enabled"
    private const val KEY_BREACH_ADDRESSES = "breach_monitored_addresses"
    private const val KEY_BREACH_SELECTED = "breach_selected_address"
    const val BREACH_ADDRESS_LIMIT = 5

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // -------------------------------------------------------------------------
    // SharedPreferences (startup flags - must be fast and synchronous)
    // -------------------------------------------------------------------------

    fun isOnboardingComplete(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ONBOARDING_COMPLETE, false)
    }

    fun setOnboardingComplete(context: Context, complete: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ONBOARDING_COMPLETE, complete).apply()
    }

    fun arePermissionsGranted(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PERMISSIONS_GRANTED, false)
    }

    fun setPermissionsGranted(context: Context, granted: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PERMISSIONS_GRANTED, granted).apply()
    }

    // -------------------------------------------------------------------------
    // Room-backed settings
    // -------------------------------------------------------------------------

    fun getLastScanTime(context: Context): Long = runBlocking {
        ScanDatabase.getInstance(context).appSettingsDao()
            .get(KEY_LAST_SCAN_TIME)?.toLongOrNull() ?: 0L
    }

    fun setLastScanTime(context: Context, time: Long) = runBlocking {
        ScanDatabase.getInstance(context).appSettingsDao()
            .set(AppSettingsEntity(KEY_LAST_SCAN_TIME, time.toString()))
    }

    // -------------------------------------------------------------------------
    // SMS Scam Detection settings (SharedPreferences - fast access)
    // -------------------------------------------------------------------------

    fun isSmsScamDetectionEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SMS_DETECTION_ENABLED, false)
    }

    fun setSmsScamDetectionEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SMS_DETECTION_ENABLED, enabled).apply()
    }

    /**
     * Returns the saved server URL, or [DEFAULT_SMS_SERVER_URL] if the user hasn't set one.
     * Means the SMS pipeline works out-of-the-box without anyone touching the AI Server screen.
     */
    fun getSmsServerUrl(context: Context): String {
        val saved = getPrefs(context).getString(KEY_SMS_SERVER_URL, "").orEmpty()
        return if (saved.isBlank()) DEFAULT_SMS_SERVER_URL else saved
    }

    fun setSmsServerUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_SMS_SERVER_URL, url).apply()
    }

    /**
     * Cached on-device fallback. When true, [com.uow.scan.worker.SmsForwardWorker] bypasses
     * the network entirely and classifies via [com.uow.scan.api.ScanAiFallback].
     */
    fun isSmsFallbackEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_SMS_FALLBACK_ENABLED, false)

    fun setSmsFallbackEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SMS_FALLBACK_ENABLED, enabled).apply()
    }

    /**
     * Whether the user has accepted the SMS-detection privacy disclosure. Used to gate
     * the disclosure dialog so it shows once on first enable, then never again unless
     * preferences are cleared (logout).
     */
    fun hasAcceptedSmsDisclosure(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SMS_DISCLOSURE_ACCEPTED, false)
    }

    fun setSmsDisclosureAccepted(context: Context, accepted: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SMS_DISCLOSURE_ACCEPTED, accepted).apply()
    }

    // Home V4 tool toggles - default ON for first-time users
    fun isWifiToolEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_TOOL_WIFI_ENABLED, true)

    fun setWifiToolEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_TOOL_WIFI_ENABLED, enabled).apply()
    }

    fun isTerminatorToolEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_TOOL_TERMINATOR_ENABLED, true)

    fun setTerminatorToolEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_TOOL_TERMINATOR_ENABLED, enabled).apply()
    }

    fun isVerdictToolEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_TOOL_VERDICT_ENABLED, true)

    fun setVerdictToolEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_TOOL_VERDICT_ENABLED, enabled).apply()
    }

    fun isBreachToolEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_TOOL_BREACH_ENABLED, true)

    fun setBreachToolEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_TOOL_BREACH_ENABLED, enabled).apply()
    }

    fun getBreachAddresses(context: Context): List<String> {
        val raw = getPrefs(context).getString(KEY_BREACH_ADDRESSES, null) ?: return emptyList()
        return raw.split('').filter { it.isNotBlank() }
    }

    fun setBreachAddresses(context: Context, addresses: List<String>) {
        getPrefs(context).edit()
            .putString(KEY_BREACH_ADDRESSES, addresses.joinToString(""))
            .apply()
    }

    fun addBreachAddress(context: Context, email: String): Boolean {
        val current = getBreachAddresses(context).toMutableList()
        if (current.size >= BREACH_ADDRESS_LIMIT) return false
        if (current.any { it.equals(email, ignoreCase = true) }) return false
        current.add(email)
        setBreachAddresses(context, current)
        return true
    }

    fun removeBreachAddress(context: Context, email: String) {
        val current = getBreachAddresses(context)
            .filterNot { it.equals(email, ignoreCase = true) }
        setBreachAddresses(context, current)
        if (getSelectedBreachAddress(context).equals(email, ignoreCase = true)) {
            setSelectedBreachAddress(context, current.firstOrNull() ?: "")
        }
    }

    fun getSelectedBreachAddress(context: Context): String =
        getPrefs(context).getString(KEY_BREACH_SELECTED, null).orEmpty()

    fun setSelectedBreachAddress(context: Context, email: String) {
        getPrefs(context).edit().putString(KEY_BREACH_SELECTED, email).apply()
    }

    // -------------------------------------------------------------------------
    // V4 Notifications
    // -------------------------------------------------------------------------

    private const val KEY_NOTIF_FINDINGS = "notif_findings"
    private const val KEY_NOTIF_SMS = "notif_sms"
    private const val KEY_NOTIF_SCANS = "notif_scans"
    private const val KEY_NOTIF_WEEKLY = "notif_weekly"
    private const val KEY_NOTIF_SCORE_DROP = "notif_score_drop"
    private const val KEY_NOTIF_SOUND = "notif_sound"
    private const val KEY_NOTIF_VIBRATE = "notif_vibrate"
    private const val KEY_NOTIF_LOCKSCREEN = "notif_lockscreen"
    private const val KEY_QUIET_ENABLED = "notif_quiet_enabled"
    private const val KEY_QUIET_START = "notif_quiet_start"
    private const val KEY_QUIET_END = "notif_quiet_end"

    data class NotificationPrefs(
        val findings: Boolean, val sms: Boolean, val scans: Boolean,
        val weekly: Boolean, val scoreDrop: Boolean,
        val sound: Boolean, val vibrate: Boolean, val lockScreen: Boolean,
        val quietEnabled: Boolean, val quietStart: String, val quietEnd: String,
    )

    fun getNotificationPrefs(context: Context): NotificationPrefs {
        val p = getPrefs(context)
        return NotificationPrefs(
            findings = p.getBoolean(KEY_NOTIF_FINDINGS, true),
            sms = p.getBoolean(KEY_NOTIF_SMS, true),
            scans = p.getBoolean(KEY_NOTIF_SCANS, false),
            weekly = p.getBoolean(KEY_NOTIF_WEEKLY, true),
            scoreDrop = p.getBoolean(KEY_NOTIF_SCORE_DROP, true),
            sound = p.getBoolean(KEY_NOTIF_SOUND, true),
            vibrate = p.getBoolean(KEY_NOTIF_VIBRATE, true),
            lockScreen = p.getBoolean(KEY_NOTIF_LOCKSCREEN, false),
            quietEnabled = p.getBoolean(KEY_QUIET_ENABLED, true),
            quietStart = p.getString(KEY_QUIET_START, "22:00") ?: "22:00",
            quietEnd = p.getString(KEY_QUIET_END, "07:00") ?: "07:00",
        )
    }

    fun setNotificationFlag(context: Context, key: String, value: Boolean) {
        getPrefs(context).edit().putBoolean(key, value).apply()
    }

    fun setQuietHours(context: Context, enabled: Boolean, start: String, end: String) {
        getPrefs(context).edit()
            .putBoolean(KEY_QUIET_ENABLED, enabled)
            .putString(KEY_QUIET_START, start)
            .putString(KEY_QUIET_END, end)
            .apply()
    }

    object NotifKeys {
        const val FINDINGS = KEY_NOTIF_FINDINGS
        const val SMS = KEY_NOTIF_SMS
        const val SCANS = KEY_NOTIF_SCANS
        const val WEEKLY = KEY_NOTIF_WEEKLY
        const val SCORE_DROP = KEY_NOTIF_SCORE_DROP
        const val SOUND = KEY_NOTIF_SOUND
        const val VIBRATE = KEY_NOTIF_VIBRATE
        const val LOCK_SCREEN = KEY_NOTIF_LOCKSCREEN
    }

    // -------------------------------------------------------------------------
    // V4 Scan schedule
    // -------------------------------------------------------------------------

    private const val KEY_SCAN_FREQ = "scan_freq"
    private const val KEY_SCAN_TIME = "scan_time"
    private const val KEY_SCAN_WIFI_ONLY = "scan_wifi_only"
    private const val KEY_SCAN_CHARGING = "scan_charging"
    private const val KEY_SCAN_DEEP = "scan_deep"

    data class ScanSchedulePrefs(
        val frequency: String, val time: String,
        val wifiOnly: Boolean, val charging: Boolean, val deepScan: Boolean,
    )

    fun getScanSchedulePrefs(context: Context): ScanSchedulePrefs {
        val p = getPrefs(context)
        return ScanSchedulePrefs(
            frequency = p.getString(KEY_SCAN_FREQ, "daily") ?: "daily",
            time = p.getString(KEY_SCAN_TIME, "03:00") ?: "03:00",
            wifiOnly = p.getBoolean(KEY_SCAN_WIFI_ONLY, true),
            charging = p.getBoolean(KEY_SCAN_CHARGING, false),
            deepScan = p.getBoolean(KEY_SCAN_DEEP, true),
        )
    }

    fun setScanFrequency(context: Context, frequency: String) {
        getPrefs(context).edit().putString(KEY_SCAN_FREQ, frequency).apply()
    }

    fun setScanTime(context: Context, time: String) {
        getPrefs(context).edit().putString(KEY_SCAN_TIME, time).apply()
    }

    fun setScanCondition(context: Context, key: String, value: Boolean) {
        getPrefs(context).edit().putBoolean(key, value).apply()
    }

    object ScanKeys {
        const val WIFI_ONLY = KEY_SCAN_WIFI_ONLY
        const val CHARGING = KEY_SCAN_CHARGING
        const val DEEP = KEY_SCAN_DEEP
    }

    // -------------------------------------------------------------------------
    // V4 Data &amp; Storage retention
    // -------------------------------------------------------------------------

    private const val KEY_KEEP_HISTORY_DAYS = "keep_history_days"
    private const val KEY_KEEP_VERDICTS_DAYS = "keep_verdicts_days"
    private const val KEY_AUTO_PURGE_CACHE = "auto_purge_cache"

    fun getKeepHistoryDays(context: Context): Int =
        getPrefs(context).getInt(KEY_KEEP_HISTORY_DAYS, 90)

    fun getKeepVerdictsDays(context: Context): Int =
        getPrefs(context).getInt(KEY_KEEP_VERDICTS_DAYS, 30)

    fun getAutoPurgeCache(context: Context): String =
        getPrefs(context).getString(KEY_AUTO_PURGE_CACHE, "Weekly") ?: "Weekly"

    fun setKeepHistoryDays(context: Context, days: Int) {
        getPrefs(context).edit().putInt(KEY_KEEP_HISTORY_DAYS, days).apply()
    }

    fun setKeepVerdictsDays(context: Context, days: Int) {
        getPrefs(context).edit().putInt(KEY_KEEP_VERDICTS_DAYS, days).apply()
    }

    fun setAutoPurgeCache(context: Context, value: String) {
        getPrefs(context).edit().putString(KEY_AUTO_PURGE_CACHE, value).apply()
    }

    // -------------------------------------------------------------------------
    // Google profile picture (captured at sign-in; FirebaseUser.photoUrl is
    // sometimes null even when the Google account has a photo, so we mirror
    // the URL here when GoogleSignInAccount surfaces one).
    // -------------------------------------------------------------------------

    private const val KEY_GOOGLE_PHOTO_URL = "google_photo_url"

    fun getGoogleProfilePhotoUrl(context: Context): String? =
        getPrefs(context).getString(KEY_GOOGLE_PHOTO_URL, null)

    fun setGoogleProfilePhotoUrl(context: Context, url: String?) {
        getPrefs(context).edit().putString(KEY_GOOGLE_PHOTO_URL, url).apply()
    }

    fun clearAll(context: Context) {
        getPrefs(context).edit().clear().apply()
        runBlocking {
            ScanDatabase.getInstance(context).appSettingsDao().clearAll()
        }
    }
}
