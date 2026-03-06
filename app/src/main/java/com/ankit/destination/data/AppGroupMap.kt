package com.ankit.destination.data

import androidx.room.Entity

@Entity(
    tableName = "app_group_map",
    primaryKeys = ["packageName", "groupId"]
)
data class AppGroupMap(
    val packageName: String,
    val groupId: String
)
