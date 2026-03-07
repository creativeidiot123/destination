package com.ankit.destination.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "schedule_blocks")
data class ScheduleBlock(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    // bit0=Mon ... bit6=Sun
    val daysMask: Int,
    // 0..1439
    val startMinute: Int,
    // 0..1439; if end < start => cross-midnight
    val endMinute: Int,
    val enabled: Boolean = true,
    val kind: String = ScheduleBlockKind.GROUPS.name,
    val strict: Boolean = false,
    val immutable: Boolean = false,
    val timezoneMode: String = ScheduleTimezoneMode.DEVICE_LOCAL.name
) {
    fun isCrossMidnight(): Boolean = endMinute < startMinute
}
