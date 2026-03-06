package com.ankit.destination.data

import androidx.room.Entity

@Entity(
    tableName = "usage_snapshots",
    primaryKeys = ["windowKey", "packageName"]
)
data class UsageSnapshot(
    val windowKey: String,
    val packageName: String,
    val foregroundMs: Long,
    val opens: Int
)
