package com.ankit.destination.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_policy")
data class AppPolicy(
    @PrimaryKey val packageName: String,
    val dailyLimitMs: Long,
    val hourlyLimitMs: Long = Long.MAX_VALUE,
    val opensPerDay: Int = Int.MAX_VALUE,
    val enabled: Boolean = true,
    val emergencyEnabled: Boolean = false,
    val unlocksPerDay: Int = 0,
    val minutesPerUnlock: Int = 0
)
