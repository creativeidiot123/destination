package com.ankit.destination.ui.groups

import android.content.Context
import androidx.room.withTransaction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ankit.destination.budgets.clampEmergencyMinutesPerUnlock
import com.ankit.destination.budgets.clampEmergencyUnlocksPerDay
import com.ankit.destination.data.FocusDatabase
import com.ankit.destination.data.GroupEmergencyConfig
import com.ankit.destination.data.GroupLimit
import com.ankit.destination.data.ScheduleBlock
import com.ankit.destination.data.ScheduleBlockKind
import com.ankit.destination.data.ScheduleTimezoneMode
import com.ankit.destination.enforce.PolicyApplyOrchestrator
import com.ankit.destination.policy.ApplyTrigger
import com.ankit.destination.policy.ApplyTriggerCategory
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.AppOption
import com.ankit.destination.ui.UiInvalidationBus
import com.ankit.destination.ui.deriveGroupId
import com.ankit.destination.ui.loadInstalledAppOptions
import com.ankit.destination.ui.runCatchingNonCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEFAULT_WEEKDAY_MASK = 0b0111110
private const val DEFAULT_START_MINUTE = 9 * 60
private const val DEFAULT_END_MINUTE = 17 * 60

enum class GroupDetailPendingAction {
    SAVE,
    DELETE
}

sealed interface GroupDetailEvent {
    data class Toast(val message: String) : GroupDetailEvent
    data class Finish(val message: String) : GroupDetailEvent
}

data class GroupDetailUiState(
    val groupId: String? = null,
    val isNewGroup: Boolean = false,
    val name: String = "",
    val isLoading: Boolean = true,
    val adminSessionActive: Boolean = false,
    val adminSessionRemainingMs: Long = 0,
    val showAuthDialog: Boolean = false,
    val pendingAction: GroupDetailPendingAction? = null,
    val statusMessage: String? = null,
    val isError: Boolean = false,
    val availableApps: List<AppOption> = emptyList(),

    val priorityIndex: Int = 1000,
    val strictEnabled: Boolean = false,
    val scheduleBlockId: Long? = null,
    val hasScheduleBlock: Boolean = false,
    val scheduleEnabled: Boolean = false,
    val scheduleDaysMask: Int = DEFAULT_WEEKDAY_MASK,
    val scheduleStartMinute: Int = DEFAULT_START_MINUTE,
    val scheduleEndMinute: Int = DEFAULT_END_MINUTE,
    val hourlyLimitMs: Long = 0,
    val dailyLimitMs: Long = 0,
    val opensPerDay: Int = 0,
    val allAppsTargetingEnabled: Boolean = false,

    val emergencyEnabled: Boolean = false,
    val unlocksPerDay: Int = 0,
    val minutesPerUnlock: Int = 0,

    val memberPackages: List<String> = emptyList()
)

