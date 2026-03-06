package com.ankit.destination.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "always_blocked_apps")
data class AlwaysBlockedApp(
    @PrimaryKey val packageName: String
)
