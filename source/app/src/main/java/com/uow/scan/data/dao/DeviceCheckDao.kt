package com.uow.scan.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.uow.scan.data.entity.DeviceCheckEntity

@Dao
interface DeviceCheckDao {

    @Query("SELECT * FROM device_checks ORDER BY checkedAt DESC LIMIT 1")
    suspend fun getLatest(): DeviceCheckEntity?

    @Query("SELECT * FROM device_checks ORDER BY checkedAt ASC")
    suspend fun getAll(): List<DeviceCheckEntity>

    @Insert
    suspend fun insert(check: DeviceCheckEntity)

    @Query("DELETE FROM device_checks WHERE id NOT IN (SELECT id FROM device_checks ORDER BY checkedAt DESC LIMIT 30)")
    suspend fun pruneOldChecks()
}
