package com.ankit.destination.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "always_allowed_apps")
data class AlwaysAllowedApp(
    @PrimaryKey val packageName: String
)
