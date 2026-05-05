package com.uow.scan.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.uow.scan.data.entity.WeeklyStatsEntity

@Dao
interface WeeklyStatsDao {

    @Query("SELECT * FROM weekly_stats ORDER BY weekStartMs DESC")
    suspend fun getAll(): List<WeeklyStatsEntity>

    @Query("SELECT MAX(weekStartMs) FROM weekly_stats")
    suspend fun getLatestWeekStart(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WeeklyStatsEntity)

    @Query("DELETE FROM weekly_stats")
    suspend fun clearAll()
}
