package com.ankit.destination.policy

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager

class PackageResolver(private val context: Context) {
    private val packageManager: PackageManager = context.packageManager

    data class UsageAccessRecoveryResolution(
        val packages: Set<String>,
        val settingsPackages: Set<String>,
        val launcherPackages: Set<String>,
        val warnings: List<String>
    )

    fun resolveAllowlist(
        userChosenEmergencyApps: Set<String>,
        alwaysAllowedApps: Set<String> = emptySet()
    ): AllowlistResolution {
        FocusLog.d(FocusEventId.ALLOWLIST_RESOLVE, "┌── resolveAllowlist() emergency=${userChosenEmergencyApps.size} alwaysAllowed=${alwaysAllowedApps.size}")
        val allowlist = linkedSetOf<String>()
        val reasons = linkedMapOf<String, String>()

        addPackage(allowlist, reasons, context.packageName, "controller app")
        FocusLog.v(FocusEventId.ALLOWLIST_RESOLVE, "│ +controller: ${context.packageName}")

        alwaysAllowedApps
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { pkg ->
                if (isInstalled(pkg)) {
                    addPackage(allowlist, reasons, pkg, "always allowed app")
                    FocusLog.v(FocusEventId.ALLOWLIST_RESOLVE, "│ +alwaysAllowed: $pkg")
                } else {
                    FocusLog.v(FocusEventId.ALLOWLIST_RESOLVE, "│ ✗ alwaysAllowed NOT installed: $pkg")
                }
            }

        userChosenEmergencyApps
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { pkg ->
                if (isInstalled(pkg)) {
                    addPackage(allowlist, reasons, pkg, "user emergency app")
                    FocusLog.v(FocusEventId.ALLOWLIST_RESOLVE, "│ +emergency: $pkg")
                } else {
                    FocusLog.v(FocusEventId.ALLOWLIST_RESOLVE, "│ ✗ emergency NOT installed: $pkg")
                }
            }

        val dialer = resolveDefaultDialer()
        dialer?.let { addPackage(allowlist, reasons, it, "default dialer") }
        FocusLog.v(FocusEventId.ALLOWLIST_RESOLVE, "│ defaultDialer=${dialer ?: "none"}")
        val sms = resolveDefaultSms()
        sms?.let { addPackage(allowlist, reasons, it, "default sms") }
        FocusLog.v(FocusEventId.ALLOWLIST_RESOLVE, "│ defaultSms=${sms ?: "none"}")
        val launcher = resolveDefaultLauncher()
        launcher?.let { addPackage(allowlist, reasons, it, "default launcher") }
        FocusLog.v(FocusEventId.ALLOWLIST_RESOLVE, "│ defaultLauncher=${launcher ?: "none"}")
        val permCtrl = resolvePermissionController()
        permCtrl?.let { addPackage(allowlist, reasons, it, "permission controller") }
        FocusLog.v(FocusEventId.ALLOWLIST_RESOLVE, "│ permissionController=${permCtrl ?: "none"}")

        FocusLog.d(FocusEventId.ALLOWLIST_RESOLVE, "└── resolveAllowlist() total=${allowlist.size} pkgs: ${allowlist.joinToString(",")}")
        return AllowlistResolution(packages = allowlist, reasons = reasons)
    }

    fun computeSuspendTargets(allowlist: Set<String>): Set<String> {
        val launchables = resolveLaunchablePackages()
        FocusLog.d(FocusEventId.SUSPEND_TARGET, "computeSuspendTargets: launchable=${launchables.size} allowlist=${allowlist.size}")
        val targets = launchables
            .asSequence()
            .filterNot { allowlist.contains(it) }
            .filterNot { isProtectedPackage(it) }
            .toSet()
        val protectedCount = launchables.size - allowlist.size - targets.size
        FocusLog.d(FocusEventId.SUSPEND_TARGET, "computeSuspendTargets: result=${targets.size} (filtered: allowlisted=${launchables.count { allowlist.contains(it) }} protected≈$protectedCount)")
        return targets
    }

    fun computeUsageAccessRecoverySuspendTargets(recoveryAllowlist: Set<String>): Set<String> {
        return computeUsageAccessRecoverySuspendTargets(
            launchablePackages = resolveLaunchablePackages(),
            recoveryAllowlist = recoveryAllowlist,
            controllerPackageName = context.packageName,
            isNonSystemPackage = ::isNonSystemPackage
        )
    }

