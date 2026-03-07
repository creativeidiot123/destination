package com.ankit.destination.policy

import android.content.Context

class PolicyStore(context: Context) {
    private val storageContext = context.createDeviceProtectedStorageContext()
    private val prefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        if (!didAttemptMigration) {
            synchronized(PolicyStore::class.java) {
                if (!didAttemptMigration) {
                    runCatching { storageContext.moveSharedPreferencesFrom(context, PREFS_NAME) }
                    didAttemptMigration = true
                }
            }
        }
    }

    fun getDesiredMode(): ModeState {
        val raw = prefs.getString(KEY_MODE, ModeState.NORMAL.name) ?: ModeState.NORMAL.name
        return normalizeSupportedMode(raw)
    }

    fun setDesiredMode(mode: ModeState) {
        prefs.edit().putString(KEY_MODE, normalizeSupportedMode(mode.name).name).apply()
    }

    fun getManualMode(): ModeState {
        val fallback = getDesiredMode().name
        val raw = prefs.getString(KEY_MANUAL_MODE, fallback) ?: fallback
        return normalizeSupportedMode(raw)
    }

    fun setManualMode(mode: ModeState) {
        prefs.edit().putString(KEY_MANUAL_MODE, normalizeSupportedMode(mode.name).name).apply()
    }

    fun isScheduleLockComputed(): Boolean = prefs.getBoolean(KEY_SCHEDULE_LOCK_COMPUTED, false)

    fun isScheduleLockEnforced(): Boolean = prefs.getBoolean(KEY_SCHEDULE_LOCK_ENFORCED, false)

    fun isScheduleStrictComputed(): Boolean = prefs.getBoolean(KEY_SCHEDULE_STRICT_COMPUTED, false)

    fun isScheduleStrictEnforced(): Boolean = prefs.getBoolean(KEY_SCHEDULE_STRICT_ENFORCED, false)

    fun getScheduleBlockedGroups(): Set<String> {
        return prefs.getStringSet(KEY_SCHEDULE_BLOCKED_GROUPS, emptySet())?.toSet() ?: emptySet()
    }

    fun getScheduleBlockedPackages(): Set<String> {
        return prefs.getStringSet(KEY_SCHEDULE_BLOCKED_PACKAGES, emptySet())?.toSet() ?: emptySet()
    }

    fun getStrictInstallSuspendedPackages(): Set<String> {
        return prefs.getStringSet(KEY_STRICT_INSTALL_SUSPENDED, emptySet())?.toSet() ?: emptySet()
    }

    fun getScheduleLockReason(): String? = prefs.getString(KEY_SCHEDULE_LOCK_REASON, null)

    fun getScheduleNextTransitionAtMs(): Long? {
        val value = prefs.getLong(KEY_SCHEDULE_NEXT_TRANSITION_AT, -1L)
        return if (value > 0L) value else null
    }

    fun setScheduleComputedState(
        active: Boolean,
        strictActive: Boolean,
        blockedGroups: Set<String>,
        reason: String?,
        nextTransitionAtMs: Long?
    ) {
        prefs.edit()
            .putBoolean(KEY_SCHEDULE_LOCK_COMPUTED, active)
            .putBoolean(KEY_SCHEDULE_STRICT_COMPUTED, strictActive)
            .putStringSet(KEY_SCHEDULE_BLOCKED_GROUPS, blockedGroups)
            .putString(KEY_SCHEDULE_LOCK_REASON, reason)
            .putLong(KEY_SCHEDULE_NEXT_TRANSITION_AT, nextTransitionAtMs ?: -1L)
            .apply()
    }

    fun setScheduleEnforced(active: Boolean) {
        prefs.edit().putBoolean(KEY_SCHEDULE_LOCK_ENFORCED, active).apply()
    }

    fun setScheduleStrictEnforced(active: Boolean) {
        prefs.edit().putBoolean(KEY_SCHEDULE_STRICT_ENFORCED, active).apply()
    }

    fun setScheduleBlockedGroups(groups: Set<String>) {
        prefs.edit().putStringSet(KEY_SCHEDULE_BLOCKED_GROUPS, groups).apply()
    }

    fun setScheduleBlockedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_SCHEDULE_BLOCKED_PACKAGES, packages).apply()
    }

    fun addStrictInstallSuspendedPackage(packageName: String) {
        val updated = getStrictInstallSuspendedPackages().toMutableSet().apply { add(packageName) }
        prefs.edit()
            .putStringSet(KEY_STRICT_INSTALL_SUSPENDED, updated)
            .putLong(KEY_STRICT_INSTALL_LAST_EVENT_AT_MS, System.currentTimeMillis())
            .putString(KEY_STRICT_INSTALL_LAST_PKG, packageName)
            .apply()
    }

    fun clearStrictInstallSuspendedPackages() {
        prefs.edit().putStringSet(KEY_STRICT_INSTALL_SUSPENDED, emptySet()).apply()
    }

    fun getBudgetBlockedPackages(): Set<String> {
        return prefs.getStringSet(KEY_BUDGET_BLOCKED, emptySet())?.toSet() ?: emptySet()
    }

    fun getBudgetBlockedGroupIds(): Set<String> {
        return prefs.getStringSet(KEY_BUDGET_BLOCKED_GROUPS, emptySet())?.toSet() ?: emptySet()
    }

    fun getBudgetReason(): String? = prefs.getString(KEY_BUDGET_REASON, null)

    fun isBudgetUsageAccessGranted(): Boolean {
        return prefs.getBoolean(KEY_BUDGET_USAGE_ACCESS_GRANTED, false)
    }

    fun getBudgetNextCheckAtMs(): Long? {
        val value = prefs.getLong(KEY_BUDGET_NEXT_CHECK_AT, -1L)
        return if (value > 0L) value else null
    }

    fun setBudgetState(
        blockedPackages: Set<String>,
        blockedGroupIds: Set<String>,
        reason: String?,
        usageAccessGranted: Boolean,
        nextCheckAtMs: Long?
    ) {
        prefs.edit()
            .putStringSet(KEY_BUDGET_BLOCKED, blockedPackages)
            .putStringSet(KEY_BUDGET_BLOCKED_GROUPS, blockedGroupIds)
            .putString(KEY_BUDGET_REASON, reason)
            .putBoolean(KEY_BUDGET_USAGE_ACCESS_GRANTED, usageAccessGranted)
            .putLong(KEY_BUDGET_NEXT_CHECK_AT, nextCheckAtMs ?: -1L)
            .apply()
    }

    fun setComputedPolicyState(
        scheduleLockComputed: Boolean,
        scheduleStrictComputed: Boolean,
        scheduleBlockedGroups: Set<String>,
        scheduleBlockedPackages: Set<String>,
        scheduleLockReason: String?,
        scheduleNextTransitionAtMs: Long?,
        budgetBlockedPackages: Set<String>,
        budgetBlockedGroupIds: Set<String>,
        budgetReason: String?,
        budgetUsageAccessGranted: Boolean,
        budgetNextCheckAtMs: Long?,
        primaryReasonByPackage: Map<String, String>,
        clearStrictInstallSuspendedPackages: Boolean
    ) {
        prefs.edit()
            .putBoolean(KEY_SCHEDULE_LOCK_COMPUTED, scheduleLockComputed)
            .putBoolean(KEY_SCHEDULE_STRICT_COMPUTED, scheduleStrictComputed)
            .putStringSet(KEY_SCHEDULE_BLOCKED_GROUPS, scheduleBlockedGroups)
            .putStringSet(KEY_SCHEDULE_BLOCKED_PACKAGES, scheduleBlockedPackages)
            .putString(KEY_SCHEDULE_LOCK_REASON, scheduleLockReason)
            .putLong(KEY_SCHEDULE_NEXT_TRANSITION_AT, scheduleNextTransitionAtMs ?: -1L)
            .putStringSet(KEY_BUDGET_BLOCKED, budgetBlockedPackages)
            .putStringSet(KEY_BUDGET_BLOCKED_GROUPS, budgetBlockedGroupIds)
            .putString(KEY_BUDGET_REASON, budgetReason)
            .putBoolean(KEY_BUDGET_USAGE_ACCESS_GRANTED, budgetUsageAccessGranted)
            .putLong(KEY_BUDGET_NEXT_CHECK_AT, budgetNextCheckAtMs ?: -1L)
            .putString(KEY_PRIMARY_REASON_BY_PACKAGE, encodeMap(primaryReasonByPackage))
            .apply {
                if (clearStrictInstallSuspendedPackages) {
                    putStringSet(KEY_STRICT_INSTALL_SUSPENDED, emptySet())
                }
            }
            .apply()
    }

    fun getTouchGrassBreakUntilMs(): Long? {
        val value = prefs.getLong(KEY_TOUCH_GRASS_BREAK_UNTIL_MS, -1L)
        return if (value > 0L) value else null
    }

    fun isTouchGrassBreakActive(nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = getTouchGrassBreakUntilMs() ?: return false
        return until > nowMs
    }

    fun setTouchGrassBreakUntilMs(untilMs: Long?) {
        synchronized(TOUCH_GRASS_LOCK) {
            prefs.edit().putLong(KEY_TOUCH_GRASS_BREAK_UNTIL_MS, untilMs ?: -1L).apply()
        }
    }

    fun getUnlockCountDay(): String? = prefs.getString(KEY_UNLOCK_COUNT_DAY, null)

    fun getUnlockCountToday(): Int = prefs.getInt(KEY_UNLOCK_COUNT_TODAY, 0).coerceAtLeast(0)

    fun incrementUnlockCount(dayKey: String): Int {
        synchronized(TOUCH_GRASS_LOCK) {
            val currentDay = getUnlockCountDay()
            val nextCount = if (currentDay == dayKey) {
                getUnlockCountToday() + 1
            } else {
                1
            }
            prefs.edit()
                .putString(KEY_UNLOCK_COUNT_DAY, dayKey)
                .putInt(KEY_UNLOCK_COUNT_TODAY, nextCount)
                .apply()
            return nextCount
        }
    }

    fun getTouchGrassThreshold(): Int {
        return prefs.getInt(
            KEY_TOUCH_GRASS_THRESHOLD,
            FocusConfig.defaultTouchGrassUnlockThreshold
        ).coerceAtLeast(1)
    }

    fun setTouchGrassThreshold(value: Int) {
        synchronized(TOUCH_GRASS_LOCK) {
            prefs.edit().putInt(KEY_TOUCH_GRASS_THRESHOLD, value.coerceAtLeast(1)).apply()
        }
    }

    fun getTouchGrassBreakMinutes(): Int {
        return prefs.getInt(
            KEY_TOUCH_GRASS_BREAK_MINUTES,
            FocusConfig.defaultTouchGrassBreakMinutes
        ).coerceAtLeast(1)
    }

    fun setTouchGrassBreakMinutes(value: Int) {
        synchronized(TOUCH_GRASS_LOCK) {
            prefs.edit().putInt(KEY_TOUCH_GRASS_BREAK_MINUTES, value.coerceAtLeast(1)).apply()
        }
    }

    fun getEmergencyApps(): Set<String> = prefs.getStringSet(KEY_EMERGENCY, emptySet())?.toSet() ?: emptySet()

    fun hasExplicitEmergencyAppsSelection(): Boolean {
        return prefs.getBoolean(KEY_EMERGENCY_EXPLICIT, false) || prefs.contains(KEY_EMERGENCY)
    }

    fun setEmergencyApps(packages: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_EMERGENCY, packages)
            .putBoolean(KEY_EMERGENCY_EXPLICIT, true)
            .apply()
    }

    fun getLastSuspendedPackages(): Set<String> {
        return prefs.getStringSet(KEY_LAST_SUSPENDED, emptySet())?.toSet() ?: emptySet()
    }

    fun addLastSuspendedPackages(packages: Set<String>) {
        if (packages.isEmpty()) return
        val updated = getLastSuspendedPackages().toMutableSet().apply { addAll(packages) }
        prefs.edit().putStringSet(KEY_LAST_SUSPENDED, updated).apply()
    }

    fun getLastAllowlistReasons(): Map<String, String> {
        val raw = prefs.getString(KEY_LAST_ALLOWLIST_REASONS, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return raw.split('\n')
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null
                else line.substring(0, idx) to line.substring(idx + 1)
            }
            .toMap()
    }

    fun recordApply(
        state: PolicyState,
        applyResult: ApplyResult,
        verification: PolicyVerificationResult,
        errorMessage: String?,
        controllerPackageName: String
    ) {
        val actualSuspended = reconcileTrackedPackages(
            previousPackages = state.previouslySuspended,
            targetPackages = state.suspendTargets,
            failedAdds = applyResult.failedToSuspend,
            failedRemovals = applyResult.failedToUnsuspend
        )
        val desiredUninstallProtected = desiredUninstallProtectedPackages(state, controllerPackageName)
        val actualUninstallProtected = reconcileTrackedPackages(
            previousPackages = state.previouslyUninstallProtectedPackages,
            targetPackages = desiredUninstallProtected,
            failedAdds = applyResult.failedToProtectUninstall,
            failedRemovals = applyResult.failedToUnprotectUninstall
        )
        prefs.edit()
            .putLong(KEY_LAST_APPLIED_AT, System.currentTimeMillis())
            .putBoolean(KEY_LAST_VERIFY_PASSED, verification.passed)
            .putString(KEY_LAST_ERROR, errorMessage)
            .putStringSet(KEY_LAST_SUSPENDED, actualSuspended)
            .putStringSet(KEY_LAST_ALLOWLIST, state.lockTaskAllowlist)
            .putString(KEY_LAST_ALLOWLIST_REASONS, encodeMap(state.allowlistReasons))
            .putStringSet(KEY_LAST_UNINSTALL_PROTECTED, actualUninstallProtected)
            .putString(KEY_PRIMARY_REASON_BY_PACKAGE, encodeMap(state.primaryReasonByPackage))
            .apply()
    }

    fun getLastAppliedAtMs(): Long = prefs.getLong(KEY_LAST_APPLIED_AT, 0L)

    fun getLastVerifyPassed(): Boolean = prefs.getBoolean(KEY_LAST_VERIFY_PASSED, false)

    fun getLastError(): String? = prefs.getString(KEY_LAST_ERROR, null)

    fun getLastAllowlist(): Set<String> = prefs.getStringSet(KEY_LAST_ALLOWLIST, emptySet())?.toSet() ?: emptySet()

    fun getLastUninstallProtectedPackages(): Set<String> {
        return prefs.getStringSet(KEY_LAST_UNINSTALL_PROTECTED, emptySet())?.toSet() ?: emptySet()
    }

    fun getPrimaryReasonByPackage(): Map<String, String> {
        val raw = prefs.getString(KEY_PRIMARY_REASON_BY_PACKAGE, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return raw.split('\n')
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null
                else line.substring(0, idx) to line.substring(idx + 1)
            }
            .toMap()
    }

    fun setPrimaryReasonByPackage(reasons: Map<String, String>) {
        prefs.edit().putString(KEY_PRIMARY_REASON_BY_PACKAGE, encodeMap(reasons)).apply()
    }

    fun getCurrentLockReason(): String? = prefs.getString(KEY_CURRENT_LOCK_REASON, null)

    fun setCurrentLockReason(reason: String?) {
        prefs.edit().putString(KEY_CURRENT_LOCK_REASON, reason).apply()
    }

    fun recordProvisioningSignal(
        action: String?,
        source: String?,
        enrollmentId: String?,
        schemaVersion: Int?
    ) {
        prefs.edit()
            .putString(KEY_PROVISIONING_SIGNAL_ACTION, action)
            .putLong(KEY_PROVISIONING_SIGNAL_AT, System.currentTimeMillis())
            .putString(KEY_PROVISIONING_SOURCE, source)
            .putString(KEY_PROVISIONING_ENROLLMENT_ID, enrollmentId)
            .putInt(KEY_PROVISIONING_SCHEMA_VERSION, schemaVersion ?: -1)
            .apply()
    }

    fun getProvisioningSignalAction(): String? = prefs.getString(KEY_PROVISIONING_SIGNAL_ACTION, null)

    fun getProvisioningSignalAtMs(): Long? {
        val value = prefs.getLong(KEY_PROVISIONING_SIGNAL_AT, -1L)
        return if (value > 0L) value else null
    }

    fun getProvisioningSource(): String? = prefs.getString(KEY_PROVISIONING_SOURCE, null)

    fun getProvisioningEnrollmentId(): String? = prefs.getString(KEY_PROVISIONING_ENROLLMENT_ID, null)

    fun getProvisioningSchemaVersion(): Int? {
        val value = prefs.getInt(KEY_PROVISIONING_SCHEMA_VERSION, -1)
        return if (value >= 0) value else null
    }

    fun recordProvisioningFinalization(state: String, message: String) {
        prefs.edit()
            .putString(KEY_PROVISIONING_FINALIZATION_STATE, state)
            .putString(KEY_PROVISIONING_FINALIZATION_MESSAGE, message)
            .putLong(KEY_PROVISIONING_FINALIZATION_AT, System.currentTimeMillis())
            .apply()
    }

    fun getProvisioningFinalizationState(): String? {
        return prefs.getString(KEY_PROVISIONING_FINALIZATION_STATE, null)
    }

    fun isProvisioningFinalizedSuccessfully(): Boolean {
        return getProvisioningFinalizationState() == ProvisioningCoordinator.FinalizationState.SUCCESS.name
    }

    fun hasSuccessfulPolicyApply(): Boolean {
        return getLastAppliedAtMs() > 0L && getLastVerifyPassed()
    }

    fun hasAnyPriorApply(): Boolean = getLastAppliedAtMs() > 0L

    fun getProvisioningFinalizationMessage(): String? {
        return prefs.getString(KEY_PROVISIONING_FINALIZATION_MESSAGE, null)
    }

    fun getProvisioningFinalizationAtMs(): Long? {
        val value = prefs.getLong(KEY_PROVISIONING_FINALIZATION_AT, -1L)
        return if (value > 0L) value else null
    }

    fun resetForFreshStart() {
        prefs.edit().clear().apply()
    }

    private fun encodeMap(values: Map<String, String>): String {
        return values.entries.joinToString(separator = "\n") { "${it.key}=${it.value}" }
    }

    private fun normalizeSupportedMode(raw: String): ModeState {
        val parsed = runCatching { ModeState.valueOf(raw) }.getOrDefault(ModeState.NORMAL)
        return when (parsed) {
            ModeState.NORMAL -> ModeState.NORMAL
            ModeState.NUCLEAR -> if (FocusConfig.enableNuclearMode) ModeState.NUCLEAR else ModeState.NORMAL
        }
    }

    companion object {
        @Volatile
        private var didAttemptMigration: Boolean = false
        private val TOUCH_GRASS_LOCK = Any()

        private const val PREFS_NAME = "focus_policy_store"
        private const val KEY_MODE = "desired_mode"
        private const val KEY_MANUAL_MODE = "manual_mode"
        private const val KEY_EMERGENCY = "emergency_packages"
        private const val KEY_EMERGENCY_EXPLICIT = "emergency_packages_explicit"
        private const val KEY_SCHEDULE_LOCK_COMPUTED = "schedule_lock_computed"
        private const val KEY_SCHEDULE_LOCK_ENFORCED = "schedule_lock_enforced"
        private const val KEY_SCHEDULE_STRICT_COMPUTED = "schedule_strict_computed"
        private const val KEY_SCHEDULE_STRICT_ENFORCED = "schedule_strict_enforced"
        private const val KEY_SCHEDULE_LOCK_REASON = "schedule_lock_reason"
        private const val KEY_SCHEDULE_NEXT_TRANSITION_AT = "schedule_next_transition_at"
        private const val KEY_SCHEDULE_BLOCKED_GROUPS = "schedule_blocked_groups"
        private const val KEY_SCHEDULE_BLOCKED_PACKAGES = "schedule_blocked_packages"
        private const val KEY_STRICT_INSTALL_SUSPENDED = "strict_install_suspended"
        private const val KEY_STRICT_INSTALL_LAST_EVENT_AT_MS = "strict_install_last_event_at_ms"
        private const val KEY_STRICT_INSTALL_LAST_PKG = "strict_install_last_pkg"
        private const val KEY_BUDGET_BLOCKED = "budget_blocked_packages"
        private const val KEY_BUDGET_BLOCKED_GROUPS = "budget_blocked_groups"
        private const val KEY_BUDGET_REASON = "budget_reason"
        private const val KEY_BUDGET_USAGE_ACCESS_GRANTED = "budget_usage_access_granted"
        private const val KEY_BUDGET_NEXT_CHECK_AT = "budget_next_check_at"
        private const val KEY_TOUCH_GRASS_BREAK_UNTIL_MS = "touch_grass_break_until_ms"
        private const val KEY_UNLOCK_COUNT_DAY = "unlock_count_day"
        private const val KEY_UNLOCK_COUNT_TODAY = "unlock_count_today"
        private const val KEY_TOUCH_GRASS_THRESHOLD = "touch_grass_threshold"
        private const val KEY_TOUCH_GRASS_BREAK_MINUTES = "touch_grass_break_minutes"
        private const val KEY_LAST_APPLIED_AT = "last_applied_at"
        private const val KEY_LAST_VERIFY_PASSED = "last_verify_passed"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_LAST_SUSPENDED = "last_suspended_packages"
        private const val KEY_LAST_ALLOWLIST = "last_allowlist"
        private const val KEY_LAST_ALLOWLIST_REASONS = "last_allowlist_reasons"
        private const val KEY_LAST_UNINSTALL_PROTECTED = "last_uninstall_protected"
        private const val KEY_PRIMARY_REASON_BY_PACKAGE = "primary_reason_by_package"
        private const val KEY_CURRENT_LOCK_REASON = "current_lock_reason"
        private const val KEY_PROVISIONING_SIGNAL_ACTION = "provisioning_signal_action"
        private const val KEY_PROVISIONING_SIGNAL_AT = "provisioning_signal_at"
        private const val KEY_PROVISIONING_SOURCE = "provisioning_source"
        private const val KEY_PROVISIONING_ENROLLMENT_ID = "provisioning_enrollment_id"
        private const val KEY_PROVISIONING_SCHEMA_VERSION = "provisioning_schema_version"
        private const val KEY_PROVISIONING_FINALIZATION_STATE = "provisioning_finalization_state"
        private const val KEY_PROVISIONING_FINALIZATION_MESSAGE = "provisioning_finalization_message"
        private const val KEY_PROVISIONING_FINALIZATION_AT = "provisioning_finalization_at"

        internal fun desiredUninstallProtectedPackages(
            state: PolicyState,
            controllerPackageName: String
        ): Set<String> = buildSet {
            addAll(state.uninstallProtectedPackages)
            if (state.blockSelfUninstall) {
                add(controllerPackageName)
            }
        }

        internal fun reconcileTrackedPackages(
            previousPackages: Set<String>,
            targetPackages: Set<String>,
            failedAdds: Set<String>,
            failedRemovals: Set<String>
        ): Set<String> {
            val toRemove = previousPackages - targetPackages
            val toAdd = targetPackages - previousPackages
            val successfulRemovals = toRemove - failedRemovals
            val successfulAdds = toAdd - failedAdds
            return (previousPackages - successfulRemovals) + successfulAdds
        }
    }
}








