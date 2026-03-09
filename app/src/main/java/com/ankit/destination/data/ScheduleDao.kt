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

    @Query("SELECT * FROM schedule_block_apps")
    suspend fun getAllBlockApps(): List<ScheduleBlockApp>

    @Query(
        """
        SELECT sbg.* FROM schedule_block_groups sbg
        INNER JOIN schedule_blocks sb ON sb.`id` = sbg.`blockId`
        WHERE sb.enabled = 1
        ORDER BY sbg.`blockId` ASC, sbg.`groupId` ASC
        """
    )
    suspend fun getEnabledBlockGroups(): List<ScheduleBlockGroup>

    @Query(
        """
        SELECT sba.* FROM schedule_block_apps sba
        INNER JOIN schedule_blocks sb ON sb.`id` = sba.`blockId`
        WHERE sb.enabled = 1
        ORDER BY sba.`blockId` ASC, sba.`packageName` ASC
        """
    )
    suspend fun getEnabledBlockApps(): List<ScheduleBlockApp>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockApps(rows: List<ScheduleBlockApp>)

    @Query("DELETE FROM schedule_block_groups WHERE blockId = :blockId")
    suspend fun deleteGroupsForBlock(blockId: Long)

    @Query("DELETE FROM schedule_block_apps WHERE blockId = :blockId")
    suspend fun deleteAppsForBlock(blockId: Long)

    @Query("DELETE FROM schedule_block_groups WHERE blockId = :blockId AND groupId = :groupId")
    suspend fun deleteGroupForBlock(blockId: Long, groupId: String)

    @Query("DELETE FROM schedule_block_groups WHERE groupId = :groupId")
    suspend fun deleteBlockMappingsForGroup(groupId: String)

    @Query("SELECT COUNT(*) FROM schedule_block_groups WHERE blockId = :blockId")
    suspend fun countGroupsForBlock(blockId: Long): Int

    @Query("DELETE FROM schedule_block_groups")
    suspend fun deleteAllBlockGroups()

    @Query("DELETE FROM schedule_block_apps")
    suspend fun deleteAllBlockApps()

    @Transaction
    suspend fun replaceGroupsForBlock(blockId: Long, groupIds: List<String>) {
        deleteGroupsForBlock(blockId)
        val clean = groupIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return
        insertBlockGroups(clean.map { ScheduleBlockGroup(blockId = blockId, groupId = it) })
    }

    @Transaction
    suspend fun replaceAppsForBlock(blockId: Long, packageNames: List<String>) {
        deleteAppsForBlock(blockId)
        val clean = packageNames.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return
        insertBlockApps(clean.map { ScheduleBlockApp(blockId = blockId, packageName = it) })
    }

    @Query("DELETE FROM schedule_blocks WHERE id = :blockId")
    suspend fun deleteBlockById(blockId: Long)

    @Query("DELETE FROM schedule_blocks")
    suspend fun deleteAllBlocks()

    @Transaction
    suspend fun deleteBlockWithGroups(blockId: Long) {
        deleteGroupsForBlock(blockId)
        deleteAppsForBlock(blockId)
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
        deleteAllBlockApps()
        deleteAllBlocks()
    }

    @Transaction
    suspend fun getEnabledScheduleSnapshot(): EnabledScheduleSnapshot {
        return EnabledScheduleSnapshot(
            blocks = getEnabledBlocks(),
            blockGroups = getEnabledBlockGroups(),
            blockApps = getEnabledBlockApps()
        )
    }

    @Delete
    suspend fun delete(block: ScheduleBlock)
}

data class EnabledScheduleSnapshot(
    val blocks: List<ScheduleBlock>,
    val blockGroups: List<ScheduleBlockGroup>,
    val blockApps: List<ScheduleBlockApp>
)
