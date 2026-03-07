package com.ankit.destination.ui.diagnostics

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ankit.destination.policy.DiagnosticsSnapshot
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DiagnosticsPendingAction {
    RESET_APP
}

const val REMOVE_DEVICE_OWNER_CONFIRMATION_TEXT =
    "i am sure i want to remove device owner permission"

data class DiagnosticsUiState(
    val snapshot: DiagnosticsSnapshot? = null,
    val isLoading: Boolean = true,
    val adminSessionActive: Boolean = false,
    val adminSessionRemainingMs: Long = 0,
    val showAuthDialog: Boolean = false,
    val pendingAction: DiagnosticsPendingAction? = null,
    val statusMessage: String? = null,
    val isError: Boolean = false
)

class DiagnosticsViewModel(
    appContext: Context,
    private val policyEngine: PolicyEngine,
    private val appLockManager: AppLockManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val previous = _uiState.value
            _uiState.update { it.copy(isLoading = true) }
            val snapshot = withContext(Dispatchers.IO) { policyEngine.diagnosticsSnapshot() }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    snapshot = snapshot,
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
