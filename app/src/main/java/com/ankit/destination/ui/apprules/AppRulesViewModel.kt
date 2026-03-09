package com.ankit.destination.ui.apprules

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ankit.destination.enforce.PolicyApplyOrchestrator
import com.ankit.destination.policy.ApplyTrigger
import com.ankit.destination.policy.ApplyTriggerCategory
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.AppOption
import com.ankit.destination.ui.RefreshCoordinator
import com.ankit.destination.ui.UiInvalidationBus
import com.ankit.destination.ui.loadInstalledAppOptions
import com.ankit.destination.ui.runCatchingNonCancellation
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
    private val refreshCoordinator = RefreshCoordinator()
    private val _uiState = MutableStateFlow(AppRulesUiState())
    val uiState: StateFlow<AppRulesUiState> = _uiState.asStateFlow()
    private var hasLoadedOnce = false
    private var lastHandledInvalidationVersion = UiInvalidationBus.latest.value.version

    init {
        refresh(force = true)
    }

    fun refresh(force: Boolean = false) {
        if (!refreshCoordinator.tryStart(force)) return

        viewModelScope.launch {
            var shouldRerun: Boolean
            do {
                var loadSucceeded = false
                try {
                    val previous = _uiState.value
                    if (!hasLoadedOnce) {
                        _uiState.update { it.copy(isLoading = true) }
                    }
                    val loaded = withContext(Dispatchers.IO) {
                        runCatchingNonCancellation {
                            val allow = policyEngine.getAlwaysAllowedAppsAsync()
                            val block = policyEngine.getAlwaysBlockedAppsAsync()
                            val uninstall = policyEngine.getUninstallProtectedAppsAsync()
                            val hiddenPackages = policyEngine.getHiddenAppsAsync()
                                .asSequence()
                                .map { it.packageName }
                                .toSet()
                            val allPackages = (allow + block + uninstall) - hiddenPackages
                            val protectionSnapshot = policyEngine.getAppProtectionSnapshotAsync()
                            val appOptions = loadInstalledAppOptions(
                                context = appContext,
                                includePackageNames = allPackages,
                                protectionSnapshot = protectionSnapshot
                            )
                            val labelByPackage = appOptions.associateBy({ it.packageName }, { it.label })
                            val items = allPackages
                                .map { packageName ->
                                    AppRuleItem(
                                        packageName = packageName,
                                        label = labelByPackage[packageName] ?: packageName,
                                        isAllowlist = allow.contains(packageName),
                                        isBlocklist = block.contains(packageName),
                                        isUninstallProtected = uninstall.contains(packageName)
                                    )
                                }
                                .sortedBy { it.label.lowercase() }
                            AppRulesUiState(
                                rules = items,
                                availableApps = appOptions,
                                isLoading = false,
                                adminSessionActive = appLockManager.isAdminSessionActive(),
                                adminSessionRemainingMs = appLockManager.getSessionRemainingMs()
                            )
                        }
                    }
                    loaded.onSuccess { state ->
                        _uiState.value = state.copy(
                            statusMessage = previous.statusMessage.takeIf { !previous.isError },
                            isError = false
                        )
                        hasLoadedOnce = true
                        loadSucceeded = true
                    }.onFailure { throwable ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                adminSessionActive = appLockManager.isAdminSessionActive(),
                                adminSessionRemainingMs = appLockManager.getSessionRemainingMs(),
                                statusMessage = throwable.message ?: "Failed to load app rules.",
                                isError = true
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

    fun addRules(category: AppRuleCategory, packageNames: Set<String>) {
        if (requiresAuthForAdd(category)) {
            _uiState.update { it.copy(showAuthDialog = true) }
            return
        }
        viewModelScope.launch {
            val protectionSnapshot = withContext(Dispatchers.IO) {
                policyEngine.getAppProtectionSnapshotAsync()
            }
            val sanitizedPackages = when (category) {
                AppRuleCategory.ALLOWLIST -> {
                    packageNames.filterNot(protectionSnapshot::shouldHideFromStandardLists).toSet()
                }
                AppRuleCategory.UNINSTALL_PROTECTION -> {
                    packageNames
                        .filterNot { it == appContext.packageName }
                        .filter(protectionSnapshot::isEligibleForManualPolicy)
                        .toSet()
                }
                AppRuleCategory.BLOCKLIST -> {
                    packageNames.filter(protectionSnapshot::isEligibleForManualPolicy).toSet()
                }
            }
            if (sanitizedPackages.isEmpty()) {
                _uiState.update {
                    it.copy(
                        statusMessage = when (category) {
                            AppRuleCategory.UNINSTALL_PROTECTION -> "Destination cannot be added to uninstall protection."
                            AppRuleCategory.ALLOWLIST -> "No eligible apps selected."
                            AppRuleCategory.BLOCKLIST -> "No eligible apps selected."
                        },
                        isError = false
                    )
                }
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatchingNonCancellation {
                    sanitizedPackages.forEach { packageName ->
                        when (category) {
                            AppRuleCategory.ALLOWLIST -> policyEngine.addAlwaysAllowedAppAsync(packageName)
                            AppRuleCategory.BLOCKLIST -> policyEngine.addAlwaysBlockedAppAsync(packageName)
                            AppRuleCategory.UNINSTALL_PROTECTION -> {
                                policyEngine.addUninstallProtectedAppAsync(packageName)
                            }
                        }
                    }
                    PolicyApplyOrchestrator.applyNow(
                        context = appContext,
                        trigger = ApplyTrigger(
                            category = ApplyTriggerCategory.POLICY_MUTATION,
                            source = "app_rules",
                            detail = "add"
                        )
                    )
                }
            }
            handleMutationResult(result, "Rules updated.")
        }
    }

    fun removeRule(category: AppRuleCategory, rule: AppRuleItem) {
        if (requiresAuthForRemove()) {
            _uiState.update { it.copy(showAuthDialog = true) }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatchingNonCancellation {
                    when (category) {
                        AppRuleCategory.ALLOWLIST -> policyEngine.removeAlwaysAllowedAppAsync(rule.packageName)
                        AppRuleCategory.BLOCKLIST -> policyEngine.removeAlwaysBlockedAppAsync(rule.packageName)
                        AppRuleCategory.UNINSTALL_PROTECTION -> {
                            policyEngine.removeUninstallProtectedAppAsync(rule.packageName)
                        }
                    }
                    PolicyApplyOrchestrator.applyNow(
                        context = appContext,
                        trigger = ApplyTrigger(
                            category = ApplyTriggerCategory.POLICY_MUTATION,
                            source = "app_rules",
                            detail = "remove"
                        )
                    )
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
            UiInvalidationBus.invalidate("app_rules_updated")
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
