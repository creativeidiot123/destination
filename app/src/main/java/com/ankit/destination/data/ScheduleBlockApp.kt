package com.ankit.destination.data

import androidx.room.Entity

@Entity(
    tableName = "schedule_block_apps",
    primaryKeys = ["blockId", "packageName"]
)
data class ScheduleBlockApp(
    val blockId: Long,
    val packageName: String
)
