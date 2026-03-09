package com.ankit.destination.policy

data class PolicyUiSnapshot(
    val scheduleBlockedGroups: Set<String>,
    val budgetBlockedPackages: Set<String>,
    val budgetBlockedGroupIds: Set<String>,
    val lastSuspendedPackages: Set<String>,
    val primaryReasonByPackage: Map<String, String>,
    val scheduleLockReason: String?,
    val budgetReason: String?,
    val currentLockReason: String?
)
