package com.ankit.destination.ui.apps

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ankit.destination.budgets.BudgetOrchestrator
import com.ankit.destination.budgets.clampEmergencyMinutesPerUnlock
import com.ankit.destination.budgets.clampEmergencyUnlocksPerDay
import com.ankit.destination.data.AppPolicy
import com.ankit.destination.data.FocusDatabase
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AppDetailEvent {
    data class Toast(val message: String) : AppDetailEvent
    data class Finish(val message: String) : AppDetailEvent
}

data class AppDetailUiState(
    val packageName: String = "",
    val label: String = "",
    val isLoading: Boolean = true,
    val isAlwaysAllowed: Boolean = false,
    val adminSessionActive: Boolean = false,
    val adminSessionRemainingMs: Long = 0,
    val showAuthDialog: Boolean = false,
    val statusMessage: String? = null,
    val isError: Boolean = false,

    val restrictHourly: Boolean = false,
    val hourlyLimitMs: Long = 0,
    val restrictDaily: Boolean = false,
    val dailyLimitMs: Long = 0,
    val restrictOpens: Boolean = false,
    val opensLimit: Int = 0,

    val usedTodayMs: Long = 0,
    val usedHourMs: Long = 0,
    val opensToday: Int = 0,

    val isEmergencyEnabled: Boolean = false,
    val emergencyUnlocksPerDay: Int = 0,
    val emergencyMinutesPerUnlock: Int = 0
)

