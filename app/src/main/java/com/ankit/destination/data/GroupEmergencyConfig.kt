package com.ankit.destination.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "group_emergency_config")
data class GroupEmergencyConfig(
    @PrimaryKey val groupId: String,
    val enabled: Boolean = false,
    val unlocksPerDay: Int = 0,
    val minutesPerUnlock: Int = 0
)
