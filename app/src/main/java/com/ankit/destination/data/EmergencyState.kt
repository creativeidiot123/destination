package com.ankit.destination.data

import androidx.room.Entity

@Entity(
    tableName = "emergency_state",
    primaryKeys = ["dayKey", "targetType", "targetId"]
)
data class EmergencyState(
    val dayKey: String,
    val targetType: String,
    val targetId: String,
    val unlocksUsedToday: Int,
    val activeUntilEpochMs: Long?
)