class AppDetailViewModel(
    private val appContext: Context,
    private val policyEngine: PolicyEngine,
    private val appLockManager: AppLockManager,
    private val packageName: String
) : ViewModel() {
    private val db by lazy { FocusDatabase.get(appContext) }
    private val budgetOrchestrator by lazy { BudgetOrchestrator(appContext) }
    private val _uiState = MutableStateFlow(AppDetailUiState(packageName = packageName))
    val uiState: StateFlow<AppDetailUiState> = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<AppDetailEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events

    fun refresh() {
        viewModelScope.launch {
            val previous = _uiState.value
            _uiState.update { it.copy(isLoading = true) }
            val refreshed = withContext(Dispatchers.IO) {
                val policy = db.budgetDao().getAppPolicy(packageName)
                val usageInputs = budgetOrchestrator.readUsageSnapshot().usageInputs
                val isAlwaysAllowed = policyEngine.getAlwaysAllowedAppsAsync().contains(packageName)
                val label = runCatching {
                    val appInfo = appContext.packageManager.getApplicationInfo(packageName, 0)
                    appContext.packageManager.getApplicationLabel(appInfo).toString()
                }.getOrDefault(packageName)
                AppDetailUiState(
                    packageName = packageName,
                    label = label,
                    isLoading = false,
                    isAlwaysAllowed = isAlwaysAllowed,
                    adminSessionActive = appLockManager.isAdminSessionActive(),
                    adminSessionRemainingMs = appLockManager.getSessionRemainingMs(),
                    restrictHourly = (policy?.hourlyLimitMs ?: 0L) > 0L,
                    hourlyLimitMs = policy?.hourlyLimitMs?.coerceAtLeast(0L) ?: 0L,
                    restrictDaily = (policy?.dailyLimitMs ?: 0L) > 0L,
                    dailyLimitMs = policy?.dailyLimitMs?.coerceAtLeast(0L) ?: 0L,
                    restrictOpens = (policy?.opensPerDay ?: 0) > 0,
                    opensLimit = policy?.opensPerDay?.coerceAtLeast(0) ?: 0,
                    usedTodayMs = usageInputs.usedTodayMs[packageName] ?: 0L,
                    usedHourMs = usageInputs.usedHourMs[packageName] ?: 0L,
                    opensToday = usageInputs.opensToday[packageName] ?: 0,
                    isEmergencyEnabled = policy?.emergencyEnabled ?: false,
                    emergencyUnlocksPerDay = clampEmergencyUnlocksPerDay(policy?.unlocksPerDay ?: 0),
                    emergencyMinutesPerUnlock = clampEmergencyMinutesPerUnlock(policy?.minutesPerUnlock ?: 0)
                )
            }
            _uiState.value = refreshed.copy(
                statusMessage = previous.statusMessage,
                isError = previous.isError
            )
        }
    }

    fun toggleHourlyLimit(enabled: Boolean) {
        _uiState.update { it.copy(restrictHourly = enabled, statusMessage = null) }
    }

    fun toggleDailyLimit(enabled: Boolean) {
        _uiState.update { it.copy(restrictDaily = enabled, statusMessage = null) }
    }

    fun toggleOpensLimit(enabled: Boolean) {
        _uiState.update { it.copy(restrictOpens = enabled, statusMessage = null) }
    }

    fun toggleEmergency(enabled: Boolean) {
        _uiState.update { it.copy(isEmergencyEnabled = enabled, statusMessage = null) }
    }

    fun updateHourlyLimitMinutes(minutes: Int) {
        _uiState.update { it.copy(hourlyLimitMs = minutes.coerceAtLeast(0) * 60_000L, statusMessage = null) }
    }

    fun updateDailyLimitMinutes(minutes: Int) {
        _uiState.update { it.copy(dailyLimitMs = minutes.coerceAtLeast(0) * 60_000L, statusMessage = null) }
    }

    fun updateOpensLimit(opens: Int) {
        _uiState.update { it.copy(opensLimit = opens.coerceAtLeast(0), statusMessage = null) }
    }

    fun updateEmergencyConfig(unlocksPerDay: Int, minutesPerUnlock: Int) {
        _uiState.update {
            it.copy(
                emergencyUnlocksPerDay = clampEmergencyUnlocksPerDay(unlocksPerDay),
                emergencyMinutesPerUnlock = clampEmergencyMinutesPerUnlock(minutesPerUnlock),
                statusMessage = null
            )
        }
    }

    fun requestSave() {
        if (_uiState.value.isAlwaysAllowed) {
            viewModelScope.launch {
                _events.emit(AppDetailEvent.Toast("Always allowed apps cannot be blocked."))
            }
            return
        }
        if (!_uiState.value.adminSessionActive && appLockManager.isProtectionEnabled()) {
            _uiState.update { it.copy(showAuthDialog = true) }
            return
        }
        saveInternal()
    }

    fun dismissAuthDialog() {
        _uiState.update { it.copy(showAuthDialog = false) }
    }

    fun onAuthenticated() {
        dismissAuthDialog()
        saveInternal()
    }

    private fun saveInternal() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = null) }
            val state = _uiState.value
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    db.budgetDao().upsertAppPolicy(
                        AppPolicy(
                            packageName = packageName,
                            dailyLimitMs = if (state.restrictDaily) state.dailyLimitMs else 0L,
                            hourlyLimitMs = if (state.restrictHourly) state.hourlyLimitMs else 0L,
                            opensPerDay = if (state.restrictOpens) state.opensLimit else 0,
                            enabled = state.restrictDaily || state.restrictHourly || state.restrictOpens || state.isEmergencyEnabled,
                            emergencyEnabled = state.isEmergencyEnabled,
                            unlocksPerDay = state.emergencyUnlocksPerDay,
                            minutesPerUnlock = state.emergencyMinutesPerUnlock
                        )
                    )
                    policyEngine.requestApplyNow(reason = "app_detail_save:$packageName")
                }
            }

            result.onSuccess {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "App rules saved and applied.",
                        isError = false,
                        adminSessionActive = appLockManager.isAdminSessionActive(),
                        adminSessionRemainingMs = appLockManager.getSessionRemainingMs()
                    )
                }
                _events.emit(AppDetailEvent.Finish("App rules saved."))
            }.onFailure { throwable ->
                _events.emit(AppDetailEvent.Toast(throwable.message ?: "Failed to save app rules."))
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = throwable.message ?: "Failed to save app rules.",
                        isError = true
                    )
                }
            }
        }
    }
}

class AppDetailViewModelFactory(
    private val appContext: Context,
    private val policyEngine: PolicyEngine,
    private val appLockManager: AppLockManager,
    private val packageName: String
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return AppDetailViewModel(appContext, policyEngine, appLockManager, packageName) as T
    }
}
