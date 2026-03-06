package com.ankit.destination.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedule_blocks ORDER BY id ASC")
    suspend fun getAllBlocks(): List<ScheduleBlock>

    @Query("SELECT * FROM schedule_blocks WHERE enabled = 1")
    suspend fun getEnabledBlocks(): List<ScheduleBlock>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(block: ScheduleBlock): Long

    @Query("SELECT * FROM schedule_block_groups")
    suspend fun getAllBlockGroups(): List<ScheduleBlockGroup>

    @Query("SELECT groupId FROM schedule_block_groups WHERE blockId = :blockId ORDER BY groupId ASC")
    suspend fun getGroupsForBlock(blockId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockGroups(rows: List<ScheduleBlockGroup>)

    @Query("DELETE FROM schedule_block_groups WHERE blockId = :blockId")
    suspend fun deleteGroupsForBlock(blockId: Long)

    @Transaction
    suspend fun replaceGroupsForBlock(blockId: Long, groupIds: List<String>) {
        deleteGroupsForBlock(blockId)
        val clean = groupIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return
        insertBlockGroups(clean.map { ScheduleBlockGroup(blockId = blockId, groupId = it) })
    }

    @Query("DELETE FROM schedule_blocks WHERE id = :blockId")
    suspend fun deleteBlockById(blockId: Long)

    @Transaction
    suspend fun deleteBlockWithGroups(blockId: Long) {
        deleteGroupsForBlock(blockId)
        deleteBlockById(blockId)
    }

    @Delete
    suspend fun delete(block: ScheduleBlock)
}
