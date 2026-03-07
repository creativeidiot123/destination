package com.ankit.destination.ui.apps

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ankit.destination.budgets.BudgetOrchestrator
import com.ankit.destination.data.FocusDatabase
import com.ankit.destination.policy.EffectiveBlockReason
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
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
    private val _uiState = MutableStateFlow(IndividualAppsUiState())
    val uiState: StateFlow<IndividualAppsUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
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
        refresh()
        if (pendingPkg != null) {
            navigate(pendingPkg)
        }
    }
}

private fun blockMessageFor(rawReason: String?, blocked: Boolean): String? {
    if (!blocked) return null
    return when {
        rawReason == EffectiveBlockReason.ALWAYS_BLOCKED.name -> "Always blocked"
        rawReason == EffectiveBlockReason.STRICT_INSTALL.name -> "Blocked during strict schedule"
        rawReason == "SCHEDULE_GROUP" || rawReason?.endsWith("_SCHEDULED_BLOCK") == true -> {
            "Scheduled block active"
        }
        rawReason == "BUDGET" ||
            rawReason?.endsWith("_HOURLY_CAP") == true ||
            rawReason?.endsWith("_DAILY_CAP") == true -> "Usage limit reached"
        rawReason?.endsWith("_OPENS_CAP") == true -> "Launch limit reached"
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
