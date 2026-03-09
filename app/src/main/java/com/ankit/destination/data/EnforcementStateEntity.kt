package com.ankit.destination.data

import com.ankit.destination.policy.UsageSnapshotStatus
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "enforcement_state")
data class EnforcementStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val scheduleLockComputed: Boolean = false,
    val scheduleLockEnforced: Boolean = false,
    val scheduleStrictComputed: Boolean = false,
    val scheduleStrictEnforced: Boolean = false,
    val scheduleBlockedGroupsEncoded: String = "",
    val scheduleBlockedPackagesEncoded: String = "",
    val strictInstallSuspendedPackagesEncoded: String = "",
    val scheduleLockReason: String? = null,
    val scheduleTargetWarning: String? = null,
    val scheduleTargetDiagnosticCode: String = "NONE",
    val scheduleNextTransitionAtMs: Long? = null,
    val budgetBlockedPackagesEncoded: String = "",
    val budgetBlockedGroupIdsEncoded: String = "",
    val budgetReason: String? = null,
    val budgetUsageSnapshotStatus: String = UsageSnapshotStatus.ACCESS_MISSING.name,
    val budgetUsageAccessGranted: Boolean = false,
    val budgetNextCheckAtMs: Long? = null,
    val nextPolicyWakeAtMs: Long? = null,
    val nextPolicyWakeReason: String? = null,
    val primaryReasonByPackageEncoded: String = "",
    val blockReasonsByPackageEncoded: String = "",
    val lastSuspendedPackagesEncoded: String = "",
    val lastUninstallProtectedPackagesEncoded: String = "",
    val lastAppliedAtMs: Long = 0L,
    val lastVerificationPassed: Boolean = false,
    val lastVerificationIssuesEncoded: String = "",
    val lastError: String? = null,
    val lastSuccessfulApplyAtMs: Long = 0L,
    val computedSnapshotVersion: Long = 0L,
    val integrityFindingsEncoded: String = "",
    val lastIntegrityAuditAtMs: Long = 0L,
    val startupRecoveryAtMs: Long = 0L,
    val startupRecoveryTriggerSummary: String? = null,
    val startupRecoveryStatus: String? = null,
    val startupRecoveryDetail: String? = null
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
