package com.uow.scan.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One record per observed sensor access by a third-party app, written by
 * [com.uow.scan.util.OpAccessTracker]. [endedAt] is null while the access
 * is still active.
 *
 * `op` is one of "CAMERA" or "MICROPHONE" — the only sensor ops a
 * non-system app can observe via public Android APIs (CameraManager
 * AvailabilityCallback, AudioManager AudioRecordingCallback).
 */
@Entity(
    tableName = "permission_access_events",
    indices = [
        Index(value = ["packageName", "startedAt"]),
        Index(value = ["op"]),
    ]
)
data class PermissionAccessEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val op: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val foregroundAtStart: Boolean
)
