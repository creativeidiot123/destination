package com.ankit.destination.ui.apps

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ankit.destination.budgets.BudgetOrchestrator
import com.ankit.destination.data.FocusDatabase
import com.ankit.destination.policy.EffectiveBlockReason
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.RefreshCoordinator
import com.ankit.destination.ui.loadInstalledAppOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppUsageItem(
    val packageName: String,
    val label: String,
    val usageTimeMs: Long,
    val blockMessage: String?,
    val hasCustomRules: Boolean,
    val opensToday: Int
)

data class IndividualAppsUiState(
    val apps: List<AppUsageItem> = emptyList(),
    val isLoading: Boolean = true,
    val adminSessionActive: Boolean = false,
    val adminSessionRemainingMs: Long = 0,
    val showAuthDialog: Boolean = false,
    val pendingActionPkg: String? = null
)

class IndividualAppsViewModel(
    private val appContext: Context,
    private val policyEngine: PolicyEngine,
    private val appLockManager: AppLockManager
) : ViewModel() {
    private val db by lazy { FocusDatabase.get(appContext) }
    private val budgetOrchestrator by lazy { BudgetOrchestrator(appContext) }
    private val refreshCoordinator = RefreshCoordinator()
    private val _uiState = MutableStateFlow(IndividualAppsUiState())
    val uiState: StateFlow<IndividualAppsUiState> = _uiState.asStateFlow()

    fun refresh(force: Boolean = false) {
        if (!refreshCoordinator.tryStart(force)) return

        viewModelScope.launch {
            var shouldRerun: Boolean
            do {
                var loadSucceeded = false
                try {
                    _uiState.update { it.copy(isLoading = true) }
                    val refreshed = withContext(Dispatchers.IO) {
                        val adminActive = appLockManager.isAdminSessionActive()
                        val remainingMs = appLockManager.getSessionRemainingMs()
                        val snapshot = policyEngine.diagnosticsSnapshot()
                        val appPolicies = db.budgetDao().getAllAppPolicies().associateBy { it.packageName }
                        val usageInputs = budgetOrchestrator.readUsageSnapshot().usageInputs
                        val launchableApps = loadInstalledAppOptions(
                            context = appContext,
                            includePackageNames = appPolicies.keys
                        )
                        IndividualAppsUiState(
                            apps = launchableApps
                                .map { option ->
                                    val policy = appPolicies[option.packageName]
                                    val usedToday = usageInputs.usedTodayMs[option.packageName] ?: 0L
                                    val opensToday = usageInputs.opensToday[option.packageName] ?: 0
                                    val rawReason = snapshot.primaryReasonByPackage[option.packageName]
                                    val customRules = policy != null && (
                                        policy.dailyLimitMs > 0L ||
                                            policy.hourlyLimitMs > 0L ||
                                            policy.opensPerDay > 0 ||
                                            policy.emergencyEnabled
                                        )
                                    AppUsageItem(
                                        packageName = option.packageName,
                                        label = option.label,
                                        usageTimeMs = usedToday,
                                        blockMessage = blockMessageFor(
                                            rawReason = rawReason,
                                            blocked = rawReason != null || snapshot.budgetBlockedPackages.contains(option.packageName)
                                        ),
                                        hasCustomRules = customRules,
                                        opensToday = opensToday
                                    )
                                }
                                .sortedWith(
                                    compareByDescending<AppUsageItem> { it.hasCustomRules }
                                        .thenByDescending { it.blockMessage != null }
                                        .thenByDescending { it.usageTimeMs }
                                        .thenBy { it.label.lowercase() }
                                ),
                            isLoading = false,
                            adminSessionActive = adminActive,
                            adminSessionRemainingMs = remainingMs
                        )
                    }

                    _uiState.value = refreshed
                    loadSucceeded = true
                } finally {
                    shouldRerun = refreshCoordinator.finish(loadSucceeded)
                }
            } while (shouldRerun)
        }
    }

    fun attemptEditApp(packageName: String, navigate: (String) -> Unit) {
        if (!_uiState.value.adminSessionActive && appLockManager.isProtectionEnabled()) {
            _uiState.update { it.copy(showAuthDialog = true, pendingActionPkg = packageName) }
            return
        }
        navigate(packageName)
    }

    fun dismissAuthDialog() {
        _uiState.update { it.copy(showAuthDialog = false, pendingActionPkg = null) }
    }

    fun onAuthenticated(navigate: (String) -> Unit) {
        val pendingPkg = _uiState.value.pendingActionPkg
        dismissAuthDialog()
        refresh(force = true)
        if (pendingPkg != null) {
            navigate(pendingPkg)
        }
    }
}

private fun blockMessageFor(rawReason: String?, blocked: Boolean): String? {
    if (!blocked) return null

    val normalized = rawReason
        ?.trim()
        ?.uppercase()
        .orEmpty()

    return when {
        normalized == EffectiveBlockReason.ALWAYS_BLOCKED.name -> "Always blocked"
        normalized == EffectiveBlockReason.STRICT_INSTALL.name -> "Blocked during strict schedule"
        normalized == EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN.name ->
            "Blocked - Usage Access recovery required"
        normalized == "SCHEDULE_GROUP" ||
            normalized.contains("SCHEDULED_BLOCK") ||
            normalized.endsWith("_SCHEDULED_BLOCK") -> {
            "Scheduled block active"
        }
        normalized == "BUDGET" ||
            normalized.contains("USAGE_BLOCK") ||
            normalized.contains("HOURLY_CAP") ||
            normalized.contains("DAILY_CAP") -> "Usage limit reached"
        normalized.contains("OPENS_CAP") -> "Launch limit reached"
        else -> "Blocked"
    }
}

class IndividualAppsViewModelFactory(
    private val appContext: Context,
    private val policyEngine: PolicyEngine,
    private val appLockManager: AppLockManager
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return IndividualAppsViewModel(appContext, policyEngine, appLockManager) as T
    }
}
