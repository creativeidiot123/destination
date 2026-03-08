package com.ankit.destination.ui.diagnostics

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ankit.destination.enforce.PolicyApplyOrchestrator
import com.ankit.destination.policy.DiagnosticsSnapshot
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.AppOption
import com.ankit.destination.ui.loadInstalledAppOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DiagnosticsPendingAction {
    RESET_APP,
    ADD_HIDDEN_APPS,
    REMOVE_HIDDEN_APP
}

const val REMOVE_DEVICE_OWNER_CONFIRMATION_TEXT =
    "i am sure i want to remove device owner permission"

data class HiddenAppItem(
    val packageName: String,
    val label: String,
    val locked: Boolean
)

data class DiagnosticsUiState(
    val snapshot: DiagnosticsSnapshot? = null,
    val hiddenApps: List<HiddenAppItem> = emptyList(),
    val availableHiddenAppOptions: List<AppOption> = emptyList(),
    val isLoading: Boolean = true,
    val adminSessionActive: Boolean = false,
    val adminSessionRemainingMs: Long = 0,
    val showAuthDialog: Boolean = false,
    val pendingAction: DiagnosticsPendingAction? = null,
    val statusMessage: String? = null,
    val isError: Boolean = false
)

class DiagnosticsViewModel(
    private val appContext: Context,
    private val policyEngine: PolicyEngine,
    private val appLockManager: AppLockManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    private var pendingHiddenAddPackages: Set<String> = emptySet()
    private var pendingHiddenRemovePackage: String? = null

    fun refresh() {
        viewModelScope.launch {
            val previous = _uiState.value
            _uiState.update { it.copy(isLoading = true) }
            val loaded = withContext(Dispatchers.IO) {
                val snapshot = policyEngine.diagnosticsSnapshot()
                val hiddenRows = policyEngine.getHiddenAppsAsync()
                val hiddenPackages = hiddenRows.map { it.packageName }.toSet()
                val hiddenOptions = loadInstalledAppOptions(
                    context = appContext,
                    includePackageNames = hiddenPackages,
                    launchableOnly = false,
                    includeHiddenApps = true
                )
                val labelByPackage = hiddenOptions.associateBy({ it.packageName }, { it.label })
                val hiddenApps = hiddenRows
                    .map { row ->
                        HiddenAppItem(
                            packageName = row.packageName,
                            label = labelByPackage[row.packageName] ?: row.packageName,
                            locked = row.locked
                        )
                    }
                    .sortedBy { it.label.lowercase() }
                val availableHiddenAppOptions = loadInstalledAppOptions(
                    context = appContext,
                    launchableOnly = false
                )
                Triple(snapshot, hiddenApps, availableHiddenAppOptions)
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    snapshot = loaded.first,
                    hiddenApps = loaded.second,
                    availableHiddenAppOptions = loaded.third,
                    adminSessionActive = appLockManager.isAdminSessionActive(),
                    adminSessionRemainingMs = appLockManager.getSessionRemainingMs(),
                    statusMessage = previous.statusMessage,
                    isError = previous.isError
                )
            }
        }
    }

    fun requestResetApp() {
        requestAction(DiagnosticsPendingAction.RESET_APP)
    }

    fun addHiddenApps(packageNames: Set<String>) {
        pendingHiddenAddPackages = packageNames
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        if (pendingHiddenAddPackages.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "No apps selected.", isError = false) }
            return
        }
        requestAction(DiagnosticsPendingAction.ADD_HIDDEN_APPS)
    }

    fun removeHiddenApp(packageName: String) {
        pendingHiddenRemovePackage = packageName.trim().takeIf(String::isNotBlank)
        if (pendingHiddenRemovePackage == null) {
            _uiState.update { it.copy(statusMessage = "Invalid package.", isError = true) }
            return
        }
        requestAction(DiagnosticsPendingAction.REMOVE_HIDDEN_APP)
    }

    fun confirmRemoveDeviceOwner(confirmationText: String, password: String) {
        when {
            confirmationText.trim() != REMOVE_DEVICE_OWNER_CONFIRMATION_TEXT -> {
                _uiState.update {
                    it.copy(
                        statusMessage = "Type the exact confirmation phrase to remove device owner.",
                        isError = true
                    )
                }
            }
            !appLockManager.isPasswordSet() -> {
                _uiState.update {
                    it.copy(
                        statusMessage = "Set a password first before removing device owner.",
                        isError = true
                    )
                }
            }
            !appLockManager.verifyPassword(password) -> {
                _uiState.update {
                    it.copy(
                        statusMessage = "Password verification failed.",
                        isError = true
                    )
                }
            }
            else -> performRemoveDeviceOwner()
        }
    }

    fun dismissAuthDialog() {
        _uiState.update { it.copy(showAuthDialog = false, pendingAction = null) }
    }

    fun onAuthenticated() {
        val pendingAction = _uiState.value.pendingAction
        dismissAuthDialog()
        when (pendingAction) {
            DiagnosticsPendingAction.RESET_APP -> performResetApp()
            DiagnosticsPendingAction.ADD_HIDDEN_APPS -> performAddHiddenApps()
            DiagnosticsPendingAction.REMOVE_HIDDEN_APP -> performRemoveHiddenApp()
            null -> Unit
        }
    }

    private fun requestAction(action: DiagnosticsPendingAction) {
        if (!_uiState.value.adminSessionActive && appLockManager.isProtectionEnabled()) {
            _uiState.update { it.copy(showAuthDialog = true, pendingAction = action) }
            return
        }
        when (action) {
            DiagnosticsPendingAction.RESET_APP -> performResetApp()
            DiagnosticsPendingAction.ADD_HIDDEN_APPS -> performAddHiddenApps()
            DiagnosticsPendingAction.REMOVE_HIDDEN_APP -> performRemoveHiddenApp()
        }
    }

    private fun performResetApp() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = null) }
            val result = withContext(Dispatchers.IO) {
                policyEngine.resetToFreshState(reason = "diagnostics_reset")
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    statusMessage = result.message,
                    isError = !result.success,
                    adminSessionActive = appLockManager.isAdminSessionActive(),
                    adminSessionRemainingMs = appLockManager.getSessionRemainingMs()
                )
            }
            refresh()
        }
    }

    private fun performAddHiddenApps() {
        val packages = pendingHiddenAddPackages
        pendingHiddenAddPackages = emptySet()
        if (packages.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    packages.forEach { packageName ->
                        policyEngine.addHiddenAppAsync(packageName = packageName, locked = false)
                    }
                    PolicyApplyOrchestrator.applyNow(
                        context = appContext,
                        reason = "diagnostics_add_hidden"
                    )
                }
            }
            result.onSuccess {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Hidden apps updated.",
                        isError = false,
                        adminSessionActive = appLockManager.isAdminSessionActive(),
                        adminSessionRemainingMs = appLockManager.getSessionRemainingMs()
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = throwable.message ?: "Failed to add hidden apps.",
                        isError = true
                    )
                }
            }
            refresh()
        }
    }

    private fun performRemoveHiddenApp() {
        val packageName = pendingHiddenRemovePackage
        pendingHiddenRemovePackage = null
        if (packageName.isNullOrBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val removed = policyEngine.removeHiddenAppAsync(packageName)
                    check(removed) { "Hidden app is locked and cannot be removed." }
                    PolicyApplyOrchestrator.applyNow(
                        context = appContext,
                        reason = "diagnostics_remove_hidden"
                    )
                }
            }
            result.onSuccess {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Hidden app removed.",
                        isError = false,
                        adminSessionActive = appLockManager.isAdminSessionActive(),
                        adminSessionRemainingMs = appLockManager.getSessionRemainingMs()
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = throwable.message ?: "Failed to remove hidden app.",
                        isError = true
                    )
                }
            }
            refresh()
        }
    }

    private fun performRemoveDeviceOwner() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = null) }
            val result = withContext(Dispatchers.IO) {
                policyEngine.removeDeviceOwner(reason = "diagnostics_remove_owner")
            }
            result.onSuccess {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Device owner removed.",
                        isError = false
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = throwable.message ?: "Failed to remove device owner.",
                        isError = true
                    )
                }
            }
            refresh()
        }
    }
}

class DiagnosticsViewModelFactory(
    private val appContext: Context,
    private val policyEngine: PolicyEngine,
    private val appLockManager: AppLockManager
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return DiagnosticsViewModel(appContext, policyEngine, appLockManager) as T
    }
}
