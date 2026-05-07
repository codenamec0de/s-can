package com.uow.scan.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.uow.scan.data.entity.PermissionAccessEntity

@Dao
interface PermissionAccessDao {

    @Insert
    suspend fun insert(event: PermissionAccessEntity): Long

    @Query("UPDATE permission_access_events SET endedAt = :endedAt WHERE id = :id")
    suspend fun markEnded(id: Long, endedAt: Long)

    @Query("UPDATE permission_access_events SET endedAt = :ts WHERE endedAt IS NULL AND op = :op AND packageName = :pkg")
    suspend fun closeOpen(pkg: String, op: String, ts: Long)

    /**
     * Distinct ops a package was observed using during the window. An access
     * counts as overlapping if it started before windowEnd and either is
     * still open or ended after windowStart.
     */
    @Query("""
        SELECT DISTINCT op FROM permission_access_events
        WHERE packageName = :pkg
        AND startedAt <= :windowEnd
        AND (endedAt IS NULL OR endedAt >= :windowStart)
    """)
    suspend fun opsInWindow(pkg: String, windowStart: Long, windowEnd: Long): List<String>

    /**
     * Same as [opsInWindow], but only counts accesses that began while the app
     * was NOT in the foreground — the cases worth raising an alert about.
     */
    @Query("""
        SELECT DISTINCT op FROM permission_access_events
        WHERE packageName = :pkg
        AND foregroundAtStart = 0
        AND startedAt <= :windowEnd
        AND (endedAt IS NULL OR endedAt >= :windowStart)
    """)
    suspend fun backgroundOpsInWindow(pkg: String, windowStart: Long, windowEnd: Long): List<String>

    @Query("DELETE FROM permission_access_events WHERE startedAt < :before")
    suspend fun pruneOlderThan(before: Long)
}
