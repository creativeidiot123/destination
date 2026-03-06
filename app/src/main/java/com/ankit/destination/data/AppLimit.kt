package com.ankit.destination.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_limits")
data class AppLimit(
    @PrimaryKey val packageName: String,
    val dailyLimitMs: Long,
    val enabled: Boolean = true
)
