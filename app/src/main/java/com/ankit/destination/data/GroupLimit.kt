package com.ankit.destination.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "group_limits")
data class GroupLimit(
    @PrimaryKey val groupId: String,
    val name: String,
    val priorityIndex: Int = 1000,
    val dailyLimitMs: Long,
    val hourlyLimitMs: Long,
    val opensPerDay: Int,
    val enabled: Boolean = true
)
