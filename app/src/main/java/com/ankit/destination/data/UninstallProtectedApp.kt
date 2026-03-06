package com.ankit.destination.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "uninstall_protected_apps")
data class UninstallProtectedApp(
    @PrimaryKey val packageName: String
)
