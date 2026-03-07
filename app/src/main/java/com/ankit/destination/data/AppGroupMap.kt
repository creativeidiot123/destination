package com.ankit.destination.data

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "app_group_map",
    primaryKeys = ["packageName", "groupId"],
    indices = [Index(value = ["groupId"])]
)
data class AppGroupMap(
    val packageName: String,
    val groupId: String
)
