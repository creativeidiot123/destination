package com.ankit.destination.data

enum class GroupTargetMode {
    SELECTED_APPS,
    ALL_APPS;

    companion object {
        fun fromStorage(value: String?): GroupTargetMode {
            return entries.firstOrNull { it.name == value } ?: SELECTED_APPS
        }
    }
}
