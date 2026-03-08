package com.ankit.destination.policy

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.os.Build
import android.os.UserManager
import com.ankit.destination.admin.FocusDeviceAdminReceiver
import com.ankit.destination.ui.MainActivity

data class PackageSuspendResult(
    val failedPackages: Set<String>,
    val errors: List<String>
)

internal interface DevicePolicyClient {
    val adminComponent: ComponentName
    val packageName: String

    fun isAdminActive(): Boolean
    fun isDeviceOwner(): Boolean
    fun clearDeviceOwnerApp()
    fun setBlankDeviceOwnerLockScreenInfo()
    fun setLockTaskPackages(packages: List<String>)
    fun setLockTaskFeatures(features: Int)
    fun getLockTaskFeatures(): Int?
    fun getLockTaskPackages(): List<String>
    fun setStatusBarDisabled(disabled: Boolean): Boolean?
    fun setAlwaysOnVpnPackage(vpnPackage: String?, lockdownEnabled: Boolean)
    fun getAlwaysOnVpnPackage(): String?
    fun isAlwaysOnVpnLockdownEnabled(): Boolean?
    fun getGlobalPrivateDnsMode(): Int?
    fun getGlobalPrivateDnsHost(): String?
    fun supportsGlobalPrivateDns(): Boolean
    fun setGlobalPrivateDnsModeOpportunistic(): Int?
    fun setGlobalPrivateDnsModeSpecifiedHost(host: String): Int?
    fun setPackagesSuspended(packages: List<String>, suspended: Boolean): PackageSuspendResult
    fun setUninstallBlocked(packageName: String, blocked: Boolean)
    fun isUninstallBlocked(packageName: String): Boolean
    fun supportsUserControlDisabledPackages(): Boolean
    fun setUserControlDisabledPackages(packages: List<String>)
    fun getUserControlDisabledPackages(): Set<String>
    fun addUserRestriction(restriction: String)
    fun clearUserRestriction(restriction: String)
    fun hasUserRestriction(restriction: String): Boolean
    fun setAutoTimeRequired(required: Boolean)
    fun isAutoTimeRequired(): Boolean
    fun canVerifyPackageSuspension(): Boolean
    fun isPackageSuspended(packageName: String): Boolean?
    fun lockTaskModeState(): Int?
    fun isHomeAppPinnedToSelf(): Boolean?
    fun setAsHomeForKiosk(enabled: Boolean)
}

internal class DevicePolicyFacade(private val context: Context) : DevicePolicyClient {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val packageManager: PackageManager = context.packageManager
    private val policyStore = PolicyStore(context)

    override val adminComponent: ComponentName = ComponentName(context, FocusDeviceAdminReceiver::class.java)
    override val packageName: String = context.packageName

    private val hiddenPackageSuspendBackend: PackageSuspendBackend by lazy {
        HiddenPackageSuspendBackend(packageManager)
    }
    private val dpmPackageSuspendBackend: PackageSuspendBackend by lazy {
        DpmPackageSuspendBackend(dpm, adminComponent)
    }

    override fun isAdminActive(): Boolean = dpm.isAdminActive(adminComponent)

    override fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(packageName)

    override fun clearDeviceOwnerApp() {
        dpm.clearDeviceOwnerApp(packageName)
    }

    override fun setBlankDeviceOwnerLockScreenInfo() {
        dpm.setDeviceOwnerLockScreenInfo(adminComponent, " ")
    }

    override fun setLockTaskPackages(packages: List<String>) {
        dpm.setLockTaskPackages(adminComponent, packages.toTypedArray())
    }

