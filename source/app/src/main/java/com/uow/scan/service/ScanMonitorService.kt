package com.uow.scan.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.uow.scan.MainActivity
import com.uow.scan.R
import com.uow.scan.data.ScanDatabase
import com.uow.scan.model.PermissionAlert
import com.uow.scan.util.AlertStorage
import com.uow.scan.util.BackgroundUsageMonitor
import com.uow.scan.util.FileLogger
import com.uow.scan.util.PreferencesManager
import com.uow.scan.util.TerminatorEngine
import com.uow.scan.util.TerminatorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Always-on foreground service that monitors background data usage.
 *
 * Runs a scan loop every [SCAN_INTERVAL_MS] (10 minutes), checking only
 * apps that the user has enabled in the Monitor tab.
 *
 * Survives app close. Restarted by [BootReceiver] after device reboot.
 * WorkManager remains as a fallback in case this service is killed.
 */
class ScanMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "scan_monitor_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.uow.scan.MONITOR_START"
        const val ACTION_STOP = "com.uow.scan.MONITOR_STOP"

        /** Scan interval - 10 minutes */
        private const val SCAN_INTERVAL_MS = 10 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, ScanMonitorService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScanMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
                if (ScanMonitorService::class.java.name == service.service.className) {
                    return true
                }
            }
            return false
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var loopRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FileLogger.d(this, "onStartCommand action=${intent?.action} loopRunning=$loopRunning")
        when (intent?.action) {
            ACTION_STOP -> {
                FileLogger.d(this, "Stopping service")
                loopRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification(statusLine()))
                startMonitorLoop()
            }
        }
        return START_STICKY
    }

    private fun startMonitorLoop() {
        if (loopRunning) {
            FileLogger.w(this, "Monitor loop already running - skipping duplicate launch")
            return
        }
        loopRunning = true
        FileLogger.d(this, "Starting monitor loop (interval=${SCAN_INTERVAL_MS / 1000}s)")

        scope.launch {
            while (isActive && loopRunning) {
                try {
                    acquireWakeLock()
                    runScan()
                } catch (e: Exception) {
                    FileLogger.e(this@ScanMonitorService, "runScan() FAILED", e)
                } finally {
                    releaseWakeLock()
                }
                delay(SCAN_INTERVAL_MS)
            }
            FileLogger.d(this@ScanMonitorService, "Monitor loop exited (isActive=$isActive, loopRunning=$loopRunning)")
        }
    }

    private suspend fun runScan() {
        val db = ScanDatabase.getInstance(this)
        val now = System.currentTimeMillis()

        // Get last check time
        val lastCheckStr = db.appSettingsDao().get("last_check_time")
        var lastCheck = lastCheckStr?.toLongOrNull() ?: 0L
        if (lastCheck == 0L) {
            lastCheck = now - SCAN_INTERVAL_MS
        }

        val windowMinutes = (now - lastCheck) / 60_000
        FileLogger.d(this, "runScan() window: ${windowMinutes}min ago → now")

        // Get packages the user has enabled for monitoring
        val monitoredApps = db.monitoredAppDao().getMonitored()
        val monitoredPackages = monitoredApps.map { it.packageName }.toSet()
        FileLogger.d(this, "Monitored apps: ${monitoredPackages.size}")

        if (monitoredPackages.isEmpty()) {
            FileLogger.w(this, "No apps are monitored - scan will report all")
        }

        // Run the scan
        val allAlerts = BackgroundUsageMonitor.scan(this, lastCheck, now)
        FileLogger.d(this, "Raw alerts from scan: ${allAlerts.size}")

        for (alert in allAlerts) {
            FileLogger.d(this, "  raw: ${alert.appName} data=${alert.dataUsedBytes}B perms=${alert.permissions}")
        }

        // Filter to only monitored apps
        val monitorFiltered = if (monitoredPackages.isNotEmpty()) {
            allAlerts.filter { it.packageName in monitoredPackages }
        } else {
            allAlerts
        }
        FileLogger.d(this, "After monitor filter: ${monitorFiltered.size}")

        // Deduplicate - skip apps we already alerted on in the last 2 hours
        val dedupWindow = 2 * 60 * 60 * 1000L
        val alertDao = db.alertDao()
        val filteredAlerts = monitorFiltered.filter { alert ->
            val recentCount = alertDao.getRecentAlertCount(alert.packageName, now - dedupWindow)
            if (recentCount > 0) {
                FileLogger.d(this, "  dedup: skipping ${alert.appName} (already alerted ${recentCount}x in last 2h)")
            }
            recentCount == 0
        }

        FileLogger.d(this, "After dedup: ${filteredAlerts.size}")

        if (filteredAlerts.isNotEmpty()) {
            AlertStorage.addAlerts(this, filteredAlerts)
            updateNotification(
                "${statusLine()} | ${filteredAlerts.size} new alert${if (filteredAlerts.size > 1) "s" else ""}"
            )
            sendAlertNotification(filteredAlerts)
            FileLogger.d(this, "Saved ${filteredAlerts.size} alerts and sent notification")
        } else {
            // Refresh notification each cycle so SMS state changes from Settings are reflected.
            updateNotification(statusLine())
            FileLogger.d(this, "No alerts to save this cycle")
        }

        // Terminator - check if any watched apps went to background
        if (TerminatorManager.isEnabled(this)) {
            val watchedApps = TerminatorManager.getWatchedApps(this)
            for (alert in allAlerts) {
                if (alert.packageName in watchedApps) {
                    FileLogger.d(this, "Terminator: ${alert.appName} is watched and was backgrounded")
                    TerminatorEngine.onAppBackgrounded(this, alert.packageName)
                }
            }
        }

        // Update last check time
        db.appSettingsDao().set(
            com.uow.scan.data.entity.AppSettingsEntity("last_check_time", now.toString())
        )

        // Update data usage stats for monitored apps
        for (alert in filteredAlerts) {
            db.monitoredAppDao().updateDataUsage(
                alert.packageName, alert.dataUsedBytes, now
            )
        }
    }

    /**
     * Sends a separate heads-up notification when new alerts are detected.
     */
    private fun sendAlertNotification(alerts: List<PermissionAlert>) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create alert channel (high importance for heads-up)
        val alertChannel = NotificationChannel(
            "scan_alert_channel",
            "Privacy Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when apps use data in the background"
        }
        nm.createNotificationChannel(alertChannel)

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("navigate_to", "alerts")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (alerts.size == 1) {
            "${alerts[0].appName} used data in background"
        } else {
            "${alerts.size} apps used data in background"
        }

        val text = if (alerts.size == 1) {
            "${alerts[0].formattedDataUsed} of data while using ${alerts[0].permissions.firstOrNull() ?: "permissions"}"
        } else {
            alerts.joinToString(", ") { it.appName }
        }

        val notification = Notification.Builder(this, "scan_alert_channel")
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID + 1, notification)
    }

    // -------------------------------------------------------------------------
    // Notification management
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "S'CAN Protection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification while S'CAN monitors your device"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("S'CAN")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    /**
     * Composes the persistent foreground-notification status line. Includes an explicit
     * "SMS scanning" indicator when the user has opted into SMS scam detection - the
     * persistent visibility addresses the disclosure requirement in the SMS roadmap §13.1.
     */
    private fun statusLine(): String {
        val base = "S'CAN is protecting your device"
        return if (PreferencesManager.isSmsScamDetectionEnabled(this)) {
            "$base · SMS scanning ON"
        } else {
            base
        }
    }

    // -------------------------------------------------------------------------
    // Wake lock - keeps CPU alive during scan
    // -------------------------------------------------------------------------

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "scan:monitor_scan"
        ).apply {
            acquire(60 * 1000L) // 60 second timeout max
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    override fun onDestroy() {
        FileLogger.w(this, "Service onDestroy() - service is being killed")
        loopRunning = false
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
}
