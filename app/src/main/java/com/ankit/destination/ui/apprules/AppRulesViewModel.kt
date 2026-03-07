package com.ankit.destination.ui.apprules

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

data class AppRuleItem(
    val packageName: String,
    val label: String,
    val isAllowlist: Boolean,
    val isBlocklist: Boolean,
    val isUninstallProtected: Boolean,
    val isLocked: Boolean = false
)

enum class AppRuleCategory {
    ALLOWLIST,
    BLOCKLIST,
    UNINSTALL_PROTECTION
}

internal fun AppRuleItem.belongsTo(category: AppRuleCategory): Boolean {
    return when (category) {
        AppRuleCategory.ALLOWLIST -> isAllowlist
        AppRuleCategory.BLOCKLIST -> isBlocklist
        AppRuleCategory.UNINSTALL_PROTECTION -> isUninstallProtected
    }
}

data class AppRulesUiState(
    val rules: List<AppRuleItem> = emptyList(),
    val availableApps: List<AppOption> = emptyList(),
    val alwaysAllowedPackages: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val adminSessionActive: Boolean = false,
    val adminSessionRemainingMs: Long = 0,
    val showAuthDialog: Boolean = false,
    val statusMessage: String? = null,
    val isError: Boolean = false
)

class AppRulesViewModel(
    private val appContext: Context,
    private val policyEngine: PolicyEngine,
    private val appLockManager: AppLockManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppRulesUiState())
    val uiState: StateFlow<AppRulesUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val previous = _uiState.value
            _uiState.update { it.copy(isLoading = true) }
            val adminActive = appLockManager.isAdminSessionActive()
            val remainingMs = appLockManager.getSessionRemainingMs()

            val loaded = withContext(Dispatchers.IO) {
                val allow = policyEngine.getAlwaysAllowedAppsAsync()
                val block = policyEngine.getAlwaysBlockedAppsAsync()
                val uninstall = policyEngine.getUninstallProtectedAppsAsync()
                val allPackages = allow + block + uninstall
                val lockedAllowlistPackages = setOf(appContext.packageName)
                val appOptions = loadInstalledAppOptions(
                    context = appContext,
                    includePackageNames = allPackages,
                    disabledPackageReasons = lockedAllowlistPackages.associateWith {
                        "Locked allowlist app"
                    }
                )
                val labelByPackage = appOptions.associateBy({ it.packageName }, { it.label })
                val items = allPackages
                    .map { packageName ->
                        AppRuleItem(
                            packageName = packageName,
                            label = labelByPackage[packageName] ?: packageName,
                            isAllowlist = allow.contains(packageName),
                            isBlocklist = block.contains(packageName),
                            isUninstallProtected = uninstall.contains(packageName),
                            isLocked = packageName == appContext.packageName && allow.contains(packageName)
                        )
                    }
                    .sortedBy { it.label.lowercase() }
                AppRulesUiState(
                    rules = items,
                    availableApps = appOptions,
                    alwaysAllowedPackages = allow,
                    isLoading = false,
                    adminSessionActive = adminActive,
                    adminSessionRemainingMs = remainingMs
                )
            }

            _uiState.value = loaded.copy(
                statusMessage = previous.statusMessage,
                isError = previous.isError
            )
        }
    }

    fun addRules(category: AppRuleCategory, packageNames: Set<String>) {
        if (requiresAuthForAdd(category)) {
            _uiState.update { it.copy(showAuthDialog = true) }
            return
        }
        viewModelScope.launch {
            val sanitizedPackages = when (category) {
                AppRuleCategory.BLOCKLIST -> {
                    packageNames.filterNot(_uiState.value.alwaysAllowedPackages::contains).toSet()
                }
                AppRuleCategory.UNINSTALL_PROTECTION -> {
                    packageNames.filterNot { it == appContext.packageName }.toSet()
                }
                else -> packageNames
            }
            if (sanitizedPackages.isEmpty()) {
                _uiState.update {
                    it.copy(
                        statusMessage = when (category) {
                            AppRuleCategory.BLOCKLIST -> "Always allowed apps cannot be blocked."
                            AppRuleCategory.UNINSTALL_PROTECTION -> "Destination cannot be added to uninstall protection."
                            AppRuleCategory.ALLOWLIST -> "No apps selected."
                        },
                        isError = false
                    )
                }
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    sanitizedPackages.forEach { packageName ->
                        when (category) {
                            AppRuleCategory.ALLOWLIST -> policyEngine.addAlwaysAllowedAppAsync(packageName)
                            AppRuleCategory.BLOCKLIST -> policyEngine.addAlwaysBlockedAppAsync(packageName)
                            AppRuleCategory.UNINSTALL_PROTECTION -> {
                                policyEngine.addUninstallProtectedAppAsync(packageName)
                            }
                        }
                    }
                    policyEngine.requestApplyNow(reason = "app_rules_add")
                }
            }
            handleMutationResult(result, "Rules updated.")
        }
    }

    fun removeRule(category: AppRuleCategory, rule: AppRuleItem) {
        if (category == AppRuleCategory.ALLOWLIST && rule.isLocked) {
            _uiState.update {
                it.copy(statusMessage = "Destination stays locked in the allowlist.", isError = false)
            }
            return
        }
        if (requiresAuthForRemove()) {
            _uiState.update { it.copy(showAuthDialog = true) }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (category) {
                        AppRuleCategory.ALLOWLIST -> policyEngine.removeAlwaysAllowedAppAsync(rule.packageName)
                        AppRuleCategory.BLOCKLIST -> policyEngine.removeAlwaysBlockedAppAsync(rule.packageName)
                        AppRuleCategory.UNINSTALL_PROTECTION -> {
                            policyEngine.removeUninstallProtectedAppAsync(rule.packageName)
                        }
                    }
                    policyEngine.requestApplyNow(reason = "app_rules_remove")
                }
            }
            handleMutationResult(result, "Rule removed.")
        }
    }

    fun dismissAuthDialog() {
        _uiState.update { it.copy(showAuthDialog = false) }
    }

    fun onAuthenticated() {
        dismissAuthDialog()
        _uiState.update {
            it.copy(
                adminSessionActive = appLockManager.isAdminSessionActive(),
                adminSessionRemainingMs = appLockManager.getSessionRemainingMs(),
                statusMessage = "Authenticated. Retry the rule change.",
                isError = false
            )
        }
    }

    private fun handleMutationResult(result: Result<*>, successMessage: String) {
        result.onSuccess {
            _uiState.update { it.copy(statusMessage = successMessage, isError = false) }
            refresh()
        }.onFailure { throwable ->
            _uiState.update {
                it.copy(
                    statusMessage = throwable.message ?: "Failed to update app rules.",
                    isError = true
                )
            }
        }
    }

    private fun requiresAuthForAdd(category: AppRuleCategory): Boolean {
        if (category == AppRuleCategory.BLOCKLIST) return false
        return !_uiState.value.adminSessionActive && appLockManager.isProtectionEnabled()
    }

    private fun requiresAuthForRemove(): Boolean {
        return !_uiState.value.adminSessionActive && appLockManager.isProtectionEnabled()
    }
}

class AppRulesViewModelFactory(
    private val appContext: Context,
    private val policyEngine: PolicyEngine,
    private val appLockManager: AppLockManager
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return AppRulesViewModel(appContext, policyEngine, appLockManager) as T
    }
}
