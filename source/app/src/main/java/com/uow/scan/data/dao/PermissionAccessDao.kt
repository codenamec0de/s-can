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

    /**
     * Returns full event rows that overlap [windowStart, windowEnd], ordered by
     * start time. Use this when the caller needs the *timeline* (timestamps and
     * durations of each access) — `opsInWindow` only returns distinct op names.
     */
    @Query("""
        SELECT * FROM permission_access_events
        WHERE packageName = :pkg
        AND startedAt <= :windowEnd
        AND (endedAt IS NULL OR endedAt >= :windowStart)
        ORDER BY startedAt ASC
    """)
    suspend fun accessesInWindow(
        pkg: String,
        windowStart: Long,
        windowEnd: Long
    ): List<PermissionAccessEntity>

    /** Most recent access events across ALL packages — drives the Home "Needs attention" feed. */
    @Query("""
        SELECT * FROM permission_access_events
        WHERE startedAt >= :since
        ORDER BY startedAt DESC
        LIMIT :limit
    """)
    suspend fun recentAccesses(since: Long, limit: Int = 20): List<PermissionAccessEntity>

    /**
     * Packages observed accessing a sensor while in the BACKGROUND since [since]. This is the
     * "real finding" signal that escalates an app's risk to HIGH (see AppScanner.effectiveRisk).
     */
    @Query("""
        SELECT DISTINCT packageName FROM permission_access_events
        WHERE foregroundAtStart = 0 AND startedAt >= :since
    """)
    suspend fun packagesWithBackgroundAccess(since: Long): List<String>

    @Query("DELETE FROM permission_access_events WHERE startedAt < :before")
    suspend fun pruneOlderThan(before: Long)
}
