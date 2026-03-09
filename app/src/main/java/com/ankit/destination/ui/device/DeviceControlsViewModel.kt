package com.ankit.destination.ui.device

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ankit.destination.data.GlobalControls
import com.ankit.destination.data.ManagedNetworkModeSetting
import com.ankit.destination.enforce.PolicyApplyOrchestrator
import com.ankit.destination.policy.ApplyTrigger
import com.ankit.destination.policy.ApplyTriggerCategory
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.RefreshCoordinator
import com.ankit.destination.ui.UiInvalidationBus
import com.ankit.destination.ui.runCatchingNonCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AuthReason {
    TOGGLE_PROTECTION,
    TOGGLE_SETTING,
    SAVE_NETWORK,
    TOGGLE_VPN_LOCKDOWN
}

data class DeviceControlsUiState(
    val protectionActive: Boolean = false,
    val hasPasswordSet: Boolean = false,
    val isDeviceOwner: Boolean = false,
    val isLoading: Boolean = true,

    val lockTimeEnabled: Boolean = false,
    val lockDnsVpnEnabled: Boolean = false,
    val lockDevOptionsEnabled: Boolean = false,
    val disableSafeModeEnabled: Boolean = false,
    val lockUserCreationEnabled: Boolean = false,
    val lockWorkProfileEnabled: Boolean = false,
    val lockCloningEnabled: Boolean = false,

    val managedNetworkMode: ManagedNetworkModeSetting = ManagedNetworkModeSetting.UNMANAGED,
    val managedVpnPackage: String = "",
    val managedVpnLockdown: Boolean = true,
    val privateDnsHost: String = "",

    val blockedGroups: Int = 0,
    val blockedApps: Int = 0,
    val strictGroups: Int = 0,
    val currentReason: String? = null,

    val adminSessionActive: Boolean = false,
    val adminSessionRemainingMs: Long = 0,

    val showAuthDialog: Boolean = false,
    val authReason: AuthReason? = null,
    val pendingSettingKey: String? = null,
    val pendingSettingValue: Boolean? = null,
    val pendingVpnLockdownValue: Boolean? = null,
    val showSetPasswordDialog: Boolean = false,
    val statusMessage: String? = null,
    val isError: Boolean = false
)

