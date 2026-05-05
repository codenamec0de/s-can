package com.uow.scan.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages Terminator (auto-revoke) watchlist and settings.
 * Stores watched packages and mode preference in SharedPreferences.
 */
object TerminatorManager {

    private const val PREFS_NAME = "terminator_prefs"
    private const val KEY_ENABLED = "terminator_enabled"
    private const val KEY_MODE = "terminator_mode"  // "manual" or "auto"
    private const val KEY_WATCHED_APPS = "watched_apps" // comma-separated package names

    // Permissions that Terminator can revoke
    val REVOCABLE_PERMISSIONS = listOf(
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_SMS",
        "android.permission.READ_CALENDAR"
    )

    val PERMISSION_LABELS = mapOf(
        "android.permission.CAMERA" to "Camera",
        "android.permission.RECORD_AUDIO" to "Microphone",
        "android.permission.ACCESS_FINE_LOCATION" to "Fine Location",
        "android.permission.ACCESS_COARSE_LOCATION" to "Coarse Location",
        "android.permission.READ_CONTACTS" to "Contacts",
        "android.permission.READ_CALL_LOG" to "Call Log",
        "android.permission.READ_SMS" to "SMS",
        "android.permission.READ_CALENDAR" to "Calendar"
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getMode(context: Context): String {
        return getPrefs(context).getString(KEY_MODE, "manual") ?: "manual"
    }

    fun setMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_MODE, mode).apply()
    }

    fun getWatchedApps(context: Context): Set<String> {
        val raw = getPrefs(context).getString(KEY_WATCHED_APPS, "") ?: ""
        return if (raw.isBlank()) emptySet() else raw.split(",").toSet()
    }

    fun setAppWatched(context: Context, packageName: String, watched: Boolean) {
        val current = getWatchedApps(context).toMutableSet()
        if (watched) current.add(packageName) else current.remove(packageName)
        getPrefs(context).edit()
            .putString(KEY_WATCHED_APPS, current.joinToString(","))
            .apply()

        // Enable terminator if at least one app is watched
        setEnabled(context, current.isNotEmpty())
    }

    fun isAppWatched(context: Context, packageName: String): Boolean {
        return packageName in getWatchedApps(context)
    }

    /**
     * Get the human-readable labels for revocable permissions an app has.
     */
    fun getRevocablePermLabels(permissions: List<String>): List<String> {
        return permissions
            .filter { it in REVOCABLE_PERMISSIONS }
            .mapNotNull { PERMISSION_LABELS[it] }
    }
}
