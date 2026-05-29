package com.uow.scan.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import com.uow.scan.model.AppInfo
import com.uow.scan.model.RiskLevel

object AppScanner {

    /**
     * Returns the raw list of non-system PackageInfo objects.
     * Used by ScanActivity for batched parallel scanning.
     */
    fun getInstalledPackages(context: Context, includeSystemApps: Boolean = false): List<PackageInfo> {
        val packageManager = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }

        val selfPackage = context.packageName

        return packages.filter { packageInfo ->
            packageInfo.packageName != selfPackage &&
            (includeSystemApps || !isHiddenSystemApp(packageManager, packageInfo))
        }
    }

    /**
     * Returns true for system apps that have no launcher icon (background services,
     * system internals). User-facing system apps like YouTube, Chrome, Maps etc.
     * return false and ARE included in scan results.
     */
    private fun isHiddenSystemApp(pm: PackageManager, packageInfo: PackageInfo): Boolean {
        val appInfo = packageInfo.applicationInfo ?: return false
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (!isSystem) return false
        return pm.getLaunchIntentForPackage(packageInfo.packageName) == null
    }

    /**
     * Scans a single package and returns an AppInfo, or null on failure.
     * Used by ScanActivity for parallel batch processing.
     */
    fun scanSinglePackage(context: Context, packageInfo: PackageInfo): AppInfo? {
        return try {
            createAppInfo(context.packageManager, packageInfo)
        } catch (e: Exception) {
            null
        }
    }

    fun scanInstalledApps(context: Context, includeSystemApps: Boolean = false): List<AppInfo> {
        val packages = getInstalledPackages(context, includeSystemApps)

        return packages
            .mapNotNull { packageInfo ->
                try {
                    createAppInfo(context.packageManager, packageInfo)
                } catch (e: Exception) {
                    null
                }
            }
            // Sort by sensitive permission count (most first), then by risk level
            .sortedWith(compareByDescending<AppInfo> {
                it.permissions.count { perm -> perm in AppInfo.SENSITIVE_PERMISSIONS }
            }.thenByDescending { it.riskLevel.ordinal })
    }
    
    private fun createAppInfo(packageManager: PackageManager, packageInfo: PackageInfo): AppInfo {
        val applicationInfo = packageInfo.applicationInfo
        val appName = applicationInfo?.let { 
            packageManager.getApplicationLabel(it).toString() 
        } ?: packageInfo.packageName
        
        val icon = try {
            applicationInfo?.let { packageManager.getApplicationIcon(it) }
        } catch (e: Exception) {
            null
        }
        
        // Get only GRANTED permissions, remove duplicates
        val grantedPermissions = getGrantedPermissions(packageManager, packageInfo)
        val isSystemApp = (applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) ?: 0) != 0
        
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        
        return AppInfo(
            packageName = packageInfo.packageName,
            appName = appName,
            icon = icon,
            versionName = packageInfo.versionName ?: "Unknown",
            versionCode = versionCode,
            installedDate = packageInfo.firstInstallTime,
            lastUpdated = packageInfo.lastUpdateTime,
            permissions = grantedPermissions,
            riskLevel = calculateRiskLevel(grantedPermissions),
            isSystemApp = isSystemApp
        )
    }
    
    /**
     * Returns only dangerous permissions that the user has explicitly granted.
     * Normal permissions (INTERNET, WAKE_LOCK, etc.) are excluded - they are
     * auto-granted at install time and do not reflect any user decision.
     */
    private fun getGrantedPermissions(packageManager: PackageManager, packageInfo: PackageInfo): List<String> {
        val permissions = packageInfo.requestedPermissions ?: return emptyList()
        val flags = packageInfo.requestedPermissionsFlags ?: return emptyList()

        return permissions.filterIndexed { index, permission ->
            val isGranted = (flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            if (!isGranted) return@filterIndexed false
            try {
                val permInfo = packageManager.getPermissionInfo(permission, 0)
                (permInfo.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }.distinct()
    }
    
    /**
     * Count sensitive permissions for an app
     */
    fun countSensitivePermissions(permissions: List<String>): Int {
        return permissions.count { it in AppInfo.SENSITIVE_PERMISSIONS }
    }
    
    /**
     * Spyware-hallmark capabilities — rarely legitimate, and the things that genuinely make an
     * app dangerous: reading your texts, reading your call history, or tracking your location
     * in the background. The presence of these (especially in combination) defines HIGH risk.
     */
    private val CRITICAL_PERMISSIONS = setOf(
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.SEND_SMS",
        "android.permission.READ_CALL_LOG",
        "android.permission.WRITE_CALL_LOG",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
    )

    /**
     * Notable, but commonly-legitimate, sensitive access. Storage/media and phone-state are
     * deliberately excluded — they're near-universal and low-signal, and counting them is what
     * pushed almost every ordinary app to HIGH under the old rule.
     */
    private val RISK_SENSITIVE_PERMISSIONS = setOf(
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
        "android.permission.BODY_SENSORS",
    )

    /**
     * Risk reflects how invasive an app's GRANTED permissions are — recalibrated so HIGH means
     * a genuine surveillance profile, not merely "holds a few permissions" (the old rule branded
     * ordinary camera/social apps HIGH, which didn't line up with having no actual findings).
     *
     *   HIGH   = a comms/covert-tracking capability AND other sensitive access, or two such
     *            capabilities (e.g. an app that can read your texts and your contacts).
     *   MEDIUM = some meaningful sensitive access, but not a surveillance profile.
     *   LOW    = no meaningful sensitive access.
     */
    private fun calculateRiskLevel(permissions: List<String>): RiskLevel {
        val critical = permissions.count { it in CRITICAL_PERMISSIONS }
        val sensitive = permissions.count { it in RISK_SENSITIVE_PERMISSIONS }
        return when {
            critical >= 2 || (critical >= 1 && sensitive >= 2) -> RiskLevel.HIGH
            critical >= 1 || sensitive >= 1 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }
    
    /**
     * The risk we actually SHOW, combining permission exposure with observed evidence.
     *
     * [calculateRiskLevel] gives the permission *capability* (how invasive the app COULD be).
     * But a capability with nothing observed behind it shouldn't scream HIGH — that's what made
     * ordinary apps look dangerous. So:
     *   • If we have a real finding (an observed background sensor access, or a critical app-
     *     integrity issue), the app is HIGH.
     *   • Otherwise a capability-HIGH app is shown as MEDIUM ("elevated exposure, nothing
     *     detected"); MEDIUM/LOW pass through unchanged.
     */
    fun effectiveRisk(capability: RiskLevel, hasFinding: Boolean): RiskLevel = when {
        hasFinding -> RiskLevel.HIGH
        capability == RiskLevel.HIGH -> RiskLevel.MEDIUM
        else -> capability
    }

    fun getAppsByRiskLevel(apps: List<AppInfo>, riskLevel: RiskLevel): List<AppInfo> {
        return apps.filter { it.riskLevel == riskLevel }
    }
    
    fun countByRiskLevel(apps: List<AppInfo>): Map<RiskLevel, Int> {
        return mapOf(
            RiskLevel.HIGH to apps.count { it.riskLevel == RiskLevel.HIGH },
            RiskLevel.MEDIUM to apps.count { it.riskLevel == RiskLevel.MEDIUM },
            RiskLevel.LOW to apps.count { it.riskLevel == RiskLevel.LOW }
        )
    }
    
    fun searchApps(apps: List<AppInfo>, query: String): List<AppInfo> {
        if (query.isBlank()) return apps
        val lowerQuery = query.lowercase()
        return apps.filter { 
            it.appName.lowercase().contains(lowerQuery) ||
            it.packageName.lowercase().contains(lowerQuery)
        }
    }
}
