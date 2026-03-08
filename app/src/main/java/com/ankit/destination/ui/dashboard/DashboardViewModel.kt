package com.ankit.destination.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ankit.destination.enforce.AccessibilityStatusMonitor
import com.ankit.destination.enforce.PolicyApplyOrchestrator
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.RefreshCoordinator
import com.ankit.destination.ui.UiInvalidationBus
import com.ankit.destination.ui.runCatchingNonCancellation
import com.ankit.destination.usage.UsageAccessMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

data class DashboardUiState(
    val isDeviceOwner: Boolean = false,
    val isLoading: Boolean = true,
    val protectionActive: Boolean = false,
    val vpnLocked: Boolean = false,
    val totalBlockedApps: Int = 0,
    val activeSchedules: Int = 0,
    val activeEmergencySessions: Int = 0,
    val blockedGroups: List<BlockedGroupSummary> = emptyList(),
    val strictActiveGroups: Int = 0,
    val lastApplied: String? = null,
    val lastError: String? = null,
    val accessibilityServiceEnabled: Boolean = false,
    val accessibilityServiceRunning: Boolean = false,
    val accessibilityDegradedReason: String? = null,
    val nextPolicyWake: String? = null,
    val usageAccessGranted: Boolean = false,
    val usageAccessRecoveryLockdownActive: Boolean = false,
    val usageAccessRecoveryReason: String? = null,
    val adminSessionActive: Boolean = false,
    val adminSessionRemainingMs: Long = 0
)

data class BlockedGroupSummary(
    val groupId: String,
    val name: String,
    val reason: String,
    val appCount: Int,
    val emergencyUntil: String?
)

class DashboardViewModel(
    private val appContext: Context,
    private val policyEngine: PolicyEngine,
    private val appLockManager: AppLockManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()
    private val refreshCoordinator = RefreshCoordinator()
    private var hasLoadedOnce = false
    private var lastHandledInvalidationVersion = UiInvalidationBus.latest.value.version

    init {
        refresh(force = true)
        viewModelScope.launch {
            UsageAccessMonitor.currentState
                .map { it.usageAccessGranted }
                .distinctUntilChanged()
                .drop(1)
                .collect { refresh(force = true) }
        }
        viewModelScope.launch {
            AccessibilityStatusMonitor.currentState
                .map { state ->
                    listOf(
                        state.enabled,
                        state.lastConnectedAtMs,
                        state.lastDisconnectedAtMs,
                        state.lastHeartbeatAtMs
                    )
                }
                .distinctUntilChanged()
                .drop(1)
                .collect { refresh(force = true) }
        }
    }

    fun refresh(force: Boolean = false) {
        if (!refreshCoordinator.tryStart(force)) return

        viewModelScope.launch {
            var shouldRerun: Boolean
            do {
                var loadSucceeded = false
                try {
                    if (!hasLoadedOnce) {
                        _uiState.update { it.copy(isLoading = true) }
                    }
                    val refreshed = withContext(Dispatchers.IO) {
                        runCatchingNonCancellation {
                            val snapshot = policyEngine.dashboardSnapshot()
                            val lastApplied = if (snapshot.lastAppliedAtMs > 0L) {
                                DateFormat.getDateTimeInstance().format(Date(snapshot.lastAppliedAtMs))
                            } else {
                                "never"
                            }
                            DashboardUiState(
                                isDeviceOwner = snapshot.deviceOwner,
                                isLoading = false,
                                protectionActive = appLockManager.isProtectionEnabled(),
                                vpnLocked = snapshot.vpnActive,
                                totalBlockedApps = snapshot.totalBlockedApps,
                                activeSchedules = snapshot.scheduleBlockedGroupsCount,
                                activeEmergencySessions = 0,
                                blockedGroups = emptyList(),
                                strictActiveGroups = if (snapshot.scheduleStrictActive) {
                                    snapshot.scheduleBlockedGroupsCount
                                } else {
                                    0
                                },
                                lastApplied = lastApplied,
                                lastError = snapshot.lastError,
                                accessibilityServiceEnabled = snapshot.accessibilityServiceEnabled,
                                accessibilityServiceRunning = snapshot.accessibilityServiceRunning,
                                accessibilityDegradedReason = snapshot.accessibilityDegradedReason,
                                nextPolicyWake = snapshot.nextPolicyWakeAtMs?.let { wakeAt ->
                                    val formatted = DateFormat.getDateTimeInstance().format(Date(wakeAt))
                                    val reason = snapshot.nextPolicyWakeReason ?: "policy wake"
                                    "$reason at $formatted"
                                },
                                usageAccessGranted = snapshot.usageAccessGranted,
                                usageAccessRecoveryLockdownActive = snapshot.usageAccessRecoveryLockdownActive,
                                usageAccessRecoveryReason = snapshot.usageAccessRecoveryReason,
                                adminSessionActive = appLockManager.isAdminSessionActive(),
                                adminSessionRemainingMs = appLockManager.getSessionRemainingMs()
                            )
                        }
                    }

                    refreshed.onSuccess { state ->
                        _uiState.value = state
                        hasLoadedOnce = true
                        loadSucceeded = true
                    }.onFailure { throwable ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                adminSessionActive = appLockManager.isAdminSessionActive(),
                                adminSessionRemainingMs = appLockManager.getSessionRemainingMs(),
                                lastError = throwable.message ?: "Failed to load dashboard."
                            )
                        }
                    }
                } finally {
                    shouldRerun = refreshCoordinator.finish(loadSucceeded)
                }
            } while (shouldRerun)
        }
    }

    fun onInvalidation(version: Long) {
        if (version <= 0L || version <= lastHandledInvalidationVersion) return
        lastHandledInvalidationVersion = version
        refresh(force = true)
    }

    fun applyNow() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatchingNonCancellation {
                    UsageAccessMonitor.refreshNow(
                        context = appContext,
                        reason = "dashboard_apply_now",
                        requestPolicyRefreshIfChanged = false
                    )
                    PolicyApplyOrchestrator.applyNow(
                        context = appContext,
                        reason = "ui_reapply"
                    )
                }
            }
            result.onSuccess {
                UiInvalidationBus.invalidate("dashboard_apply_now")
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(lastError = throwable.message ?: "Failed to apply rules now.")
                }
            }
        }
    }
}

class DashboardViewModelFactory(
    private val appContext: Context,
    private val policyEngine: PolicyEngine,
    private val appLockManager: AppLockManager
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return DashboardViewModel(appContext, policyEngine, appLockManager) as T
    }
}
