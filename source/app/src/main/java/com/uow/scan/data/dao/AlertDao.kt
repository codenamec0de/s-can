package com.uow.scan.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.uow.scan.data.entity.AlertEntity

@Dao
interface AlertDao {

    @Query("SELECT * FROM alerts ORDER BY timestamp DESC LIMIT 100")
    suspend fun getAll(): List<AlertEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: AlertEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(alerts: List<AlertEntity>)

    @Query("UPDATE alerts SET isRead = 1 WHERE id = :alertId")
    suspend fun markAsRead(alertId: String)

    @Query("SELECT COUNT(*) FROM alerts WHERE isRead = 0")
    suspend fun getUnreadCount(): Int

    @Query("DELETE FROM alerts")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM alerts")
    suspend fun getCount(): Int

    @Query("DELETE FROM alerts WHERE id IN (SELECT id FROM alerts ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)

    @Query("SELECT COUNT(*) FROM alerts WHERE packageName = :packageName")
    suspend fun getAlertCountForPackage(packageName: String): Int

    @Query("SELECT COUNT(*) FROM alerts WHERE packageName = :packageName AND timestamp > :since")
    suspend fun getRecentAlertCount(packageName: String, since: Long): Int

    @Query("SELECT appName, packageName, SUM(dataUsedBytes) as totalData FROM alerts GROUP BY packageName ORDER BY totalData DESC LIMIT :limit")
    suspend fun getTopDataUsers(limit: Int = 5): List<TopDataUser>
}

data class TopDataUser(
    val appName: String,
    val packageName: String,
    val totalData: Long
)
