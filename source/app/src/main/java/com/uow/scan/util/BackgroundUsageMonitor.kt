package com.uow.scan.util

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import com.uow.scan.model.PermissionAlert
import java.util.UUID

/**
 * Detects apps that used data while running in the background and hold sensitive permissions.
 *
 * How it works:
 * 1. Query UsageStatsManager events to find apps that were in the background during the window.
 * 2. For each such app, check if it holds any dangerous (user-granted) sensitive permissions.
 * 3. Query NetworkStatsManager for data usage during that background window.
 * 4. Generate a PermissionAlert for any app that transmitted data from the background.
 */
object BackgroundUsageMonitor {

    /** Minimum data usage (bytes) to generate an alert - avoids noise from keep-alive pings. */
    private const val MIN_DATA_THRESHOLD = 50 * 1024L // 50 KB

    /** NetworkStats lookback window - 2 hours, independent of scan interval. */
    private const val DATA_LOOKBACK_MS = 2 * 60 * 60 * 1000L

    /**
     * Scan for background permission usage in the given time window.
     * Returns a list of new alerts.
     */
    fun scan(context: Context, startTime: Long, endTime: Long): List<PermissionAlert> {
        val hasPermission = DataUsageHelper.hasUsageStatsPermission(context)
        FileLogger.d(context, "BackgroundUsageMonitor.scan() hasUsageStats=$hasPermission window=${(endTime - startTime) / 1000}s")
        if (!hasPermission) {
            FileLogger.w(context, "Usage stats permission NOT granted - returning empty")
            return emptyList()
        }

        // Use a wider lookback for NetworkStats - Samsung and other OEMs flush stats
        // in coarse buckets, so short windows often return 0.
        val dataStartTime = endTime - DATA_LOOKBACK_MS
        FileLogger.d(context, "NetworkStats lookback: ${DATA_LOOKBACK_MS / 60_000}min (from ${(endTime - dataStartTime) / 60_000}min ago)")

        // Query ALL per-UID data usage once via querySummary(). On Samsung OneUI 5+
        // (Android 13+), queryDetailsForUid() is privacy-locked to the caller's own
        // UID even with PACKAGE_USAGE_STATS granted, so the previous per-UID path
        // always returned 0B for every other app. querySummary() returns aggregated
        // per-UID buckets - the same API Settings > Data Usage uses internally -
        // and works reliably across OEMs. Batching to a single query per scan also
        // eliminates NetworkStats rate-limiting risk from the old pattern (up to
        // 138 queries per scan in a 69-app workload).
        val uidDataMap = DataUsageHelper.getAllAppsDataUsage(context, dataStartTime, endTime)
        FileLogger.d(context, "NetworkStats summary: ${uidDataMap.size} UIDs with data")

        val backgroundApps = getBackgroundAppDurations(context, startTime, endTime)
        FileLogger.d(context, "Pass 1: ${backgroundApps.size} apps had activity transitions")

        val pm = context.packageManager
        val alerts = mutableListOf<PermissionAlert>()

        // --- Pass 1: Apps with Activity transitions (existing detection) ---
        for ((packageName, durationMs) in backgroundApps) {
            if (packageName == context.packageName) continue

            val sensitivePerms = getGrantedSensitivePermissions(pm, packageName)
            if (sensitivePerms.isEmpty()) continue

            val uid = try {
                pm.getApplicationInfo(packageName, 0).uid
            } catch (e: Exception) {
                -1
            }
            val dataUsed = if (uid >= 0) uidDataMap[uid]?.totalBytes ?: 0L else 0L
            FileLogger.d(context, "  Pass1: $packageName bg=${durationMs}ms data=${dataUsed}B perms=$sensitivePerms")
            if (dataUsed < MIN_DATA_THRESHOLD) continue

            val appName = try {
                val ai = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                packageName
            }

            alerts.add(
                PermissionAlert(
                    id = UUID.randomUUID().toString(),
                    packageName = packageName,
                    appName = appName,
                    permissions = sensitivePerms,
                    dataUsedBytes = dataUsed,
                    backgroundDurationMs = durationMs,
                    timestamp = endTime
                )
            )
        }

        // --- Pass 2: Silent background data (push notifications, services) ---
        val installedPackages = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            }
        } catch (e: Exception) {
            FileLogger.e(context, "Failed to get installed packages for Pass 2", e)
            emptyList()
        }

        var pass2Checked = 0
        var pass2WithPerms = 0
        var pass2WithData = 0

        for (pkgInfo in installedPackages) {
            val packageName = pkgInfo.packageName ?: continue

            if (packageName == context.packageName) continue
            if (packageName in backgroundApps) continue

            // Skip hidden system apps (no launcher icon) but include user-facing
            // system apps like YouTube, Chrome, Maps etc.
            val appInfo = pkgInfo.applicationInfo ?: continue
            val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem && pm.getLaunchIntentForPackage(packageName) == null) continue

            pass2Checked++

            val sensitivePerms = getGrantedSensitivePermissions(pm, packageName)
            if (sensitivePerms.isEmpty()) continue

            pass2WithPerms++

            val dataUsed = uidDataMap[appInfo.uid]?.totalBytes ?: 0L
            if (dataUsed > 0) {
                pass2WithData++
                FileLogger.d(context, "  Pass2: $packageName data=${dataUsed}B perms=$sensitivePerms")
            }
            if (dataUsed < MIN_DATA_THRESHOLD) continue

            val appName = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageName
            }

            alerts.add(
                PermissionAlert(
                    id = UUID.randomUUID().toString(),
                    packageName = packageName,
                    appName = appName,
                    permissions = sensitivePerms,
                    dataUsedBytes = dataUsed,
                    backgroundDurationMs = 0L, // Unknown - no Activity events observed
                    timestamp = endTime
                )
            )
        }

        FileLogger.d(context, "Pass 2 summary: checked=$pass2Checked withPerms=$pass2WithPerms withAnyData=$pass2WithData")
        FileLogger.d(context, "Total alerts: ${alerts.size}")
        return alerts
    }

    // -------------------------------------------------------------------------
    // Background duration detection via UsageEvents
    // -------------------------------------------------------------------------

    /**
     * Returns a map of packageName -> total background duration (ms) during the window.
     * An app is "in background" between ACTIVITY_PAUSED and the next ACTIVITY_RESUMED.
     */
    private fun getBackgroundAppDurations(
        context: Context,
        startTime: Long,
        endTime: Long
    ): Map<String, Long> {
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return emptyMap()

        val events = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()

        // Track the last known state per package:  timestamp of move-to-background
        val bgStartTimes = mutableMapOf<String, Long>()
        val bgDurations = mutableMapOf<String, Long>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    // App moved to background
                    if (pkg !in bgStartTimes) {
                        bgStartTimes[pkg] = event.timeStamp
                    }
                }
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    // App came to foreground - close the background window
                    val bgStart = bgStartTimes.remove(pkg)
                    if (bgStart != null) {
                        val duration = event.timeStamp - bgStart
                        bgDurations[pkg] = (bgDurations[pkg] ?: 0L) + duration
                    }
                }
            }
        }

        // For apps still in the background at endTime, close the window at endTime
        for ((pkg, bgStart) in bgStartTimes) {
            val duration = endTime - bgStart
            bgDurations[pkg] = (bgDurations[pkg] ?: 0L) + duration
        }

        return bgDurations
    }

    // -------------------------------------------------------------------------
    // Permission checking
    // -------------------------------------------------------------------------

    /**
     * Returns the human-readable names of granted dangerous sensitive permissions for [packageName].
     */
    private fun getGrantedSensitivePermissions(
        pm: PackageManager,
        packageName: String
    ): List<String> {
        val packageInfo: PackageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            }
        } catch (e: Exception) {
            return emptyList()
        }

        val permissions = packageInfo.requestedPermissions ?: return emptyList()
        val flags = packageInfo.requestedPermissionsFlags ?: return emptyList()

        return permissions.filterIndexed { index, permission ->
            val isGranted =
                (flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            if (!isGranted) return@filterIndexed false
            val isDangerous = try {
                val permInfo = pm.getPermissionInfo(permission, 0)
                (permInfo.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) ==
                        PermissionInfo.PROTECTION_DANGEROUS
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
            isDangerous && PermissionHelper.isSensitivePermission(permission)
        }.map { PermissionHelper.getPermissionName(it) }
    }

    // -------------------------------------------------------------------------
    // Data usage per app
    // -------------------------------------------------------------------------

    /**
     * Public entry point for querying a single app's data usage.
     * Used by TestDataUsageService to verify detection of its own traffic.
     *
     * Delegates to [DataUsageHelper.getAllAppsDataUsage], which uses
     * [android.app.usage.NetworkStatsManager.querySummary] - the only
     * NetworkStats API that reliably returns cross-UID data on Samsung
     * OneUI 5+. `queryDetailsForUid()` is privacy-locked to the caller's
     * own UID on modern Samsung firmware and silently returns 0B for all
     * other apps.
     */
    fun getDataUsageForPackage(
        context: Context,
        packageName: String,
        startTime: Long,
        endTime: Long
    ): Long {
        val uid = try {
            context.packageManager.getApplicationInfo(packageName, 0).uid
        } catch (e: Exception) {
            return 0L
        }
        val map = DataUsageHelper.getAllAppsDataUsage(context, startTime, endTime)
        return map[uid]?.totalBytes ?: 0L
    }
}
