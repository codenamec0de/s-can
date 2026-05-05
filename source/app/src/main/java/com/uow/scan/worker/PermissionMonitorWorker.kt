package com.uow.scan.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.uow.scan.data.ScanDatabase
import com.uow.scan.util.AlertStorage
import com.uow.scan.util.BackgroundUsageMonitor
import com.uow.scan.util.FileLogger

/**
 * Periodic WorkManager worker that scans for apps using data in the background
 * while holding sensitive permissions, and stores the resulting alerts.
 */
class PermissionMonitorWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "permission_monitor"
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        FileLogger.d(context, "WorkManager doWork() started")
        val now = System.currentTimeMillis()

        var lastCheck = AlertStorage.getLastCheckTime(context)
        if (lastCheck == 0L) {
            lastCheck = now - 15 * 60 * 1000L
        }

        val windowMin = (now - lastCheck) / 60_000
        FileLogger.d(context, "WorkManager scan window: ${windowMin}min")

        val alerts = BackgroundUsageMonitor.scan(context, lastCheck, now)
        FileLogger.d(context, "WorkManager found ${alerts.size} alerts")

        // Deduplicate - skip apps already alerted in last 2 hours
        val dedupWindow = 2 * 60 * 60 * 1000L
        val alertDao = ScanDatabase.getInstance(context).alertDao()
        val dedupedAlerts = alerts.filter { alert ->
            alertDao.getRecentAlertCount(alert.packageName, now - dedupWindow) == 0
        }
        FileLogger.d(context, "WorkManager after dedup: ${dedupedAlerts.size}")

        if (dedupedAlerts.isNotEmpty()) {
            AlertStorage.addAlerts(context, dedupedAlerts)
        }

        AlertStorage.setLastCheckTime(context, now)
        return Result.success()
    }
}
