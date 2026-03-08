package com.ankit.destination.ui.groups

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ankit.destination.budgets.BudgetOrchestrator
import com.ankit.destination.enforce.PolicyApplyOrchestrator
import com.ankit.destination.budgets.clampEmergencyMinutesPerUnlock
import com.ankit.destination.budgets.clampEmergencyUnlocksPerDay
import com.ankit.destination.data.EmergencyStateMerger
import com.ankit.destination.data.EmergencyTargetType
import com.ankit.destination.data.FocusDatabase
import com.ankit.destination.data.GroupLimit
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.RefreshCoordinator
import com.ankit.destination.ui.minuteToTimeLabel
import com.ankit.destination.usage.UsageWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.ZonedDateTime
import java.util.Date

data class GroupCardState(
    val groupId: String,
    val name: String,
    val appCount: Int,
    val priorityIndex: Int,
    val isBlocked: Boolean,
    val primaryReason: String?,
    val isStrictActive: Boolean,
    val isEmergencyActive: Boolean,
    val emergencyUntil: String?,
    val emergencyEnabled: Boolean,
    val emergencyRemainingUnlocks: Int,
    val emergencyUnlocksPerDay: Int,
    val emergencyMinutesPerUnlock: Int,
    val effectiveBlocked: Boolean,
    val scheduleSummary: String
)

sealed interface GroupListEvent {
    data class Toast(val message: String) : GroupListEvent
}

data class GroupListUiState(
    val groups: List<GroupCardState> = emptyList(),
    val isLoading: Boolean = true,
    val adminSessionActive: Boolean = false,
    val adminSessionRemainingMs: Long = 0,
    val showAuthDialog: Boolean = false,
    val pendingActionGroupId: String? = null,
    val activatingEmergencyGroupId: String? = null
)

