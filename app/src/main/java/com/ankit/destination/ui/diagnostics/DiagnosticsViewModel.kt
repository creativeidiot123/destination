package com.ankit.destination.ui.diagnostics

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ankit.destination.enforce.PolicyApplyOrchestrator
import com.ankit.destination.policy.DiagnosticsSnapshot
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.AppOption
import com.ankit.destination.ui.UiInvalidationBus
import com.ankit.destination.ui.loadInstalledAppOptions
import com.ankit.destination.ui.runCatchingNonCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class DiagnosticsPendingAction {
    RESET_APP,
    ADD_HIDDEN_APPS,
    REMOVE_HIDDEN_APP,
    EXPORT_BACKUP,
    IMPORT_BACKUP
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
    private val backupManager = DiagnosticsBackupManager(appContext)
    private val operationMutex = Mutex()

    private var pendingHiddenAddPackages: Set<String> = emptySet()
    private var pendingHiddenRemovePackage: String? = null
    private var pendingBackupExportUri: Uri? = null
    private var pendingBackupImportUri: Uri? = null

    fun refresh(preserveStatusMessage: Boolean = true) {
        launchSerialized {
            val previous = _uiState.value
            _uiState.update { it.copy(isLoading = true) }
            val loaded = withContext(Dispatchers.IO) {
                runCatchingNonCancellation {
                    val snapshot = policyEngine.diagnosticsSnapshot()
                    val protectionSnapshot = policyEngine.getAppProtectionSnapshotAsync()
                    val hiddenRows = policyEngine.getHiddenAppsAsync()
                    val hiddenPackages = hiddenRows.map { it.packageName }.toSet()
                    val hiddenOptions = loadInstalledAppOptions(
                        context = appContext,
                        includePackageNames = hiddenPackages,
                        launchableOnly = false,
                        includeHiddenApps = true,
                        protectionSnapshot = protectionSnapshot
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
                        launchableOnly = false,
                        protectionSnapshot = protectionSnapshot
                    )
                    Triple(snapshot, hiddenApps, availableHiddenAppOptions)
                }
            }
            loaded.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        snapshot = result.first,
                        hiddenApps = result.second,
                        availableHiddenAppOptions = result.third,
                        adminSessionActive = appLockManager.isAdminSessionActive(),
                        adminSessionRemainingMs = appLockManager.getSessionRemainingMs(),
                        statusMessage = previous.statusMessage.takeIf { preserveStatusMessage },
                        isError = previous.isError && preserveStatusMessage
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        adminSessionActive = appLockManager.isAdminSessionActive(),
                        adminSessionRemainingMs = appLockManager.getSessionRemainingMs(),
                        statusMessage = throwable.message ?: "Failed to load diagnostics.",
                        isError = true
                    )
                }
            }
        }
    }

    fun requestResetApp() {
        requestAction(DiagnosticsPendingAction.RESET_APP)
    }

    fun setHiddenSuspendPrototypeEnabled(enabled: Boolean) {
        launchSerialized {
            _uiState.update { it.copy(isLoading = true, statusMessage = null) }
            val result = withContext(Dispatchers.IO) {
                runCatchingNonCancellation {
                    policyEngine.setHiddenSuspendPrototypeEnabled(enabled)
                }
            }
            result.onSuccess {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = if (enabled) {
                            "Hidden suspend prototype enabled. Future suspensions will try the custom dialog first."
                        } else {
                            "Hidden suspend prototype disabled. Future suspensions will use the stable DPM path only."
                        },
                        isError = false,
                        adminSessionActive = appLockManager.isAdminSessionActive(),
                        adminSessionRemainingMs = appLockManager.getSessionRemainingMs()
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = throwable.message ?: "Failed to update suspend prototype setting.",
                        isError = true
                    )
                }
            }
            refresh()
        }
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

    fun exportBackup(uri: Uri) {
        pendingBackupExportUri = uri
        requestAction(DiagnosticsPendingAction.EXPORT_BACKUP)
    }

    fun importBackup(uri: Uri) {
        pendingBackupImportUri = uri
        requestAction(DiagnosticsPendingAction.IMPORT_BACKUP)
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

    fun dismissAuthDialog(clearPendingPayload: Boolean = true) {
        if (clearPendingPayload) {
            when (_uiState.value.pendingAction) {
                DiagnosticsPendingAction.ADD_HIDDEN_APPS -> pendingHiddenAddPackages = emptySet()
                DiagnosticsPendingAction.REMOVE_HIDDEN_APP -> pendingHiddenRemovePackage = null
                DiagnosticsPendingAction.EXPORT_BACKUP -> pendingBackupExportUri = null
                DiagnosticsPendingAction.IMPORT_BACKUP -> pendingBackupImportUri = null
                else -> Unit
            }
        }
        _uiState.update { it.copy(showAuthDialog = false, pendingAction = null) }
    }

    fun onAuthenticated() {
        val pendingAction = _uiState.value.pendingAction
        dismissAuthDialog(clearPendingPayload = false)
        when (pendingAction) {
            DiagnosticsPendingAction.RESET_APP -> performResetApp()
            DiagnosticsPendingAction.ADD_HIDDEN_APPS -> performAddHiddenApps()
            DiagnosticsPendingAction.REMOVE_HIDDEN_APP -> performRemoveHiddenApp()
            DiagnosticsPendingAction.EXPORT_BACKUP -> performExportBackup()
            DiagnosticsPendingAction.IMPORT_BACKUP -> performImportBackup()
            null -> Unit
        }
    }

    private fun requestAction(action: DiagnosticsPendingAction) {
        if (_uiState.value.isLoading) {
            _uiState.update {
                it.copy(
                    statusMessage = "Wait for the current diagnostics operation to finish.",
                    isError = false
                )
            }
            return
        }
        if (!_uiState.value.adminSessionActive && appLockManager.isProtectionEnabled()) {
            _uiState.update { it.copy(showAuthDialog = true, pendingAction = action) }
            return
        }
        when (action) {
            DiagnosticsPendingAction.RESET_APP -> performResetApp()
            DiagnosticsPendingAction.ADD_HIDDEN_APPS -> performAddHiddenApps()
            DiagnosticsPendingAction.REMOVE_HIDDEN_APP -> performRemoveHiddenApp()
            DiagnosticsPendingAction.EXPORT_BACKUP -> performExportBackup()
            DiagnosticsPendingAction.IMPORT_BACKUP -> performImportBackup()
        }
    }

    private fun performResetApp() {
        launchSerialized {
            _uiState.update { it.copy(isLoading = true, statusMessage = null) }
            val result = withContext(Dispatchers.IO) {
                policyEngine.resetToFreshState(reason = "diagnostics_reset")
            }
            val completedWithIssues = result.message.startsWith("Reset completed", ignoreCase = true)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    statusMessage = result.message,
                    isError = !result.success && !completedWithIssues,
                    adminSessionActive = appLockManager.isAdminSessionActive(),
                    adminSessionRemainingMs = appLockManager.getSessionRemainingMs()
                )
            }
            if (result.success || completedWithIssues) {
                UiInvalidationBus.invalidate("diagnostics_reset")
            }
            refresh(preserveStatusMessage = !result.success && !completedWithIssues)
        }
    }

    private fun performAddHiddenApps() {
        val packages = pendingHiddenAddPackages
        pendingHiddenAddPackages = emptySet()
        if (packages.isEmpty()) return
        launchSerialized {
            _uiState.update { it.copy(isLoading = true, statusMessage = null) }
            val result = withContext(Dispatchers.IO) {
                runCatchingNonCancellation {
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
                UiInvalidationBus.invalidate("diagnostics_hidden_apps_updated")
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
        launchSerialized {
            _uiState.update { it.copy(isLoading = true, statusMessage = null) }
            val result = withContext(Dispatchers.IO) {
                runCatchingNonCancellation {
                    val removed = policyEngine.removeHiddenAppAsync(packageName)
                    check(removed) { "Hidden app is locked and cannot be removed." }
                    PolicyApplyOrchestrator.applyNow(
                        context = appContext,
                        reason = "diagnostics_remove_hidden"
                    )
                }
            }
            result.onSuccess {
                UiInvalidationBus.invalidate("diagnostics_hidden_apps_updated")
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
        launchSerialized {
            _uiState.update { it.copy(isLoading = true, statusMessage = null) }
            val result = withContext(Dispatchers.IO) {
                policyEngine.removeDeviceOwner(reason = "diagnostics_remove_owner")
            }
            result.onSuccess {
                UiInvalidationBus.invalidate("diagnostics_remove_owner")
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

    private fun performExportBackup() {
        val uri = pendingBackupExportUri
        pendingBackupExportUri = null
        if (uri == null) return
        val destinationLabel = uri.backupLocationLabel()
        launchSerialized {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    statusMessage = destinationLabel?.let { label -> "Exporting backup to $label..." }
                        ?: "Exporting backup...",
                    isError = false
                )
            }
            val result = withContext(Dispatchers.IO) {
                runCatchingNonCancellation { backupManager.exportToUri(uri) }
            }
            result.onSuccess {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = destinationLabel?.let { label ->
                            "Backup exported successfully to $label."
                        } ?: "Backup exported successfully. Check your selected file location.",
                        isError = false,
                        adminSessionActive = appLockManager.isAdminSessionActive(),
                        adminSessionRemainingMs = appLockManager.getSessionRemainingMs()
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = throwable.message ?: "Failed to export backup.",
                        isError = true
                    )
                }
            }
        }
    }

    private fun performImportBackup() {
        val uri = pendingBackupImportUri
        pendingBackupImportUri = null
        if (uri == null) return
        val sourceLabel = uri.backupLocationLabel()
        launchSerialized {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    statusMessage = sourceLabel?.let { label -> "Importing backup from $label..." }
                        ?: "Importing backup...",
                    isError = false
                )
            }
            val importResult = withContext(Dispatchers.IO) {
                runCatchingNonCancellation { backupManager.importFromUri(uri, policyEngine) }
            }
            importResult.onSuccess {
                _uiState.update {
                    it.copy(
                        statusMessage = "Backup imported. Reapplying policies...",
                        isError = false
                    )
                }
                val applyResult = withContext(Dispatchers.IO) {
                    runCatchingNonCancellation {
                        PolicyApplyOrchestrator.applyNow(
                            context = appContext,
                            reason = "diagnostics_import_backup"
                        )
                    }
                }
                applyResult.onSuccess {
                    UiInvalidationBus.invalidate("diagnostics_backup_imported")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = sourceLabel?.let { label ->
                                "Backup imported from $label and policies reapplied successfully."
                            } ?: "Backup imported and policies reapplied successfully.",
                            isError = false,
                            adminSessionActive = appLockManager.isAdminSessionActive(),
                            adminSessionRemainingMs = appLockManager.getSessionRemainingMs()
                        )
                    }
                }.onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = "Backup imported, but policy reapply failed: ${throwable.message ?: "unknown error"}",
                            isError = true,
                            adminSessionActive = appLockManager.isAdminSessionActive(),
                            adminSessionRemainingMs = appLockManager.getSessionRemainingMs()
                        )
                    }
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = throwable.message ?: "Failed to import backup.",
                        isError = true
                    )
                }
            }
            refresh()
        }
    }

    private fun launchSerialized(block: suspend () -> Unit) {
        viewModelScope.launch {
            operationMutex.withLock {
                block()
            }
        }
    }

    private fun Uri.backupLocationLabel(): String? {
        return lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.trim()
            ?.takeIf(String::isNotBlank)
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
