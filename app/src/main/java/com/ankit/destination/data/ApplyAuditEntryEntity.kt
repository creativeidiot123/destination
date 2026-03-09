package com.ankit.destination.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "apply_audit_entries")
data class ApplyAuditEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val atMs: Long,
    val triggerSummary: String,
    val desiredSuspendCount: Int,
    val actualVerifiedSuspendCount: Int?,
    val verificationPassed: Boolean,
    val scheduleReason: String?,
    val budgetReason: String?,
    val warning: String?,
    val repairTriggered: Boolean,
    val recoveryPass: Boolean
)
