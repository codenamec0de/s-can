package com.uow.scan

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import kotlin.system.exitProcess

/**
 * Application entry point.
 *
 * Two responsibilities, both about presenting a finished, stable product:
 *  1. Force the dark theme. S'CAN has a dark-by-default visual identity and several screens
 *     hardcode light-on-dark text (e.g. the scan screen's large percentage). Pinning night
 *     mode guarantees those render correctly regardless of the device's system theme.
 *  2. Install a last-resort crash handler. Instead of the system "S'CAN keeps stopping"
 *     dialog mid-presentation, an uncaught exception is logged and the app relaunches itself
 *     from the launcher screen. A short loop-guard avoids spinning on an immediate crash.
 */
class ScanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread '${thread.name}'", throwable)

            val now = System.currentTimeMillis()
            val prefs = getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
            val lastCrash = prefs.getLong(KEY_LAST_CRASH, 0L)
            prefs.edit().putLong(KEY_LAST_CRASH, now).apply()
            val crashLoop = now - lastCrash < RESTART_LOOP_GUARD_MS

            if (crashLoop) {
                // Two crashes in quick succession: stop relaunching so we don't spin on stage.
                Log.e(TAG, "Crash loop detected; deferring to platform handler")
                previous?.uncaughtException(thread, throwable) ?: hardExit()
                return@setDefaultUncaughtExceptionHandler
            }

            try {
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    val pending = PendingIntent.getActivity(
                        this, RESTART_REQUEST_CODE, launch,
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    am.set(AlarmManager.RTC, now + RESTART_DELAY_MS, pending)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to schedule restart", t)
            }
            hardExit()
        }
    }

    private fun hardExit() {
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(10)
    }

    companion object {
        private const val TAG = "ScanApp"
        private const val CRASH_PREFS = "scan_crash_guard"
        private const val KEY_LAST_CRASH = "last_crash_ts"
        private const val RESTART_LOOP_GUARD_MS = 4000L
        private const val RESTART_DELAY_MS = 300L
        private const val RESTART_REQUEST_CODE = 0xC2A5
    }
}
