package com.ankit.destination.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hidden_apps")
data class HiddenApp(
    @PrimaryKey val packageName: String,
    val locked: Boolean = false
)