    override fun setLockTaskFeatures(features: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(adminComponent, features)
        }
    }

    override fun getLockTaskFeatures(): Int? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.getLockTaskFeatures(adminComponent)
        } else {
            null
        }
    }

    override fun getLockTaskPackages(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dpm.getLockTaskPackages(adminComponent).toList()
        } else {
            emptyList()
        }
    }

    override fun setStatusBarDisabled(disabled: Boolean): Boolean? {
        return dpm.setStatusBarDisabled(adminComponent, disabled)
    }

    override fun setAlwaysOnVpnPackage(vpnPackage: String?, lockdownEnabled: Boolean) {
        dpm.setAlwaysOnVpnPackage(adminComponent, vpnPackage, lockdownEnabled)
    }

    override fun getAlwaysOnVpnPackage(): String? {
        return dpm.getAlwaysOnVpnPackage(adminComponent)
    }

    override fun isAlwaysOnVpnLockdownEnabled(): Boolean? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dpm.isAlwaysOnVpnLockdownEnabled(adminComponent)
        } else {
            null
        }
    }

    override fun getGlobalPrivateDnsMode(): Int? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dpm.getGlobalPrivateDnsMode(adminComponent)
        } else {
            null
        }
    }

    override fun getGlobalPrivateDnsHost(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dpm.getGlobalPrivateDnsHost(adminComponent)
        } else {
            null
        }
    }

    override fun supportsGlobalPrivateDns(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun setGlobalPrivateDnsModeOpportunistic(): Int? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dpm.setGlobalPrivateDnsModeOpportunistic(adminComponent)
        } else {
            null
        }
    }

    override fun setGlobalPrivateDnsModeSpecifiedHost(host: String): Int? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dpm.setGlobalPrivateDnsModeSpecifiedHost(adminComponent, host)
        } else {
            null
        }
    }

    override fun setPackagesSuspended(packages: List<String>, suspended: Boolean): PackageSuspendResult {
        if (packages.isEmpty()) {
            return PackageSuspendResult(
                failedPackages = emptySet(),
                errors = emptyList()
            )
        }
        // Package suspension uses binder calls; chunk to avoid TransactionTooLarge on app-heavy devices.
        val failed = linkedSetOf<String>()
        val errors = mutableListOf<String>()
        val backendStatuses = mutableListOf<PackageSuspendBackendStatus>()
        var hiddenPrototypeError: String? = null
        val options = buildPackageSuspendCallOptions(suspended)
        val packageSuspendCoordinator = PackageSuspendCoordinator(
            hiddenBackend = hiddenPackageSuspendBackend.takeIf { policyStore.isHiddenSuspendPrototypeEnabled() },
            dpmBackend = dpmPackageSuspendBackend
        )
        packages.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .chunked(SUSPEND_CHUNK_SIZE)
            .forEach { chunk ->
                runCatching {
                    packageSuspendCoordinator.setPackagesSuspended(chunk, options)
                }.onSuccess { outcome ->
                    failed += outcome.result.failedPackages
                    backendStatuses += outcome.status.backend
                    if (hiddenPrototypeError == null) {
                        hiddenPrototypeError = outcome.status.hiddenErrorMessage
                    }
                    if (outcome.status.backend == PackageSuspendBackendStatus.HIDDEN) {
                        FocusLog.d(
                            FocusEventId.SUSPEND_TARGET,
                            "Hidden suspend prototype used for ${chunk.size} packages suspended=$suspended"
                        )
                    } else if (outcome.status.backend == PackageSuspendBackendStatus.DPM_FALLBACK) {
                        FocusLog.w(
                            FocusEventId.SUSPEND_TARGET,
                            "Hidden suspend prototype fell back to DPM: ${outcome.status.hiddenErrorMessage}"
                        )
                    }
                }.onFailure {
                    failed += chunk
                    errors += it.message ?: it.javaClass.simpleName
                }
            }
        if (backendStatuses.isNotEmpty()) {
            val aggregateBackend = when {
                backendStatuses.contains(PackageSuspendBackendStatus.DPM_FALLBACK) ->
                    PackageSuspendBackendStatus.DPM_FALLBACK
                backendStatuses.contains(PackageSuspendBackendStatus.HIDDEN) ->
                    PackageSuspendBackendStatus.HIDDEN
                else -> PackageSuspendBackendStatus.DPM_ONLY
            }
            policyStore.setLastPackageSuspendPrototypeStatus(
                backend = aggregateBackend,
                errorMessage = hiddenPrototypeError
            )
        }
        return PackageSuspendResult(
            failedPackages = failed,
            errors = errors
        )
    }

    override fun setUninstallBlocked(packageName: String, blocked: Boolean) {
        dpm.setUninstallBlocked(adminComponent, packageName, blocked)
    }

    override fun isUninstallBlocked(packageName: String): Boolean {
        return dpm.isUninstallBlocked(adminComponent, packageName)
    }

    override fun supportsUserControlDisabledPackages(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun setUserControlDisabledPackages(packages: List<String>) {
        if (!supportsUserControlDisabledPackages()) return
        val normalizedPackages = packages.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .toList()
        dpm.setUserControlDisabledPackages(adminComponent, normalizedPackages)
    }

    override fun getUserControlDisabledPackages(): Set<String> {
        if (!supportsUserControlDisabledPackages()) return emptySet()
        return dpm.getUserControlDisabledPackages(adminComponent).toSet()
    }

    override fun addUserRestriction(restriction: String) {
        dpm.addUserRestriction(adminComponent, restriction)
    }

    override fun clearUserRestriction(restriction: String) {
        dpm.clearUserRestriction(adminComponent, restriction)
    }

    override fun hasUserRestriction(restriction: String): Boolean = userManager.hasUserRestriction(restriction)

    override fun setAutoTimeRequired(required: Boolean) {
        dpm.setAutoTimeRequired(adminComponent, required)
    }

    override fun isAutoTimeRequired(): Boolean = dpm.autoTimeRequired

    override fun canVerifyPackageSuspension(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun isPackageSuspended(packageName: String): Boolean? {
        if (!canVerifyPackageSuspension()) return null
        return try {
            packageManager.isPackageSuspended(packageName)
        } catch (_: NameNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    override fun lockTaskModeState(): Int? = activityManager?.lockTaskModeState

    override fun isHomeAppPinnedToSelf(): Boolean? {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(
            android.content.Intent.CATEGORY_HOME
        )
        val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?: return null
        return resolved.activityInfo?.packageName == packageName
    }

    override fun setAsHomeForKiosk(enabled: Boolean) {
        if (enabled) {
            val filter = android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
            }
            val activity = ComponentName(context, MainActivity::class.java)
            dpm.addPersistentPreferredActivity(adminComponent, filter, activity)
        } else {
            dpm.clearPackagePersistentPreferredActivities(adminComponent, packageName)
        }
    }

    companion object {
        private const val SUSPEND_CHUNK_SIZE = 200

        fun privateDnsModeLabel(mode: Int?): String {
            return when (mode) {
                null -> "n/a"
                DevicePolicyManager.PRIVATE_DNS_MODE_OFF -> "off"
                DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC -> "opportunistic"
                DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME -> "provider-hostname"
                else -> "unknown($mode)"
            }
        }
    }
}