    fun filterSuspendable(packages: Set<String>, allowlist: Set<String>): Set<String> {
        val result = packages
            .asSequence()
            .filterNot { pkg ->
                val inAllowlist = allowlist.contains(pkg)
                if (inAllowlist) FocusLog.v(FocusEventId.SUSPEND_TARGET, "filterSuspendable: $pkg skipped (in allowlist)")
                inAllowlist
            }
            .filterNot { pkg ->
                val prot = isProtectedPackage(pkg)
                if (prot) FocusLog.v(FocusEventId.SUSPEND_TARGET, "filterSuspendable: $pkg skipped (protected)")
                prot
            }
            .toSet()
        FocusLog.d(FocusEventId.SUSPEND_TARGET, "filterSuspendable: input=${packages.size} allowlist=${allowlist.size} → suspendable=${result.size}")
        return result
    }

    fun resolveUsageAccessRecoveryPackages(): UsageAccessRecoveryResolution {
        val launcherPackages = resolveLauncherPackages()
        val usageAccessSettingsPackages = resolveIntentPackages(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        )
        val settingsPackages = if (usageAccessSettingsPackages.isNotEmpty()) {
            usageAccessSettingsPackages
        } else {
            resolveFallbackSettingsPackages()
        }
        val warnings = buildList {
            if (usageAccessSettingsPackages.isEmpty()) {
                add("Usage Access Settings screen did not resolve; using generic Settings fallback")
            }
            if (settingsPackages.isEmpty()) {
                add("No Settings package resolved for Usage Access recovery")
            }
            if (launcherPackages.isEmpty()) {
                add("No launcher package resolved for Usage Access recovery")
            }
        }
        return UsageAccessRecoveryResolution(
            packages = linkedSetOf<String>().apply {
                add(context.packageName)
                addAll(settingsPackages)
                addAll(launcherPackages)
            },
            settingsPackages = settingsPackages,
            launcherPackages = launcherPackages,
            warnings = warnings
        )
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
        } catch (_: NameNotFoundException) {
            packageName
        } catch (securityException: SecurityException) {
            FocusLog.w(
                context,
                FocusEventId.POLICY_STATE_COMPUTED,
                "Package label lookup denied for $packageName: ${securityException.message}"
            )
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
        } catch (_: NameNotFoundException) {
            false
        } catch (securityException: SecurityException) {
            FocusLog.w(
                context,
                FocusEventId.POLICY_STATE_COMPUTED,
                "Package install lookup denied for $packageName: ${securityException.message}"
            )
            false
        }
    }

    private fun resolveLauncherPackages(): Set<String> {
        val defaultLauncher = resolveDefaultLauncher()
        if (!defaultLauncher.isNullOrBlank()) {
            return setOf(defaultLauncher)
        }
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return resolveIntentPackages(homeIntent)
    }

    private fun resolveFallbackSettingsPackages(): Set<String> {
        val genericSettings = resolveIntentPackages(Intent(Settings.ACTION_SETTINGS))
        if (genericSettings.isNotEmpty()) {
            return genericSettings
        }
        val appDetails = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        return resolveIntentPackages(appDetails)
    }

    private fun resolveIntentPackages(intent: Intent): Set<String> {
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo?.packageName }
            .filter(String::isNotBlank)
            .toSet()
    }

    private fun isNonSystemPackage(packageName: String): Boolean {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val flags = appInfo.flags
            val isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            !isSystem
        } catch (_: NameNotFoundException) {
            false
        } catch (securityException: SecurityException) {
            FocusLog.w(
                context,
                FocusEventId.POLICY_STATE_COMPUTED,
                "Package system-state lookup denied for $packageName: ${securityException.message}"
            )
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
        internal fun computeUsageAccessRecoverySuspendTargets(
            launchablePackages: Set<String>,
            recoveryAllowlist: Set<String>,
            controllerPackageName: String,
            isNonSystemPackage: (String) -> Boolean
        ): Set<String> {
            return launchablePackages
                .asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .filterNot(recoveryAllowlist::contains)
                .filter { !isProtectedPackageName(it, controllerPackageName) }
                .filter(isNonSystemPackage)
                .toSet()
        }

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



