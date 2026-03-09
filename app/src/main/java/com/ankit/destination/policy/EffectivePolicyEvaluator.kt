package com.ankit.destination.policy

import com.ankit.destination.data.EmergencyTargetType
import com.ankit.destination.data.GroupTargetMode

enum class EffectiveBlockReason {
    NONE,
    SCHEDULED_BLOCK,
    HOURLY_CAP,
    DAILY_CAP,
    OPENS_CAP,
    STRICT_INSTALL,
    ALWAYS_BLOCKED,
    USAGE_ACCESS_RECOVERY_LOCKDOWN,
    ACCESSIBILITY_RECOVERY_LOCKDOWN
}

data class EmergencyConfigInput(
    val enabled: Boolean,
    val unlocksPerDay: Int,
    val minutesPerUnlock: Int
)

data class EmergencyStateInput(
    val targetType: EmergencyTargetType,
    val targetId: String,
    val unlocksUsedToday: Int,
    val activeUntilEpochMs: Long?
)

data class UsageInputs(
    val usedTodayMs: Map<String, Long>,
    val usedHourMs: Map<String, Long>,
    val opensToday: Map<String, Int>
)

data class GroupPolicyInput(
    val groupId: String,
    val priorityIndex: Int,
    val strictInstallParticipates: Boolean,
    val targetMode: GroupTargetMode = GroupTargetMode.SELECTED_APPS,
    val dailyLimitMs: Long?,
    val hourlyLimitMs: Long?,
    val opensPerDay: Int?,
    val members: Set<String>,
    val emergencyConfig: EmergencyConfigInput,
    val scheduleBlocked: Boolean
)

data class AppPolicyInput(
    val packageName: String,
    val dailyLimitMs: Long?,
    val hourlyLimitMs: Long?,
    val opensPerDay: Int?,
    val emergencyConfig: EmergencyConfigInput,
    val scheduleBlocked: Boolean
)

data class GroupPolicyEvaluation(
    val groupId: String,
    val priorityIndex: Int,
    val members: Set<String>,
    val resolvedUsageTargets: Set<String>,
    val scheduleTargets: Set<String>,
    val strictInstallTargets: Set<String>,
    val scheduleReasonToken: String,
    val baselineReason: EffectiveBlockReason,
    val baselineBlocked: Boolean,
    val strictInstallActive: Boolean,
    val emergencyAvailable: Boolean,
    val emergencyRemainingUnlocks: Int,
    val emergencyActiveUntilEpochMs: Long?,
    val effectiveBlocked: Boolean
)

data class AppPolicyEvaluation(
    val packageName: String,
    val baselineReason: EffectiveBlockReason,
    val baselineBlocked: Boolean,
    val emergencyAvailable: Boolean,
    val emergencyRemainingUnlocks: Int,
    val emergencyActiveUntilEpochMs: Long?,
    val effectiveBlocked: Boolean
)

data class EffectivePolicyEvaluation(
    val groupEvaluations: List<GroupPolicyEvaluation>,
    val appEvaluations: List<AppPolicyEvaluation>,
    val scheduledBlockedPackages: Set<String>,
    val usageBlockedPackages: Set<String>,
    val strictInstallBlockedPackages: Set<String>,
    val effectiveBlockedPackages: Set<String>,
    val effectiveBlockedGroupIds: Set<String>,
    val activeAllAppsGroupIds: Set<String>,
    val strictInstallActiveGroupIds: Set<String>,
    val blockReasonsByPackage: Map<String, Set<String>>,
    val primaryReasonByPackage: Map<String, String>,
    val usageReasonSummary: String?
)

