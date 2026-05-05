package com.uow.scan.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.uow.scan.data.entity.SmsVerdictEntity

@Dao
interface SmsVerdictDao {

    @Query("SELECT * FROM sms_verdicts ORDER BY timestamp DESC LIMIT 100")
    suspend fun getAll(): List<SmsVerdictEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(verdict: SmsVerdictEntity): Long

    @Query("UPDATE sms_verdicts SET isRead = 1 WHERE id = :id")
    suspend fun markAsRead(id: Long)

    @Query("SELECT COUNT(*) FROM sms_verdicts WHERE isRead = 0")
    suspend fun getUnreadCount(): Int

    @Query("DELETE FROM sms_verdicts")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM sms_verdicts")
    suspend fun getCount(): Int

    @Query("DELETE FROM sms_verdicts WHERE id IN (SELECT id FROM sms_verdicts ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)

    @Query("SELECT * FROM sms_verdicts WHERE id = :id")
    suspend fun getById(id: Long): SmsVerdictEntity?
}
