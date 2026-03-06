package com.ankit.destination.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "global_controls")
data class GlobalControls(
    @PrimaryKey val id: Int = 1,
    val lockTime: Boolean = false,
    val lockVpnDns: Boolean = true,
    val lockDevOptions: Boolean = false,
    val lockUserCreation: Boolean = false,
    val lockWorkProfile: Boolean = false,
    val lockCloningBestEffort: Boolean = false,
    val dangerUnenrollEnabled: Boolean = false
)