class GroupDetailViewModel(
    private val appContext: Context,
    private val policyEngine: PolicyEngine,
    private val appLockManager: AppLockManager,
    private val initialGroupId: String?
) : ViewModel() {

    private val db by lazy { FocusDatabase.get(appContext) }
    private var currentGroupId: String? = initialGroupId?.takeIf { it.isNotBlank() }
    private val _uiState = MutableStateFlow(GroupDetailUiState(isLoading = true))
    val uiState: StateFlow<GroupDetailUiState> = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<GroupDetailEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val previous = _uiState.value
            _uiState.update { it.copy(isLoading = true) }
            val adminActive = appLockManager.isAdminSessionActive()
            val remainingMs = appLockManager.getSessionRemainingMs()
            val resolvedGroupId = currentGroupId
            val isNew = resolvedGroupId.isNullOrBlank()

            val loadedState = withContext(Dispatchers.IO) {
                runCatchingNonCancellation {
                    val budgetDao = db.budgetDao()
                    val scheduleDao = db.scheduleDao()
                    val protectionSnapshot = policyEngine.getAppProtectionSnapshotAsync()
                    val groupLimit = resolvedGroupId?.let { budgetDao.getGroupLimit(it) }
                    val emergencyConfig = resolvedGroupId?.let { budgetDao.getGroupEmergencyConfig(it) }
                    val memberPackages = resolvedGroupId
                        ?.let { budgetDao.getPackagesForGroup(it) }
                        .orEmpty()
                        .filter(protectionSnapshot::isEligibleForGroupMembership)
                        .sorted()
                    val scheduleBlock = resolvedGroupId
                        ?.let { scheduleDao.getBlocksForGroup(it).firstOrNull() }
                    val appOptions = loadInstalledAppOptions(
                        context = appContext,
                        includePackageNames = memberPackages.toSet(),
                        protectionSnapshot = protectionSnapshot
                    )

                    GroupDetailUiState(
                        groupId = resolvedGroupId,
                        isNewGroup = isNew,
                        name = groupLimit?.name.orEmpty(),
                        isLoading = false,
                        adminSessionActive = adminActive,
                        adminSessionRemainingMs = remainingMs,
                        availableApps = appOptions,
                        priorityIndex = groupLimit?.priorityIndex ?: 1000,
                        strictEnabled = groupLimit?.strictEnabled ?: false,
                        scheduleBlockId = scheduleBlock?.id,
                        hasScheduleBlock = scheduleBlock != null,
                        scheduleEnabled = scheduleBlock?.enabled == true,
                        scheduleDaysMask = scheduleBlock?.daysMask ?: DEFAULT_WEEKDAY_MASK,
                        scheduleStartMinute = scheduleBlock?.startMinute ?: DEFAULT_START_MINUTE,
                        scheduleEndMinute = scheduleBlock?.endMinute ?: DEFAULT_END_MINUTE,
                        hourlyLimitMs = groupLimit?.hourlyLimitMs ?: 0L,
                        dailyLimitMs = groupLimit?.dailyLimitMs ?: 0L,
                        opensPerDay = groupLimit?.opensPerDay ?: 0,
                        allAppsTargetingEnabled = isAllAppsScheduleTargetEnabled(
                            strictEnabled = groupLimit?.strictEnabled ?: false,
                            storedTargetMode = groupLimit?.scheduleTargetMode.orEmpty()
                        ),
                        emergencyEnabled = emergencyConfig?.enabled ?: false,
                        unlocksPerDay = clampEmergencyUnlocksPerDay(emergencyConfig?.unlocksPerDay ?: 0),
                        minutesPerUnlock = clampEmergencyMinutesPerUnlock(emergencyConfig?.minutesPerUnlock ?: 0),
                        memberPackages = memberPackages
                    )
                }
            }
            loadedState.onSuccess { state ->
                _uiState.value = state.copy(
                    statusMessage = previous.statusMessage.takeIf { !previous.isError },
                    isError = false
                )
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        adminSessionActive = appLockManager.isAdminSessionActive(),
                        adminSessionRemainingMs = appLockManager.getSessionRemainingMs(),
                        statusMessage = throwable.message ?: "Failed to load group details.",
                        isError = true
                    )
                }
            }
        }
    }

    fun updateName(newName: String) {
        _uiState.update { it.copy(name = newName, statusMessage = null) }
    }

    fun updatePriority(priorityIndex: Int) {
        _uiState.update { it.copy(priorityIndex = priorityIndex.coerceAtLeast(0), statusMessage = null) }
    }

    fun toggleStrictOption(enabled: Boolean) {
        _uiState.update {
            it.copy(
                strictEnabled = enabled,
                allAppsTargetingEnabled = if (enabled) it.allAppsTargetingEnabled else false,
                statusMessage = null
            )
        }
    }

    fun toggleAllAppsTargeting(enabled: Boolean) {
        _uiState.update {
            it.copy(
                allAppsTargetingEnabled = enabled && it.strictEnabled,
                statusMessage = null
            )
        }
    }

    fun toggleScheduleOption(enabled: Boolean) {
        _uiState.update {
            it.copy(
                scheduleEnabled = enabled,
                hasScheduleBlock = it.hasScheduleBlock || enabled,
                statusMessage = null
            )
        }
    }

    fun updateScheduleDay(dayIndex: Int, selected: Boolean) {
        val bit = 1 shl dayIndex
        _uiState.update { state ->
            val nextMask = if (selected) {
                state.scheduleDaysMask or bit
            } else {
                state.scheduleDaysMask and bit.inv()
            }
            state.copy(scheduleDaysMask = nextMask, statusMessage = null)
        }
    }

    fun updateScheduleStart(hour: Int, minute: Int) {
        _uiState.update {
            it.copy(scheduleStartMinute = hour * 60 + minute, statusMessage = null)
        }
    }

    fun updateScheduleEnd(hour: Int, minute: Int) {
        _uiState.update {
            it.copy(scheduleEndMinute = hour * 60 + minute, statusMessage = null)
        }
    }

    fun updateHourlyLimitMinutes(minutes: Int) {
        _uiState.update {
            it.copy(hourlyLimitMs = minutes.coerceAtLeast(0) * 60_000L, statusMessage = null)
        }
    }

    fun updateDailyLimitMinutes(minutes: Int) {
        _uiState.update {
            it.copy(dailyLimitMs = minutes.coerceAtLeast(0) * 60_000L, statusMessage = null)
        }
    }

    fun updateOpensPerDay(opens: Int) {
        _uiState.update {
            it.copy(opensPerDay = opens.coerceAtLeast(0), statusMessage = null)
        }
    }

    fun toggleEmergency(enabled: Boolean) {
        _uiState.update { it.copy(emergencyEnabled = enabled, statusMessage = null) }
    }

    fun updateEmergencyConfig(unlocksPerDay: Int, minutesPerUnlock: Int) {
        _uiState.update {
            it.copy(
                unlocksPerDay = clampEmergencyUnlocksPerDay(unlocksPerDay),
                minutesPerUnlock = clampEmergencyMinutesPerUnlock(minutesPerUnlock),
                statusMessage = null
            )
        }
    }

    fun updateMemberPackages(packageNames: Set<String>) {
        _uiState.update {
            it.copy(
                memberPackages = packageNames
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                    .sorted(),
                statusMessage = null
            )
        }
    }

    fun requestSave() {
        if (!_uiState.value.adminSessionActive && appLockManager.isProtectionEnabled()) {
            _uiState.update {
                it.copy(
                    showAuthDialog = true,
                    pendingAction = GroupDetailPendingAction.SAVE
                )
            }
            return
        }
        saveInternal()
    }

    fun requestDelete() {
        val groupId = currentGroupId ?: _uiState.value.groupId
        if (groupId.isNullOrBlank()) {
            viewModelScope.launch {
                _events.emit(GroupDetailEvent.Toast("Save the group before deleting it."))
            }
            return
        }
        if (!_uiState.value.adminSessionActive && appLockManager.isProtectionEnabled()) {
            _uiState.update {
                it.copy(
                    showAuthDialog = true,
                    pendingAction = GroupDetailPendingAction.DELETE
                )
            }
            return
        }
        deleteInternal(groupId)
    }

    fun dismissAuthDialog() {
        _uiState.update { it.copy(showAuthDialog = false, pendingAction = null) }
    }

    fun onAuthenticated() {
        val pendingAction = _uiState.value.pendingAction
        dismissAuthDialog()
        refreshAdminSession()
        when (pendingAction) {
            GroupDetailPendingAction.SAVE -> saveInternal()
            GroupDetailPendingAction.DELETE -> {
                val groupId = currentGroupId ?: _uiState.value.groupId
                if (!groupId.isNullOrBlank()) {
                    deleteInternal(groupId)
                }
            }
            null -> Unit
        }
    }

    private fun refreshAdminSession() {
        _uiState.update {
            it.copy(
                adminSessionActive = appLockManager.isAdminSessionActive(),
                adminSessionRemainingMs = appLockManager.getSessionRemainingMs()
            )
        }
    }

    private fun saveInternal() {
        viewModelScope.launch {
            val current = _uiState.value
            val trimmedName = current.name.trim()
            if (trimmedName.isBlank()) {
                _events.emit(GroupDetailEvent.Toast("Group name cannot be empty."))
                _uiState.update {
                    it.copy(statusMessage = "Group name cannot be empty.", isError = true)
                }
                return@launch
            }
            if (current.scheduleEnabled && current.scheduleDaysMask == 0) {
                _events.emit(GroupDetailEvent.Toast("Select at least one schedule day."))
                _uiState.update {
                    it.copy(statusMessage = "Select at least one active day for the group schedule.", isError = true)
                }
                return@launch
            }
            validateGroupScheduleWindow(
                scheduleEnabled = current.scheduleEnabled,
                startMinute = current.scheduleStartMinute,
                endMinute = current.scheduleEndMinute
            )?.let { validationMessage ->
                _events.emit(GroupDetailEvent.Toast(validationMessage))
                _uiState.update {
                    it.copy(statusMessage = validationMessage, isError = true)
                }
                return@launch
            }

            val finalGroupId = current.groupId ?: deriveGroupId(trimmedName)
            if (finalGroupId.isBlank()) {
                _events.emit(GroupDetailEvent.Toast("Group name must contain letters or numbers."))
                _uiState.update {
                    it.copy(statusMessage = "Group name must contain letters or numbers.", isError = true)
                }
                return@launch
            }

            _uiState.update { it.copy(isLoading = true, statusMessage = null) }

            val result = withContext(Dispatchers.IO) {
                runCatchingNonCancellation {
                    val protectionSnapshot = policyEngine.getAppProtectionSnapshotAsync()
                    val eligibleMembers = current.memberPackages
                        .filter(protectionSnapshot::isEligibleForGroupMembership)
                    db.withTransaction {
                        val budgetDao = db.budgetDao()
                        val scheduleDao = db.scheduleDao()
                        budgetDao.upsertGroupLimit(
                            GroupLimit(
                                groupId = finalGroupId,
                                name = trimmedName,
                                priorityIndex = current.priorityIndex,
                                dailyLimitMs = current.dailyLimitMs,
                                hourlyLimitMs = current.hourlyLimitMs,
                                opensPerDay = current.opensPerDay,
                                strictEnabled = current.strictEnabled,
                                scheduleTargetMode = resolvePersistedScheduleTargetMode(
                                    strictEnabled = current.strictEnabled,
                                    allAppsEnabled = current.allAppsTargetingEnabled
                                ),
                                enabled = true
                            )
                        )
                        budgetDao.upsertGroupEmergencyConfig(
                            GroupEmergencyConfig(
                                groupId = finalGroupId,
                                enabled = current.emergencyEnabled,
                                unlocksPerDay = current.unlocksPerDay,
                                minutesPerUnlock = current.minutesPerUnlock
                            )
                        )
                        budgetDao.deleteMappingsForGroup(finalGroupId)
                        eligibleMembers.forEach { packageName ->
                            budgetDao.upsertMapping(
                                com.ankit.destination.data.AppGroupMap(
                                    packageName = packageName,
                                    groupId = finalGroupId
                                )
                            )
                        }
                        val scheduleBlocks = if (current.hasScheduleBlock) {
                            listOf(
                                ScheduleBlock(
                                    id = current.scheduleBlockId ?: 0L,
                                    name = "$trimmedName schedule",
                                    daysMask = current.scheduleDaysMask,
                                    startMinute = current.scheduleStartMinute,
                                    endMinute = current.scheduleEndMinute,
                                    enabled = current.scheduleEnabled,
                                    kind = ScheduleBlockKind.GROUPS.name,
                                    strict = current.strictEnabled,
                                    immutable = false,
                                    timezoneMode = ScheduleTimezoneMode.DEVICE_LOCAL.name
                                )
                            )
                        } else {
                            emptyList()
                        }
                        scheduleDao.replaceGroupSchedules(finalGroupId, scheduleBlocks)
                    }
                    PolicyApplyOrchestrator.applyNow(
                        context = appContext,
                        trigger = ApplyTrigger(
                            category = ApplyTriggerCategory.POLICY_MUTATION,
                            source = "group_detail_save",
                            detail = finalGroupId
                        )
                    )
                }
            }

            result.onSuccess {
                currentGroupId = finalGroupId
                UiInvalidationBus.invalidate("group_detail_saved")
                _uiState.update {
                    it.copy(
                        groupId = finalGroupId,
                        isNewGroup = false,
                        isLoading = false,
                        statusMessage = "Group saved and rules reapplied.",
                        isError = false,
                        adminSessionActive = appLockManager.isAdminSessionActive(),
                        adminSessionRemainingMs = appLockManager.getSessionRemainingMs()
                    )
                }
                _events.emit(GroupDetailEvent.Finish("Group saved."))
            }.onFailure { throwable ->
                _events.emit(GroupDetailEvent.Toast(throwable.message ?: "Failed to save group."))
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = throwable.message ?: "Failed to save group.",
                        isError = true
                    )
                }
            }
        }
    }

    private fun deleteInternal(groupId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = null) }
            val result = withContext(Dispatchers.IO) {
                runCatchingNonCancellation {
                    db.withTransaction {
                        val budgetDao = db.budgetDao()
                        val scheduleDao = db.scheduleDao()
                        scheduleDao.replaceGroupSchedules(groupId, emptyList())
                        budgetDao.deleteGroupLimitCascade(groupId)
                    }
                    PolicyApplyOrchestrator.applyNow(
                        context = appContext,
                        trigger = ApplyTrigger(
                            category = ApplyTriggerCategory.POLICY_MUTATION,
                            source = "group_detail_delete",
                            detail = groupId
                        )
                    )
                }
            }

            result.onSuccess {
                currentGroupId = null
                UiInvalidationBus.invalidate("group_detail_deleted")
                _events.emit(GroupDetailEvent.Finish("Group deleted."))
            }.onFailure { throwable ->
                _events.emit(GroupDetailEvent.Toast(throwable.message ?: "Failed to delete group."))
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = throwable.message ?: "Failed to delete group.",
                        isError = true
                    )
                }
            }
        }
    }
}

class GroupDetailViewModelFactory(
    private val appContext: Context,
    private val policyEngine: PolicyEngine,
    private val appLockManager: AppLockManager,
    private val groupId: String?
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return GroupDetailViewModel(appContext, policyEngine, appLockManager, groupId) as T
    }
}
