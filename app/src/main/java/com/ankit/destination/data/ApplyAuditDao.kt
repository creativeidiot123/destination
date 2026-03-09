package com.ankit.destination.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ApplyAuditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ApplyAuditEntryEntity): Long

    @Query("SELECT * FROM apply_audit_entries ORDER BY atMs DESC, id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ApplyAuditEntryEntity>

    @Query(
        """
        DELETE FROM apply_audit_entries
        WHERE id NOT IN (
            SELECT id FROM apply_audit_entries
            ORDER BY atMs DESC, id DESC
            LIMIT :keepCount
        )
        """
    )
    suspend fun trimToLatest(keepCount: Int)
}
