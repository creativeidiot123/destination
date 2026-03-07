package com.ankit.destination.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
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

    init {
        viewModelScope.launch {
            UsageAccessMonitor.currentState
                .map { it.usageAccessGranted }
                .distinctUntilChanged()
                .drop(1)
                .collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val refreshed = withContext(Dispatchers.IO) {
                val snapshot = policyEngine.diagnosticsSnapshot()
                val lastApplied = if (snapshot.lastAppliedAtMs > 0L) {
                    DateFormat.getDateTimeInstance().format(Date(snapshot.lastAppliedAtMs))
                } else {
                    "never"
                }
                DashboardUiState(
                    isDeviceOwner = policyEngine.isDeviceOwner(),
                    isLoading = false,
                    protectionActive = appLockManager.isProtectionEnabled(),
                    vpnLocked = snapshot.vpnActive,
                    totalBlockedApps = (snapshot.budgetBlockedPackages + snapshot.lastSuspendedPackages).size,
                    activeSchedules = snapshot.scheduleBlockedGroups.size,
                    activeEmergencySessions = 0,
                    blockedGroups = emptyList(),
                    strictActiveGroups = if (snapshot.scheduleStrictActive) snapshot.scheduleBlockedGroups.size else 0,
                    lastApplied = lastApplied,
                    lastError = snapshot.lastError,
                    usageAccessGranted = snapshot.usageAccessGranted,
                    usageAccessRecoveryLockdownActive = snapshot.usageAccessRecoveryLockdownActive,
                    usageAccessRecoveryReason = snapshot.usageAccessRecoveryReason,
                    adminSessionActive = appLockManager.isAdminSessionActive(),
                    adminSessionRemainingMs = appLockManager.getSessionRemainingMs()
                )
            }

            _uiState.value = refreshed
        }
    }

    fun applyNow() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                UsageAccessMonitor.refreshNow(
                    context = appContext,
                    reason = "dashboard_apply_now",
                    requestPolicyRefreshIfChanged = false
                )
                policyEngine.requestApplyNow(null, "ui_reapply")
            }
            refresh()
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
