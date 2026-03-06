package com.ankit.destination.data

import androidx.room.Entity

@Entity(
    tableName = "schedule_block_groups",
    primaryKeys = ["blockId", "groupId"]
)
data class ScheduleBlockGroup(
    val blockId: Long,
    val groupId: String
)