object EffectivePolicyEvaluator {
    fun evaluate(
        nowMs: Long,
        usageInputs: UsageInputs,
        groupPolicies: List<GroupPolicyInput>,
        appPolicies: List<AppPolicyInput>,
        emergencyStates: List<EmergencyStateInput>,
        strictInstallBlockedPackages: Set<String>,
        fullyExemptPackages: Set<String>,
        allAppsExcludedPackages: Set<String>,
        installedTargetablePackages: Set<String> = emptySet(),
        hardProtectedPackages: Set<String> = emptySet()
    ): EffectivePolicyEvaluation {
        FocusLog.d(FocusEventId.GROUP_EVAL, "â”Œâ”€â”€ EffectivePolicyEvaluator.evaluate() START â”€â”€")
        FocusLog.d(FocusEventId.GROUP_EVAL, "â”‚ groups=${groupPolicies.size} apps=${appPolicies.size} emergencyStates=${emergencyStates.size} strictInstall=${strictInstallBlockedPackages.size} fullyExempt=${fullyExemptPackages.size} allAppsExcluded=${allAppsExcludedPackages.size}")
        FocusLog.d(FocusEventId.GROUP_EVAL, "â”‚ installedTargetable=${installedTargetablePackages.size} hardProtected=${hardProtectedPackages.size}")
        FocusLog.d(FocusEventId.USAGE_READ, "â”‚ usageTodayMs entries=${usageInputs.usedTodayMs.size} usedHourMs entries=${usageInputs.usedHourMs.size} opensToday entries=${usageInputs.opensToday.size}")

        val stateByKey = emergencyStates.associateBy { it.targetType.name to it.targetId.trim() }
        if (emergencyStates.isNotEmpty()) {
            emergencyStates.forEach { es ->
                val remainMs = es.activeUntilEpochMs?.let { it - nowMs }
                FocusLog.d(FocusEventId.BUDGET_EMERGENCY, "â”‚ emergencyState: type=${es.targetType} id=${es.targetId} unlocksUsed=${es.unlocksUsedToday} activeUntil=${es.activeUntilEpochMs} remainMs=$remainMs")
            }
        }

        val sortedGroups = groupPolicies.sortedWith(compareBy<GroupPolicyInput> { it.priorityIndex }.thenBy { it.groupId })

        val groupEvaluations = sortedGroups.mapNotNull { group ->
            val cleanGroupId = group.groupId.trim()
            if (cleanGroupId.isBlank()) return@mapNotNull null
            val members = normalizeConfiguredMembers(group.members)
            val resolvedTargets = resolveGroupTargets(
                members = members,
                targetMode = group.targetMode,
                fullyExemptPackages = fullyExemptPackages,
                allAppsExcludedPackages = allAppsExcludedPackages,
                installedTargetablePackages = installedTargetablePackages,
                hardProtectedPackages = hardProtectedPackages
            )
            if (resolvedTargets.resolvedUsageTargets.isEmpty() && resolvedTargets.resolvedScheduleTargets.isEmpty()) {
                FocusLog.v(FocusEventId.GROUP_EVAL, "â”‚ group=$cleanGroupId SKIPPED (no resolved usage or schedule targets)")
                return@mapNotNull null
            }

            val usedHour = resolvedTargets.resolvedUsageTargets.sumOf { usageInputs.usedHourMs[it] ?: 0L }
            val usedDay = resolvedTargets.resolvedUsageTargets.sumOf { usageInputs.usedTodayMs[it] ?: 0L }
            val opens = resolvedTargets.resolvedUsageTargets.sumOf { usageInputs.opensToday[it] ?: 0 }
            val baselineReason = when {
                group.scheduleBlocked -> EffectiveBlockReason.SCHEDULED_BLOCK
                group.hourlyLimitMs != null && usedHour >= group.hourlyLimitMs -> EffectiveBlockReason.HOURLY_CAP
                group.dailyLimitMs != null && usedDay >= group.dailyLimitMs -> EffectiveBlockReason.DAILY_CAP
                group.opensPerDay != null && opens >= group.opensPerDay -> EffectiveBlockReason.OPENS_CAP
                else -> EffectiveBlockReason.NONE
            }
            val baselineBlocked = baselineReason != EffectiveBlockReason.NONE
            val state = stateByKey[EmergencyTargetType.GROUP.name to cleanGroupId]
            val emergencyActive = state?.activeUntilEpochMs?.let { it > nowMs } == true
            val remainingUnlocks = (group.emergencyConfig.unlocksPerDay - (state?.unlocksUsedToday ?: 0)).coerceAtLeast(0)

            val usedDayMin = usedDay / 60_000L
            val usedHourMin = usedHour / 60_000L
            val dailyLimitLabel = group.dailyLimitMs?.let { "${it / 60_000L}min" } ?: "disabled"
            val hourlyLimitLabel = group.hourlyLimitMs?.let { "${it / 60_000L}min" } ?: "disabled"
            val opensLimitLabel = group.opensPerDay?.toString() ?: "disabled"
            FocusLog.d(FocusEventId.GROUP_EVAL, "â”‚ â”Œâ”€ GROUP: $cleanGroupId (priority=${group.priorityIndex}) members=${members.size} strictInstallParticipates=${group.strictInstallParticipates} schedBlocked=${group.scheduleBlocked}")
            if (group.scheduleBlocked) {
                FocusLog.d(FocusEventId.GROUP_EVAL, "â”‚ â”‚ scheduleTargetMode=${group.targetMode} scheduleTargets=${resolvedTargets.resolvedScheduleTargets.size}")
            }
            FocusLog.d(FocusEventId.GROUP_EVAL, "â”‚ â”‚ usage: dayUsed=${usedDayMin}min/$dailyLimitLabel hourUsed=${usedHourMin}min/$hourlyLimitLabel opens=$opens/$opensLimitLabel")
            FocusLog.d(FocusEventId.GROUP_EVAL, "â”‚ â”‚ baseline=${baselineReason.name} blocked=$baselineBlocked")
            FocusLog.d(FocusEventId.GROUP_EVAL, "â”‚ â”‚ emergency: enabled=${group.emergencyConfig.enabled} active=$emergencyActive remainingUnlocks=$remainingUnlocks untilMs=${state?.activeUntilEpochMs}")
            val effectiveBlocked = baselineBlocked &&
                !emergencyActive &&
                when (baselineReason) {
                    EffectiveBlockReason.SCHEDULED_BLOCK -> resolvedTargets.resolvedScheduleTargets.isNotEmpty()
                    else -> resolvedTargets.resolvedUsageTargets.isNotEmpty()
                }
            FocusLog.d(FocusEventId.GROUP_EVAL, "â”‚ â””â”€ EFFECTIVE: ${if (effectiveBlocked) "ðŸ”´ BLOCKED" else "ðŸŸ¢ ALLOWED"} scheduleTargets=${resolvedTargets.resolvedScheduleTargets.size} usageTargets=${resolvedTargets.resolvedUsageTargets.size}")

            GroupPolicyEvaluation(
                groupId = cleanGroupId,
                priorityIndex = group.priorityIndex,
                members = members,
                resolvedUsageTargets = resolvedTargets.resolvedUsageTargets,
                scheduleTargets = resolvedTargets.resolvedScheduleTargets,
                strictInstallTargets = resolvedTargets.strictInstallTargets,
                scheduleReasonToken = if (group.targetMode == GroupTargetMode.ALL_APPS) {
                    "GROUP:${cleanGroupId}:SCHEDULED_BLOCK_ALL_APPS"
                } else {
                    "GROUP:${cleanGroupId}:SCHEDULED_BLOCK"
                },
                baselineReason = baselineReason,
                baselineBlocked = baselineBlocked,
                strictInstallActive = group.scheduleBlocked && group.strictInstallParticipates,
                emergencyAvailable = baselineBlocked && group.emergencyConfig.enabled && remainingUnlocks > 0 && !emergencyActive,
                emergencyRemainingUnlocks = remainingUnlocks,
                emergencyActiveUntilEpochMs = state?.activeUntilEpochMs,
                effectiveBlocked = effectiveBlocked
            )
        }

        val appEvaluations = appPolicies.mapNotNull { policy ->
            val packageName = policy.packageName.trim()
            if (packageName.isBlank() || fullyExemptPackages.contains(packageName)) {
                if (fullyExemptPackages.contains(packageName)) {
                    FocusLog.v(FocusEventId.APP_EVAL, "â”‚ app=$packageName SKIPPED (fully exempt)")
                }
                return@mapNotNull null
            }
            val usedHourMs = usageInputs.usedHourMs[packageName] ?: 0L
            val usedDayMs = usageInputs.usedTodayMs[packageName] ?: 0L
            val opensCount = usageInputs.opensToday[packageName] ?: 0
            val baselineReason = when {
                policy.scheduleBlocked -> EffectiveBlockReason.SCHEDULED_BLOCK
                policy.hourlyLimitMs != null && usedHourMs >= policy.hourlyLimitMs ->
                    EffectiveBlockReason.HOURLY_CAP
                policy.dailyLimitMs != null && usedDayMs >= policy.dailyLimitMs ->
                    EffectiveBlockReason.DAILY_CAP
                policy.opensPerDay != null && opensCount >= policy.opensPerDay ->
                    EffectiveBlockReason.OPENS_CAP
                else -> EffectiveBlockReason.NONE
            }
            val baselineBlocked = baselineReason != EffectiveBlockReason.NONE
            val state = stateByKey[EmergencyTargetType.APP.name to packageName]
            val emergencyActive = state?.activeUntilEpochMs?.let { it > nowMs } == true
            val remainingUnlocks = (policy.emergencyConfig.unlocksPerDay - (state?.unlocksUsedToday ?: 0)).coerceAtLeast(0)

            val usedDayMin = usedDayMs / 60_000L
            val usedHourMin = usedHourMs / 60_000L
            val dailyLimitLabel = policy.dailyLimitMs?.let { "${it / 60_000L}min" } ?: "disabled"
            val hourlyLimitLabel = policy.hourlyLimitMs?.let { "${it / 60_000L}min" } ?: "disabled"
            val opensLimitLabel = policy.opensPerDay?.toString() ?: "disabled"
            FocusLog.d(FocusEventId.APP_EVAL, "â”‚ â”Œâ”€ APP: $packageName schedBlocked=${policy.scheduleBlocked}")
            FocusLog.d(FocusEventId.APP_EVAL, "â”‚ â”‚ usage: dayUsed=${usedDayMin}min/$dailyLimitLabel hourUsed=${usedHourMin}min/$hourlyLimitLabel opens=$opensCount/$opensLimitLabel")
            FocusLog.d(FocusEventId.APP_EVAL, "â”‚ â”‚ baseline=${baselineReason.name} blocked=$baselineBlocked")
            FocusLog.d(FocusEventId.APP_EVAL, "â”‚ â”‚ emergency: enabled=${policy.emergencyConfig.enabled} active=$emergencyActive remainingUnlocks=$remainingUnlocks")
            val effectiveBlocked = baselineBlocked && !emergencyActive
            FocusLog.d(FocusEventId.APP_EVAL, "â”‚ â””â”€ EFFECTIVE: ${if (effectiveBlocked) "ðŸ”´ BLOCKED" else "ðŸŸ¢ ALLOWED"}")

            AppPolicyEvaluation(
                packageName = packageName,
                baselineReason = baselineReason,
                baselineBlocked = baselineBlocked,
                emergencyAvailable = baselineBlocked && policy.emergencyConfig.enabled && remainingUnlocks > 0 && !emergencyActive,
                emergencyRemainingUnlocks = remainingUnlocks,
                emergencyActiveUntilEpochMs = state?.activeUntilEpochMs,
                effectiveBlocked = effectiveBlocked
            )
        }

        val scheduledBlocked = linkedSetOf<String>()
        val usageBlocked = linkedSetOf<String>()
        val blockReasons = linkedMapOf<String, LinkedHashSet<String>>()
        val effectiveGroupIds = linkedSetOf<String>()
        val activeAllAppsGroupIds = linkedSetOf<String>()
        val primaryReason = linkedMapOf<String, String>()

        fun addReason(pkg: String, reason: String) {
            val normalized = pkg.trim()
            if (normalized.isBlank() || reason.isBlank()) return
            blockReasons.getOrPut(normalized) { linkedSetOf() }.add(reason)
        }

        groupEvaluations.forEach { evaluation ->
            if (!evaluation.effectiveBlocked) return@forEach
            effectiveGroupIds += evaluation.groupId
            if (evaluation.scheduleReasonToken.endsWith("SCHEDULED_BLOCK_ALL_APPS")) {
                activeAllAppsGroupIds += evaluation.groupId
            }
            when (evaluation.baselineReason) {
                EffectiveBlockReason.SCHEDULED_BLOCK -> scheduledBlocked += evaluation.scheduleTargets
                EffectiveBlockReason.HOURLY_CAP,
                EffectiveBlockReason.DAILY_CAP,
                EffectiveBlockReason.OPENS_CAP -> usageBlocked += evaluation.resolvedUsageTargets
                else -> Unit
            }
            val reasonTargets = when (evaluation.baselineReason) {
                EffectiveBlockReason.SCHEDULED_BLOCK -> evaluation.scheduleTargets
                else -> evaluation.resolvedUsageTargets
            }
            reasonTargets.forEach { pkg ->
                addReason(
                    pkg = pkg,
                    reason = if (evaluation.baselineReason == EffectiveBlockReason.SCHEDULED_BLOCK) {
                        evaluation.scheduleReasonToken
                    } else {
                        "GROUP:${evaluation.groupId}:${evaluation.baselineReason.name}"
                    }
                )
            }
        }

        appEvaluations.forEach { evaluation ->
            if (!evaluation.effectiveBlocked) return@forEach
            if (evaluation.baselineReason == EffectiveBlockReason.SCHEDULED_BLOCK) {
                scheduledBlocked += evaluation.packageName
            } else {
                usageBlocked += evaluation.packageName
            }
            addReason(pkg = evaluation.packageName, reason = "APP:${evaluation.baselineReason.name}")
        }

        val normalizedStrictInstall = strictInstallBlockedPackages
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot(fullyExemptPackages::contains)
            .toSet()

        normalizedStrictInstall.forEach { pkg ->
            addReason(pkg = pkg, reason = EffectiveBlockReason.STRICT_INSTALL.name)
        }

        val effectiveBlockedPackages = linkedSetOf<String>().apply {
            addAll(scheduledBlocked)
            addAll(usageBlocked)
            addAll(normalizedStrictInstall)
        }
        val reasonSummary = groupEvaluations.firstOrNull { it.effectiveBlocked }?.let { "${it.groupId}:${it.baselineReason.name}" }
            ?: appEvaluations.firstOrNull { it.effectiveBlocked }?.let { "${it.packageName}:${it.baselineReason.name}" }

        FocusLog.d(FocusEventId.GROUP_EVAL, "â”‚ â”€â”€ SUMMARY â”€â”€")
        FocusLog.d(FocusEventId.GROUP_EVAL, "â”‚ scheduledBlocked=${scheduledBlocked.size} usageBlocked=${usageBlocked.size} strictInstall=${normalizedStrictInstall.size}")
        FocusLog.d(FocusEventId.GROUP_EVAL, "â”‚ effectiveBlockedTotal=${effectiveBlockedPackages.size} blockedGroupIds=${effectiveGroupIds.size}")
        if (effectiveBlockedPackages.isNotEmpty()) {
            FocusLog.d(FocusEventId.GROUP_EVAL, "â”‚ blockedPkgs=${effectiveBlockedPackages.joinToString(",")}")
        }
        if (blockReasons.isNotEmpty()) {
            BlockReasonUtils.derivePrimaryByPackage(blockReasons).forEach { (pkg, reason) ->
                FocusLog.v(FocusEventId.GROUP_EVAL, "â”‚ reason: $pkg â†’ $reason")
            }
        }
        FocusLog.d(FocusEventId.GROUP_EVAL, "â””â”€â”€ EffectivePolicyEvaluator.evaluate() END â”€â”€")

        return EffectivePolicyEvaluation(
            groupEvaluations = groupEvaluations,
            appEvaluations = appEvaluations,
            scheduledBlockedPackages = scheduledBlocked,
            usageBlockedPackages = usageBlocked,
            strictInstallBlockedPackages = normalizedStrictInstall,
            effectiveBlockedPackages = effectiveBlockedPackages,
            effectiveBlockedGroupIds = effectiveGroupIds,
            activeAllAppsGroupIds = activeAllAppsGroupIds,
            strictInstallActiveGroupIds = groupEvaluations.filter { it.strictInstallActive }.mapTo(linkedSetOf()) { it.groupId },
            blockReasonsByPackage = blockReasons.mapValues { (_, value) -> value.toSet() },
            primaryReasonByPackage = BlockReasonUtils.derivePrimaryByPackage(blockReasons),
            usageReasonSummary = reasonSummary
        )
    }

