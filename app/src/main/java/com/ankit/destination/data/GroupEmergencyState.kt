package com.ankit.destination.data

import androidx.room.Entity

@Entity(
    tableName = "group_emergency_state",
    primaryKeys = ["dayKey", "groupId"]
)
data class GroupEmergencyState(
    val dayKey: String,
    val groupId: String,
    val unlocksUsedToday: Int,
    val activeUntilEpochMs: Long?
)
