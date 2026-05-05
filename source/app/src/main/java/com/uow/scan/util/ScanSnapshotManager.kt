package com.uow.scan.util

import android.content.Context
import com.uow.scan.data.ScanDatabase
import com.uow.scan.data.entity.ScanResultEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saves/loads a snapshot of scan results so the previous scan can be
 * compared with the current one after the DB is overwritten.
 */
object ScanSnapshotManager {

    private const val SNAPSHOT_FILE = "previous_scan_snapshot.json"

    /**
     * Save the current scan_results from DB to a JSON file before they get overwritten.
     * Call this BEFORE clearAll + insertAll in ScanActivity.
     */
    suspend fun snapshotCurrentScan(context: Context) {
        val db = ScanDatabase.getInstance(context)
        val current = db.scanResultDao().getAll()
        if (current.isEmpty()) return

        val jsonArray = JSONArray()
        for (entity in current) {
            jsonArray.put(JSONObject().apply {
                put("packageName", entity.packageName)
                put("appName", entity.appName)
                put("versionName", entity.versionName)
                put("versionCode", entity.versionCode)
                put("permissions", entity.permissions)
                put("riskLevel", entity.riskLevel)
                put("isSystemApp", entity.isSystemApp)
                put("installedDate", entity.installedDate)
                put("lastUpdated", entity.lastUpdated)
                put("scannedAt", entity.scannedAt)
            })
        }

        val file = File(context.filesDir, SNAPSHOT_FILE)
        file.writeText(jsonArray.toString())
    }

    /**
     * Load the previous scan snapshot from file.
     */
    fun loadPreviousSnapshot(context: Context): List<ScanResultEntity>? {
        val file = File(context.filesDir, SNAPSHOT_FILE)
        if (!file.exists()) return null

        return try {
            val jsonArray = JSONArray(file.readText())
            val results = mutableListOf<ScanResultEntity>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                results.add(
                    ScanResultEntity(
                        packageName = obj.getString("packageName"),
                        appName = obj.getString("appName"),
                        versionName = obj.optString("versionName", ""),
                        versionCode = obj.optLong("versionCode", 0),
                        permissions = obj.optString("permissions", ""),
                        riskLevel = obj.getString("riskLevel"),
                        isSystemApp = obj.optBoolean("isSystemApp", false),
                        installedDate = obj.optLong("installedDate", 0),
                        lastUpdated = obj.optLong("lastUpdated", 0),
                        scannedAt = obj.optLong("scannedAt", 0)
                    )
                )
            }
            results
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Compare previous snapshot with current scan results.
     */
    fun compare(
        previous: List<ScanResultEntity>,
        current: List<ScanResultEntity>
    ): ScanComparison {
        val prevMap = previous.associateBy { it.packageName }
        val currMap = current.associateBy { it.packageName }

        val newApps = current.filter { it.packageName !in prevMap }
        val removedApps = previous.filter { it.packageName !in currMap }

        val riskChanges = mutableListOf<RiskChange>()
        val permissionChanges = mutableListOf<PermissionChange>()

        for (curr in current) {
            val prev = prevMap[curr.packageName] ?: continue

            // Risk level change
            if (prev.riskLevel != curr.riskLevel) {
                riskChanges.add(
                    RiskChange(
                        appName = curr.appName,
                        packageName = curr.packageName,
                        oldRisk = prev.riskLevel,
                        newRisk = curr.riskLevel
                    )
                )
            }

            // Permission changes
            val prevPerms = prev.permissions.split(",").filter { it.isNotBlank() }.toSet()
            val currPerms = curr.permissions.split(",").filter { it.isNotBlank() }.toSet()
            val added = currPerms - prevPerms
            val removed = prevPerms - currPerms

            if (added.isNotEmpty() || removed.isNotEmpty()) {
                permissionChanges.add(
                    PermissionChange(
                        appName = curr.appName,
                        packageName = curr.packageName,
                        addedPermissions = added.toList(),
                        removedPermissions = removed.toList()
                    )
                )
            }
        }

        return ScanComparison(
            previousScanTime = previous.firstOrNull()?.scannedAt ?: 0,
            currentScanTime = current.firstOrNull()?.scannedAt ?: 0,
            previousAppCount = previous.size,
            currentAppCount = current.size,
            newApps = newApps,
            removedApps = removedApps,
            riskChanges = riskChanges,
            permissionChanges = permissionChanges
        )
    }

    data class ScanComparison(
        val previousScanTime: Long,
        val currentScanTime: Long,
        val previousAppCount: Int,
        val currentAppCount: Int,
        val newApps: List<ScanResultEntity>,
        val removedApps: List<ScanResultEntity>,
        val riskChanges: List<RiskChange>,
        val permissionChanges: List<PermissionChange>
    ) {
        val hasChanges: Boolean
            get() = newApps.isNotEmpty() || removedApps.isNotEmpty() ||
                    riskChanges.isNotEmpty() || permissionChanges.isNotEmpty()
    }

    data class RiskChange(
        val appName: String,
        val packageName: String,
        val oldRisk: String,
        val newRisk: String
    )

    data class PermissionChange(
        val appName: String,
        val packageName: String,
        val addedPermissions: List<String>,
        val removedPermissions: List<String>
    )
}
