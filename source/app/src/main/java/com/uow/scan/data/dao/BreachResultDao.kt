package com.uow.scan.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.uow.scan.data.entity.BreachResultEntity

@Dao
interface BreachResultDao {

    @Query("SELECT * FROM breach_results WHERE email = :email ORDER BY checkedAt DESC")
    suspend fun getByEmail(email: String): List<BreachResultEntity>

    @Query("SELECT * FROM breach_results ORDER BY checkedAt DESC")
    suspend fun getAll(): List<BreachResultEntity>

    @Insert
    suspend fun insertAll(results: List<BreachResultEntity>)

    @Query("DELETE FROM breach_results WHERE email = :email")
    suspend fun deleteByEmail(email: String)

    @Query("SELECT MAX(checkedAt) FROM breach_results WHERE email = :email")
    suspend fun getLastCheckTime(email: String): Long?

    @Query("DELETE FROM breach_results")
    suspend fun clearAll()

    @Query("UPDATE breach_results SET resolved = :resolved, resolvedAt = :resolvedAt WHERE id = :id")
    suspend fun updateResolved(id: Long, resolved: Boolean, resolvedAt: Long?)
}
