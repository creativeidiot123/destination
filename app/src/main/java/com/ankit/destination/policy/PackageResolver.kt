package com.ankit.destination.policy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telecom.TelecomManager

class PackageResolver(private val context: Context) {
    private val packageManager: PackageManager = context.packageManager

    fun resolveAllowlist(
        userChosenEmergencyApps: Set<String>,
        alwaysAllowedApps: Set<String> = emptySet()
    ): AllowlistResolution {
        val allowlist = linkedSetOf<String>()
        val reasons = linkedMapOf<String, String>()

        addPackage(allowlist, reasons, context.packageName, "controller app")

        alwaysAllowedApps
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { pkg ->
                if (isInstalled(pkg)) {
                    addPackage(allowlist, reasons, pkg, "always allowed app")
                }
            }

        userChosenEmergencyApps
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { pkg ->
                if (isInstalled(pkg)) {
                    addPackage(allowlist, reasons, pkg, "user emergency app")
                }
            }

        resolveDefaultDialer()?.let { addPackage(allowlist, reasons, it, "default dialer") }
        resolveDefaultSms()?.let { addPackage(allowlist, reasons, it, "default sms") }
        resolveDefaultLauncher()?.let { addPackage(allowlist, reasons, it, "default launcher") }
        resolvePermissionController()?.let {
            addPackage(allowlist, reasons, it, "permission controller")
        }

        return AllowlistResolution(packages = allowlist, reasons = reasons)
    }

    fun computeSuspendTargets(allowlist: Set<String>): Set<String> {
        val launchables = resolveLaunchablePackages()
        return launchables
            .asSequence()
            .filterNot { allowlist.contains(it) }
            .filterNot { isProtectedPackage(it) }
            .toSet()
    }

    fun filterSuspendable(packages: Set<String>, allowlist: Set<String>): Set<String> {
        return packages
            .asSequence()
            .filterNot { allowlist.contains(it) }
            .filterNot { isProtectedPackage(it) }
            .toSet()
    }

    fun resolveLaunchablePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .toSet()
    }

    fun isPackageInstalled(packageName: String): Boolean = isInstalled(packageName)

    fun packageLabelOrPackage(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private fun resolveDefaultDialer(): String? {
        val telecom = context.getSystemService(TelecomManager::class.java)
        return telecom?.defaultDialerPackage
    }

    private fun resolveDefaultSms(): String? {
        return runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
    }

    private fun resolveDefaultLauncher(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
    }

    private fun resolvePermissionController(): String? {
        val permissionIntent = Intent("android.intent.action.MANAGE_APP_PERMISSIONS")
        return packageManager.resolveActivity(permissionIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
    }

    private fun isInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isProtectedPackage(packageName: String): Boolean {
        return isProtectedPackageName(packageName, context.packageName)
    }

    private fun addPackage(
        allowlist: MutableSet<String>,
        reasons: MutableMap<String, String>,
        packageName: String,
        reason: String
    ) {
        allowlist += packageName
        reasons.putIfAbsent(packageName, reason)
    }

    companion object {
        internal fun isProtectedPackageName(
            packageName: String,
            controllerPackageName: String
        ): Boolean {
            return packageName == controllerPackageName ||
                FocusConfig.protectedExactPackages.contains(packageName) ||
                FocusConfig.protectedPackagePrefixes.any { prefix -> packageName.startsWith(prefix) }
        }
    }
}



