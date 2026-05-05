package com.uow.scan.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_checks")
data class DeviceCheckEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val score: Int,
    val screenLockEnabled: Boolean,
    val biometricEnrolled: Boolean,
    val diskEncrypted: Boolean,
    val developerOptionsOff: Boolean,
    val usbDebuggingOff: Boolean,
    val unknownSourcesOff: Boolean,
    val osUpToDate: Boolean,
    /** Set in V1.4 when the connected Wi-Fi passes the [WifiSecurityAnalyzer] threshold. */
    val wifiNetworkSafe: Boolean = false,
    val checkedAt: Long = System.currentTimeMillis()
)
