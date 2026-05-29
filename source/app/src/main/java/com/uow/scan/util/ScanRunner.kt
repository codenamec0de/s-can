package com.uow.scan.util

import android.content.Context
import com.uow.scan.data.ScanDatabase
import com.uow.scan.data.entity.MonitoredAppEntity
import com.uow.scan.data.entity.ScanResultEntity
import com.uow.scan.model.AppInfo
import com.uow.scan.model.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Full device scan pipeline (extracted from ScanActivity so it can run
 * inline from any screen - e.g. the Home scan CTA).
 *
 * Steps:
 *   1. Enumerate installed packages
 *   2. Scan each package in parallel batches
 *   3. Sort + classify by risk, cache to Room, snapshot, seed monitor table
 */
object ScanRunner {

    data class Progress(
        val stage: Stage,
        val currentAppName: String? = null,
        val scanned: Int = 0,
        val total: Int = 0
    )

    enum class Stage { DISCOVERING, SCANNING, ANALYZING, SAVING, DONE }

    /**
     * Run the full scan. Progress callbacks always fire on the main
     * thread so callers can update views directly without extra marshalling.
     */
    suspend fun runFullScan(
        context: Context,
        onProgress: (Progress) -> Unit = {}
    ): List<AppInfo> = coroutineScope {
        suspend fun emit(p: Progress) = withContext(Dispatchers.Main) { onProgress(p) }

        emit(Progress(Stage.DISCOVERING))
        val packages = withContext(Dispatchers.IO) {
            AppScanner.getInstalledPackages(context)
        }
        val total = packages.size
        if (total == 0) {
            emit(Progress(Stage.DONE))
            return@coroutineScope emptyList<AppInfo>()
        }

        emit(Progress(Stage.SCANNING, scanned = 0, total = total))

        val results = mutableListOf<AppInfo>()
        var scannedCount = 0

        withContext(Dispatchers.IO) {
            packages.chunked(BATCH_SIZE).forEach { batch ->
                val batchResults = batch.map { pkg ->
                    async { AppScanner.scanSinglePackage(context, pkg) }
                }.awaitAll().filterNotNull()

                results += batchResults
                scannedCount += batch.size

                val last = batchResults.lastOrNull()?.appName
                emit(
                    Progress(
                        stage = Stage.SCANNING,
                        currentAppName = last,
                        scanned = scannedCount.coerceAtMost(total),
                        total = total
                    )
                )
            }
        }

        emit(Progress(Stage.ANALYZING, scanned = total, total = total))

        val sorted = results.sortedWith(
            compareByDescending<AppInfo> {
                it.permissions.count { perm -> perm in AppInfo.SENSITIVE_PERMISSIONS }
            }.thenByDescending { it.riskLevel.ordinal }
        )

        emit(Progress(Stage.SAVING))

        withContext(Dispatchers.IO) {
            val db = ScanDatabase.getInstance(context)
            val now = System.currentTimeMillis()

            ScanSnapshotManager.snapshotCurrentScan(context)

            // "Real finding" signal: which apps were observed accessing a sensor in the
            // background. Only these escalate to HIGH; capability alone caps at MEDIUM.
            val flagged = db.permissionAccessDao()
                .packagesWithBackgroundAccess(now - FINDING_WINDOW_MS)
                .toSet()

            val entities = sorted.map { app ->
                ScanResultEntity(
                    packageName = app.packageName,
                    appName = app.appName,
                    versionName = app.versionName,
                    versionCode = app.versionCode,
                    permissions = app.permissions.joinToString(","),
                    riskLevel = AppScanner.effectiveRisk(app.riskLevel, app.packageName in flagged).name,
                    isSystemApp = app.isSystemApp,
                    installedDate = app.installedDate,
                    lastUpdated = app.lastUpdated,
                    scannedAt = now
                )
            }
            db.scanResultDao().clearAll()
            db.scanResultDao().insertAll(entities)

            val monitorDao = db.monitoredAppDao()
            for (app in sorted) {
                if (monitorDao.getByPackage(app.packageName) == null) {
                    monitorDao.insert(
                        MonitoredAppEntity(
                            packageName = app.packageName,
                            appName = app.appName,
                            riskLevel = AppScanner.effectiveRisk(app.riskLevel, app.packageName in flagged).name,
                            // Monitoring tracks anything with sensitive access (exposure-based),
                            // independent of whether a finding has surfaced yet.
                            isMonitored = app.riskLevel != RiskLevel.LOW
                        )
                    )
                }
            }
        }

        PreferencesManager.setLastScanTime(context, System.currentTimeMillis())

        emit(Progress(Stage.DONE, scanned = total, total = total))
        sorted
    }

    private const val BATCH_SIZE = 8

    /** Lookback for the "observed background sensor access" finding that escalates risk to HIGH. */
    private const val FINDING_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
}