class DeviceControlsViewModel(
    private val appContext: Context,
    private val policyEngine: PolicyEngine,
    private val appLockManager: AppLockManager
) : ViewModel() {
    private val refreshCoordinator = RefreshCoordinator()
    private val _uiState = MutableStateFlow(DeviceControlsUiState())
    val uiState: StateFlow<DeviceControlsUiState> = _uiState.asStateFlow()
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
                    val refreshed = withContext(Dispatchers.IO) {
                        runCatchingNonCancellation {
                            val controls = policyEngine.getGlobalControlsAsync()
                            val snapshot = policyEngine.uiSnapshot()
                            DeviceControlsUiState(
                                isLoading = false,
                                protectionActive = appLockManager.isProtectionEnabled(),
                                hasPasswordSet = appLockManager.isPasswordSet(),
                                isDeviceOwner = policyEngine.isDeviceOwner(),
                                lockTimeEnabled = controls.lockTime,
                                lockDnsVpnEnabled = controls.lockVpnDns,
                                lockDevOptionsEnabled = controls.lockDevOptions,
                                disableSafeModeEnabled = controls.disableSafeMode,
                                lockUserCreationEnabled = controls.lockUserCreation,
                                lockWorkProfileEnabled = controls.lockWorkProfile,
                                lockCloningEnabled = controls.lockCloningBestEffort,
                                managedNetworkMode = runCatching {
                                    ManagedNetworkModeSetting.valueOf(controls.managedNetworkMode)
                                }.getOrDefault(ManagedNetworkModeSetting.UNMANAGED),
                                managedVpnPackage = controls.managedVpnPackage.orEmpty(),
                                managedVpnLockdown = controls.managedVpnLockdown,
                                privateDnsHost = controls.privateDnsHost.orEmpty(),
                                blockedGroups = (snapshot.scheduleBlockedGroups + snapshot.budgetBlockedGroupIds).size,
                                blockedApps = (snapshot.budgetBlockedPackages + snapshot.lastSuspendedPackages).size,
                                strictGroups = snapshot.scheduleBlockedGroups.size,
                                currentReason = snapshot.currentLockReason
                                    ?: snapshot.scheduleLockReason
                                    ?: snapshot.budgetReason,
                                adminSessionActive = appLockManager.isAdminSessionActive(),
                                adminSessionRemainingMs = appLockManager.getSessionRemainingMs()
                            )
                        }
                    }
                    refreshed.onSuccess { state ->
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
                                statusMessage = throwable.message ?: "Failed to load overview controls.",
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

    fun requestProtectionToggle() {
        val state = _uiState.value
        if (state.protectionActive) {
            _uiState.update { it.copy(showAuthDialog = true, authReason = AuthReason.TOGGLE_PROTECTION) }
        } else if (state.hasPasswordSet) {
            if (appLockManager.enableProtection()) {
                UiInvalidationBus.invalidate("protection_state_updated")
                refresh(force = true)
            } else {
                _uiState.update {
                    it.copy(
                        statusMessage = "Failed to enable password protection.",
                        isError = true
                    )
                }
            }
        } else {
            _uiState.update { it.copy(showSetPasswordDialog = true) }
        }
    }

    fun requestSettingToggle(setting: String, newValue: Boolean) {
        if (requiresAuth()) {
            _uiState.update {
                it.copy(
                    showAuthDialog = true,
                    authReason = AuthReason.TOGGLE_SETTING,
                    pendingSettingKey = setting,
                    pendingSettingValue = newValue
                )
            }
            return
        }
        applySettingToggle(setting, newValue)
    }

    fun updateManagedNetworkMode(mode: ManagedNetworkModeSetting) {
        _uiState.update { it.copy(managedNetworkMode = mode, statusMessage = null) }
    }

    fun updateManagedVpnPackage(packageName: String) {
        _uiState.update { it.copy(managedVpnPackage = packageName, statusMessage = null) }
    }

    fun updateManagedVpnLockdown(enabled: Boolean) {
        if (requiresAuth()) {
            _uiState.update {
                it.copy(
                    showAuthDialog = true,
                    authReason = AuthReason.TOGGLE_VPN_LOCKDOWN,
                    pendingVpnLockdownValue = enabled
                )
            }
            return
        }
        _uiState.update { it.copy(managedVpnLockdown = enabled, statusMessage = null) }
    }

    fun updatePrivateDnsHost(hostname: String) {
        _uiState.update { it.copy(privateDnsHost = hostname, statusMessage = null) }
    }

    fun saveNetworkSettings() {
        if (requiresAuth()) {
            _uiState.update { it.copy(showAuthDialog = true, authReason = AuthReason.SAVE_NETWORK) }
            return
        }
        persistNetworkSettings()
    }

    fun dismissAuthDialog() {
        _uiState.update {
            it.copy(
                showAuthDialog = false,
                authReason = null,
                pendingSettingKey = null,
                pendingSettingValue = null,
                pendingVpnLockdownValue = null
            )
        }
    }

    fun dismissSetPasswordDialog() {
        _uiState.update { it.copy(showSetPasswordDialog = false) }
    }

    fun onAuthenticated(password: String) {
        val authReason = _uiState.value.authReason
        val pendingSettingKey = _uiState.value.pendingSettingKey
        val pendingSettingValue = _uiState.value.pendingSettingValue
        val pendingVpnLockdownValue = _uiState.value.pendingVpnLockdownValue
        dismissAuthDialog()
        _uiState.update {
            it.copy(
                adminSessionActive = appLockManager.isAdminSessionActive(),
                adminSessionRemainingMs = appLockManager.getSessionRemainingMs()
            )
        }
        when (authReason) {
            AuthReason.TOGGLE_PROTECTION -> {
                if (appLockManager.disableProtection(password)) {
                    UiInvalidationBus.invalidate("protection_state_updated")
                    _uiState.update {
                        it.copy(statusMessage = "Password protection disabled.", isError = false)
                    }
                    refresh(force = true)
                } else {
                    _uiState.update {
                        it.copy(statusMessage = "Password verification failed.", isError = true)
                    }
                }
            }
            AuthReason.TOGGLE_VPN_LOCKDOWN -> {
                if (pendingVpnLockdownValue != null) {
                    _uiState.update { it.copy(managedVpnLockdown = pendingVpnLockdownValue, statusMessage = null) }
                }
            }
            AuthReason.SAVE_NETWORK -> persistNetworkSettings()
            AuthReason.TOGGLE_SETTING -> {
                if (pendingSettingKey != null && pendingSettingValue != null) {
                    applySettingToggle(pendingSettingKey, pendingSettingValue)
                }
            }
            null -> refresh()
        }
    }

    fun setPassword(password: String) {
        if (appLockManager.createPasswordAndEnableProtection(password)) {
            UiInvalidationBus.invalidate("protection_state_updated")
            _uiState.update {
                it.copy(
                    showSetPasswordDialog = false,
                    statusMessage = "Password protection enabled.",
                    isError = false
                )
            }
            refresh()
        } else {
            _uiState.update {
                it.copy(
                    statusMessage = "Password must be at least 4 characters.",
                    isError = true
                )
            }
        }
    }

    private fun requiresAuth(): Boolean {
        return !_uiState.value.adminSessionActive && _uiState.value.protectionActive
    }

    private fun applySettingToggle(setting: String, newValue: Boolean) {
        updateControls { controls ->
            when (setting) {
                "time" -> controls.copy(lockTime = newValue)
                "dns" -> controls.copy(lockVpnDns = newValue)
                "dev" -> controls.copy(lockDevOptions = newValue)
                "safe" -> controls.copy(disableSafeMode = newValue)
                "user" -> controls.copy(lockUserCreation = newValue)
                "work" -> controls.copy(lockWorkProfile = newValue)
                "clone" -> controls.copy(lockCloningBestEffort = newValue)
                else -> controls
            }
        }
    }

    private fun persistNetworkSettings() {
        val state = _uiState.value
        updateControls { controls ->
            when (state.managedNetworkMode) {
                ManagedNetworkModeSetting.UNMANAGED -> controls.copy(
                    managedNetworkMode = ManagedNetworkModeSetting.UNMANAGED.name,
                    managedVpnPackage = null,
                    privateDnsHost = null
                )
                ManagedNetworkModeSetting.FORCED_VPN -> controls.copy(
                    managedNetworkMode = ManagedNetworkModeSetting.FORCED_VPN.name,
                    managedVpnPackage = state.managedVpnPackage.trim().ifBlank { appContext.packageName },
                    managedVpnLockdown = state.managedVpnLockdown,
                    privateDnsHost = null
                )
                ManagedNetworkModeSetting.FORCED_PRIVATE_DNS -> controls.copy(
                    managedNetworkMode = ManagedNetworkModeSetting.FORCED_PRIVATE_DNS.name,
                    privateDnsHost = state.privateDnsHost.trim(),
                    managedVpnPackage = null
                )
            }
        }
    }

    private fun updateControls(transform: (GlobalControls) -> GlobalControls) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = null) }
            val result = withContext(Dispatchers.IO) {
                runCatchingNonCancellation {
                    val updated = transform(policyEngine.getGlobalControlsAsync())
                    policyEngine.setGlobalControlsAsync(updated)
                    PolicyApplyOrchestrator.applyNow(
                        context = appContext,
                        trigger = ApplyTrigger(
                            category = ApplyTriggerCategory.POLICY_MUTATION,
                            source = "device_controls",
                            detail = "overview_controls"
                        )
                    )
                }
            }
            result.onSuccess {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Overview settings saved and applied.",
                        isError = false
                    )
                }
                UiInvalidationBus.invalidate("device_controls_updated")
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = throwable.message ?: "Failed to update controls.",
                        isError = true
                    )
                }
            }
        }
    }
}

class DeviceControlsViewModelFactory(
    private val appContext: Context,
    private val policyEngine: PolicyEngine,
    private val appLockManager: AppLockManager
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return DeviceControlsViewModel(appContext, policyEngine, appLockManager) as T
    }
}
