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

class DevicePolicyFacade(private val context: Context) {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val packageManager: PackageManager = context.packageManager

    val adminComponent: ComponentName = ComponentName(context, FocusDeviceAdminReceiver::class.java)
    val packageName: String = context.packageName

    fun isAdminActive(): Boolean = dpm.isAdminActive(adminComponent)

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(packageName)

    fun setLockTaskPackages(packages: List<String>) {
        dpm.setLockTaskPackages(adminComponent, packages.toTypedArray())
    }

    fun setLockTaskFeatures(features: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(adminComponent, features)
        }
    }

    fun getLockTaskFeatures(): Int? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.getLockTaskFeatures(adminComponent)
        } else {
            null
        }
    }

    fun getLockTaskPackages(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dpm.getLockTaskPackages(adminComponent).toList()
        } else {
            emptyList()
        }
    }

    fun setStatusBarDisabled(disabled: Boolean): Boolean? {
        return dpm.setStatusBarDisabled(adminComponent, disabled)
    }

    fun setAlwaysOnVpnPackage(vpnPackage: String?, lockdownEnabled: Boolean) {
        dpm.setAlwaysOnVpnPackage(adminComponent, vpnPackage, lockdownEnabled)
    }

    fun getAlwaysOnVpnPackage(): String? {
        return dpm.getAlwaysOnVpnPackage(adminComponent)
    }

    fun isAlwaysOnVpnLockdownEnabled(): Boolean? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dpm.isAlwaysOnVpnLockdownEnabled(adminComponent)
        } else {
            null
        }
    }

    fun setPackagesSuspended(packages: List<String>, suspended: Boolean): PackageSuspendResult {
        if (packages.isEmpty()) {
            return PackageSuspendResult(
                failedPackages = emptySet(),
                errors = emptyList()
            )
        }
        // DPM calls are binder transactions; chunk to avoid TransactionTooLarge on app-heavy devices.
        val failed = linkedSetOf<String>()
        val errors = mutableListOf<String>()
        packages.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .chunked(SUSPEND_CHUNK_SIZE)
            .forEach { chunk ->
                runCatching {
                    dpm.setPackagesSuspended(adminComponent, chunk.toTypedArray(), suspended).toSet()
                }.onSuccess { chunkFailures ->
                    failed += chunkFailures
                }.onFailure {
                    failed += chunk
                    errors += it.message ?: it.javaClass.simpleName
                }
            }
        return PackageSuspendResult(
            failedPackages = failed,
            errors = errors
        )
    }

    fun setUninstallBlocked(packageName: String, blocked: Boolean) {
        dpm.setUninstallBlocked(adminComponent, packageName, blocked)
    }

    fun isUninstallBlocked(packageName: String): Boolean {
        return dpm.isUninstallBlocked(adminComponent, packageName)
    }

    fun addUserRestriction(restriction: String) {
        dpm.addUserRestriction(adminComponent, restriction)
    }

    fun clearUserRestriction(restriction: String) {
        dpm.clearUserRestriction(adminComponent, restriction)
    }

    fun hasUserRestriction(restriction: String): Boolean = userManager.hasUserRestriction(restriction)

    fun setAutoTimeRequired(required: Boolean) {
        dpm.setAutoTimeRequired(adminComponent, required)
    }

    fun isAutoTimeRequired(): Boolean = dpm.autoTimeRequired

    fun canVerifyPackageSuspension(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun isPackageSuspended(packageName: String): Boolean? {
        if (!canVerifyPackageSuspension()) return null
        return try {
            packageManager.isPackageSuspended(packageName)
        } catch (_: NameNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun lockTaskModeState(): Int? = activityManager?.lockTaskModeState

    fun isHomeAppPinnedToSelf(): Boolean? {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(
            android.content.Intent.CATEGORY_HOME
        )
        val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?: return null
        return resolved.activityInfo?.packageName == packageName
    }

    fun setAsHomeForKiosk(enabled: Boolean) {
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

    private companion object {
        private const val SUSPEND_CHUNK_SIZE = 200
    }
}



