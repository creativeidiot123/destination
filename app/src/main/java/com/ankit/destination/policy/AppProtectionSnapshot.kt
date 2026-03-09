package com.ankit.destination.policy

internal enum class PackageProtectionReason {
    USER_ALLOWLIST,
    HIDDEN,
    LOCKED_HIDDEN,
    RUNTIME_EXEMPT,
    CORE_PROTECTED,
    EXCLUDED_FROM_ALL_APPS
}

internal data class AppProtectionSnapshot(
    val allowlistedPackages: Set<String>,
    val hiddenPackages: Set<String>,
    val lockedHiddenPackages: Set<String>,
    val runtimeExemptPackages: Set<String>,
    val runtimeExemptionReasons: Map<String, String>,
    val hardProtectedPackages: Set<String>
) {
    val fullyExemptPackages: Set<String> =
        buildSet {
            addAll(hiddenPackages)
            addAll(runtimeExemptPackages)
            addAll(hardProtectedPackages)
        }

    val allAppsExcludedPackages: Set<String> =
        buildSet {
            addAll(fullyExemptPackages)
            addAll(allowlistedPackages)
        }

    fun isAllowlisted(packageName: String): Boolean {
        val normalized = packageName.trim()
        return normalized.isNotBlank() && allowlistedPackages.contains(normalized)
    }

    fun isHidden(packageName: String): Boolean {
        val normalized = packageName.trim()
        return normalized.isNotBlank() && hiddenPackages.contains(normalized)
    }

    fun isHiddenLocked(packageName: String): Boolean {
        val normalized = packageName.trim()
        return normalized.isNotBlank() && lockedHiddenPackages.contains(normalized)
    }

    fun isRuntimeExempt(packageName: String): Boolean {
        val normalized = packageName.trim()
        return normalized.isNotBlank() && runtimeExemptPackages.contains(normalized)
    }

    fun isCoreProtected(packageName: String): Boolean {
        val normalized = packageName.trim()
        return normalized.isNotBlank() && hardProtectedPackages.contains(normalized)
    }

    fun isFullyExemptFromNormalPolicies(packageName: String): Boolean {
        val normalized = packageName.trim()
        return normalized.isNotBlank() && fullyExemptPackages.contains(normalized)
    }

    fun shouldHideFromStandardLists(packageName: String): Boolean {
        return isHidden(packageName)
    }

    fun isEligibleForManualPolicy(packageName: String): Boolean {
        return !isFullyExemptFromNormalPolicies(packageName)
    }

    fun isEligibleForGroupMembership(packageName: String): Boolean {
        return isEligibleForManualPolicy(packageName)
    }

    fun isEligibleForAllAppsExpansion(packageName: String): Boolean {
        val normalized = packageName.trim()
        return normalized.isNotBlank() && !allAppsExcludedPackages.contains(normalized)
    }

    fun protectionReasons(packageName: String): Set<PackageProtectionReason> {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return emptySet()

        val reasons = linkedSetOf<PackageProtectionReason>()
        if (allowlistedPackages.contains(normalized)) {
            reasons += PackageProtectionReason.USER_ALLOWLIST
            reasons += PackageProtectionReason.EXCLUDED_FROM_ALL_APPS
        }
        if (hiddenPackages.contains(normalized)) {
            reasons += PackageProtectionReason.HIDDEN
            reasons += PackageProtectionReason.EXCLUDED_FROM_ALL_APPS
        }
        if (lockedHiddenPackages.contains(normalized)) {
            reasons += PackageProtectionReason.LOCKED_HIDDEN
        }
        if (runtimeExemptPackages.contains(normalized)) {
            reasons += PackageProtectionReason.RUNTIME_EXEMPT
            reasons += PackageProtectionReason.EXCLUDED_FROM_ALL_APPS
        }
        if (hardProtectedPackages.contains(normalized)) {
            reasons += PackageProtectionReason.CORE_PROTECTED
            reasons += PackageProtectionReason.EXCLUDED_FROM_ALL_APPS
        }
        return reasons
    }

    fun manualPolicyIneligibilityReason(packageName: String): String? {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return null
        return when {
            isHiddenLocked(normalized) -> "Locked hidden app"
            isHidden(normalized) -> "Hidden apps do not participate in normal blocking"
            isRuntimeExempt(normalized) -> runtimeExemptionReasons[normalized]
                ?: "This app is temporarily exempt from normal blocking"
            isCoreProtected(normalized) -> "Core protected apps do not participate in normal blocking"
            else -> null
        }
    }

    fun allAppsExclusionReason(packageName: String): String? {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return null
        return when {
            isHiddenLocked(normalized) -> "Locked hidden app"
            isHidden(normalized) -> "Hidden apps are excluded from all-apps expansion"
            isAllowlisted(normalized) -> "Allowlist apps are excluded from all-apps expansion"
            isRuntimeExempt(normalized) -> runtimeExemptionReasons[normalized]
                ?: "Runtime-exempt apps are excluded from all-apps expansion"
            isCoreProtected(normalized) -> "Core protected apps are excluded from all-apps expansion"
            else -> null
        }
    }
}
