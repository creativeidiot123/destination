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

    @Query(
        """
        SELECT sb.* FROM schedule_blocks sb
        INNER JOIN schedule_block_groups sbg ON sb.`id` = sbg.`blockId`
        WHERE sbg.`groupId` = :groupId
        ORDER BY sb.`id` ASC
        """
    )
    suspend fun getBlocksForGroup(groupId: String): List<ScheduleBlock>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockGroups(rows: List<ScheduleBlockGroup>)

    @Query("DELETE FROM schedule_block_groups WHERE blockId = :blockId")
    suspend fun deleteGroupsForBlock(blockId: Long)

    @Query("DELETE FROM schedule_block_groups WHERE blockId = :blockId AND groupId = :groupId")
    suspend fun deleteGroupForBlock(blockId: Long, groupId: String)

    @Query("DELETE FROM schedule_block_groups WHERE groupId = :groupId")
    suspend fun deleteBlockMappingsForGroup(groupId: String)

    @Query("SELECT COUNT(*) FROM schedule_block_groups WHERE blockId = :blockId")
    suspend fun countGroupsForBlock(blockId: Long): Int

    @Query("DELETE FROM schedule_block_groups")
    suspend fun deleteAllBlockGroups()

    @Transaction
    suspend fun replaceGroupsForBlock(blockId: Long, groupIds: List<String>) {
        deleteGroupsForBlock(blockId)
        val clean = groupIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return
        insertBlockGroups(clean.map { ScheduleBlockGroup(blockId = blockId, groupId = it) })
    }

    @Query("DELETE FROM schedule_blocks WHERE id = :blockId")
    suspend fun deleteBlockById(blockId: Long)

    @Query("DELETE FROM schedule_blocks")
    suspend fun deleteAllBlocks()

    @Transaction
    suspend fun deleteBlockWithGroups(blockId: Long) {
        deleteGroupsForBlock(blockId)
        deleteBlockById(blockId)
    }

    @Transaction
    suspend fun replaceGroupSchedules(groupId: String, blocks: List<ScheduleBlock>) {
        val normalizedGroupId = groupId.trim()
        if (normalizedGroupId.isBlank()) return
        val existingBlocks = getBlocksForGroup(normalizedGroupId)
        existingBlocks.forEach { existing ->
            deleteGroupForBlock(existing.id, normalizedGroupId)
            if (countGroupsForBlock(existing.id) == 0) {
                deleteBlockById(existing.id)
            }
        }
        blocks.forEach { block ->
            val blockId = upsert(block)
            replaceGroupsForBlock(blockId, listOf(normalizedGroupId))
        }
    }

    @Transaction
    suspend fun clearAllSchedules() {
        deleteAllBlockGroups()
        deleteAllBlocks()
    }

    @Delete
    suspend fun delete(block: ScheduleBlock)
}