    data class ResolvedGroupTargets(
        val resolvedUsageTargets: Set<String>,
        val resolvedScheduleTargets: Set<String>,
        val strictInstallTargets: Set<String>
    )

    internal fun normalizeConfiguredMembers(members: Set<String>): Set<String> {
        return members.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toCollection(linkedSetOf())
    }

    internal fun resolveGroupTargets(
        members: Set<String>,
        targetMode: GroupTargetMode,
        fullyExemptPackages: Set<String>,
        allAppsExcludedPackages: Set<String>,
        installedTargetablePackages: Set<String>,
        hardProtectedPackages: Set<String>
    ): ResolvedGroupTargets {
        val selectedTargets = members
            .asSequence()
            .filterNot(fullyExemptPackages::contains)
            .toCollection(linkedSetOf())
        val allAppsTargets = installedTargetablePackages
            .asSequence()
            .filterNot(hardProtectedPackages::contains)
            .filterNot(fullyExemptPackages::contains)
            .filterNot(allAppsExcludedPackages::contains)
            .toCollection(linkedSetOf())
        val resolvedTargets = when (targetMode) {
            GroupTargetMode.SELECTED_APPS -> selectedTargets
            GroupTargetMode.ALL_APPS -> allAppsTargets
        }
        return ResolvedGroupTargets(
            resolvedUsageTargets = resolvedTargets,
            resolvedScheduleTargets = resolvedTargets,
            strictInstallTargets = resolvedTargets
        )
    }
}