class GroupListViewModel(
    private val appContext: Context,
    private val policyEngine: PolicyEngine,
    private val appLockManager: AppLockManager
) : ViewModel() {
    private val db by lazy { FocusDatabase.get(appContext) }
    private val budgetOrchestrator by lazy { BudgetOrchestrator(appContext) }
    private val refreshCoordinator = RefreshCoordinator()
    private val _uiState = MutableStateFlow(GroupListUiState())
    val uiState: StateFlow<GroupListUiState> = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<GroupListEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events

    fun refresh(force: Boolean = false) {
        if (!refreshCoordinator.tryStart(force)) return

        viewModelScope.launch {
            var shouldRerun: Boolean
            do {
                var loadSucceeded = false
                try {
                    val previous = _uiState.value
                    _uiState.update { it.copy(isLoading = true) }
                    val refreshed = withContext(Dispatchers.IO) {
                        val adminActive = appLockManager.isAdminSessionActive()
                        val remainingMs = appLockManager.getSessionRemainingMs()
                        val snapshot = policyEngine.diagnosticsSnapshot()
                        val budgetDao = db.budgetDao()
                        val scheduleDao = db.scheduleDao()
                        val now = ZonedDateTime.now()
                        val nowMs = now.toInstant().toEpochMilli()
                        val dayKey = UsageWindow.dayKey(now)
                        val limits = budgetDao.getAllGroupLimits()
                        val emergencyConfigs = budgetDao.getAllGroupEmergencyConfigs().associateBy { it.groupId }
                        val packagesByGroup = budgetDao.getAllMappings()
                            .groupBy({ it.groupId }, { it.packageName })
                        val schedulesByGroup = limits.associate { limit ->
                            limit.groupId to scheduleDao.getBlocksForGroup(limit.groupId).firstOrNull()
                        }
                        budgetDao.clearExpiredEmergencyStateBefore(dayKey, nowMs)
                        val mergedEmergencyStates = EmergencyStateMerger.merge(
                            dayKey = dayKey,
                            nowMs = nowMs,
                            rows = budgetDao
                                .getCurrentOrActiveEmergencyStates(dayKey, nowMs)
                                .asSequence()
                                .filter { it.targetType == EmergencyTargetType.GROUP.name }
                                .toList()
                        )
                        val emergencyStates = mergedEmergencyStates.associateBy { it.targetId }

                        GroupListUiState(
                            groups = limits
                                .sortedWith(compareBy<GroupLimit> { it.priorityIndex }.thenBy { it.name.lowercase() })
                                .map { limit ->
                                    val scheduleBlock = schedulesByGroup[limit.groupId]
                                    val emergencyConfig = emergencyConfigs[limit.groupId]
                                    val emergencyState = emergencyStates[limit.groupId]
                                    val isEmergencyActive = (emergencyState?.activeUntilEpochMs ?: 0L) > nowMs
                                    val scheduleBlocked = snapshot.scheduleBlockedGroups.contains(limit.groupId)
                                    val budgetBlocked = snapshot.budgetBlockedGroupIds.contains(limit.groupId)
                                    val emergencyUnlocksPerDay = clampEmergencyUnlocksPerDay(
                                        emergencyConfig?.unlocksPerDay ?: 0
                                    )
                                    val emergencyMinutesPerUnlock = clampEmergencyMinutesPerUnlock(
                                        emergencyConfig?.minutesPerUnlock ?: 0
                                    )
                                    val emergencyEnabled = emergencyConfig?.enabled == true &&
                                        emergencyUnlocksPerDay > 0 &&
                                        emergencyMinutesPerUnlock > 0
                                    val emergencyRemainingUnlocks = (
                                        emergencyUnlocksPerDay - (emergencyState?.unlocksUsedToday ?: 0)
                                    ).coerceAtLeast(0)
                                    val baselineBlocked = scheduleBlocked || budgetBlocked
                                    val effectiveBlocked = baselineBlocked && !isEmergencyActive
                                    val reason = when {
                                        isEmergencyActive -> "Emergency unlock active"
                                        scheduleBlocked -> "Scheduled block active"
                                        budgetBlocked -> snapshot.budgetReason ?: "Usage limit reached"
                                        else -> null
                                    }

                                    GroupCardState(
                                        groupId = limit.groupId,
                                        name = limit.name,
                                        appCount = packagesByGroup[limit.groupId].orEmpty().distinct().size,
                                        priorityIndex = limit.priorityIndex,
                                        isBlocked = effectiveBlocked,
                                        primaryReason = reason,
                                        isStrictActive = limit.strictEnabled && scheduleBlocked,
                                        isEmergencyActive = isEmergencyActive,
                                        emergencyUntil = emergencyState?.activeUntilEpochMs?.let {
                                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
                                        },
                                        emergencyEnabled = emergencyEnabled,
                                        emergencyRemainingUnlocks = emergencyRemainingUnlocks,
                                        emergencyUnlocksPerDay = emergencyUnlocksPerDay,
                                        emergencyMinutesPerUnlock = emergencyMinutesPerUnlock,
                                        effectiveBlocked = effectiveBlocked,
                                        scheduleSummary = scheduleBlock?.let { block ->
                                            "${daysMaskLabel(block.daysMask)} - ${minuteToTimeLabel(block.startMinute)}-${minuteToTimeLabel(block.endMinute)}"
                                        } ?: "No schedule"
                                    )
                                },
                            isLoading = false,
                            adminSessionActive = adminActive,
                            adminSessionRemainingMs = remainingMs
                        )
                    }

                    _uiState.value = refreshed.copy(
                        showAuthDialog = previous.showAuthDialog,
                        pendingActionGroupId = previous.pendingActionGroupId,
                        activatingEmergencyGroupId = previous.activatingEmergencyGroupId
                    )
                    loadSucceeded = true
                } finally {
                    shouldRerun = refreshCoordinator.finish(loadSucceeded)
                }
            } while (shouldRerun)
        }
    }

    fun activateEmergency(groupId: String) {
        val normalizedGroupId = groupId.trim()
        if (normalizedGroupId.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(activatingEmergencyGroupId = normalizedGroupId) }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    budgetOrchestrator.activateEmergencyUnlock(
                        targetType = EmergencyTargetType.GROUP,
                        targetId = normalizedGroupId
                    ).also { unlockResult ->
                        if (unlockResult.success) {
                            PolicyApplyOrchestrator.applyNow(
                                context = appContext,
                                reason = "group_emergency_activate:$normalizedGroupId"
                            )
                        }
                    }
                }
            }

            _uiState.update { state ->
                if (state.activatingEmergencyGroupId == normalizedGroupId) {
                    state.copy(activatingEmergencyGroupId = null)
                } else {
                    state
                }
            }

            result.onSuccess {
                _events.emit(GroupListEvent.Toast(it.message))
                refresh(force = true)
            }.onFailure { throwable ->
                _events.emit(GroupListEvent.Toast(throwable.message ?: "Failed to activate emergency unlock."))
            }
        }
    }

    fun attemptEditGroup(groupId: String, navigate: (String) -> Unit) {
        if (!_uiState.value.adminSessionActive && appLockManager.isProtectionEnabled()) {
            _uiState.update { it.copy(showAuthDialog = true, pendingActionGroupId = groupId) }
            return
        }
        navigate(groupId)
    }

    fun dismissAuthDialog() {
        _uiState.update { it.copy(showAuthDialog = false, pendingActionGroupId = null) }
    }

    fun onAuthenticated(navigate: (String) -> Unit) {
        val pendingId = _uiState.value.pendingActionGroupId
        dismissAuthDialog()
        refresh(force = true)
        if (pendingId != null) {
            navigate(pendingId)
        }
    }
}

private fun daysMaskLabel(daysMask: Int): String {
    val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val selected = labels.filterIndexed { index, _ -> daysMask and (1 shl index) != 0 }
    return when {
        selected.isEmpty() -> "No days"
        selected.size == 7 -> "Every day"
        else -> selected.joinToString(", ")
    }
}

class GroupListViewModelFactory(
    private val appContext: Context,
    private val policyEngine: PolicyEngine,
    private val appLockManager: AppLockManager
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return GroupListViewModel(appContext, policyEngine, appLockManager) as T
    }
}
