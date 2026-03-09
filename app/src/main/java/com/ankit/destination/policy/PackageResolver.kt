package com.ankit.destination.policy

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.provider.Settings.Secure
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager

internal interface InstalledAppResolver {
    fun getInstalledTargetablePackages(): Set<String>
}

internal interface ProtectedPackagesProvider {
    fun getHardProtectedPackages(): Set<String>
    fun isHardProtectedPackage(packageName: String): Boolean
}

internal interface PackageResolverClient : InstalledAppResolver, ProtectedPackagesProvider {
    fun resolveRuntimeAllowlist(userChosenEmergencyApps: Set<String>): AllowlistResolution

    fun computeUsageAccessRecoverySuspendTargets(recoveryAllowlist: Set<String>): Set<String>
    fun computeAccessibilityRecoverySuspendTargets(recoveryAllowlist: Set<String>): Set<String>
    fun filterSuspendable(packages: Set<String>, allowlist: Set<String>): Set<String>
    fun resolveUsageAccessRecoveryPackages(): PackageResolver.UsageAccessRecoveryResolution
    fun resolveAccessibilityRecoveryPackages(): PackageResolver.AccessibilityRecoveryResolution
    fun isPackageInstalled(packageName: String): Boolean
    fun packageLabelOrPackage(packageName: String): String
}

internal class PackageResolver(private val context: Context) : PackageResolverClient {
    private val packageManager: PackageManager = context.packageManager

    data class UsageAccessRecoveryResolution(
        val packages: Set<String>,
        val settingsPackages: Set<String>,
        val launcherPackages: Set<String>,
        val warnings: List<String>
    )

    data class AccessibilityRecoveryResolution(
        val packages: Set<String>,
        val settingsPackages: Set<String>,
        val launcherPackages: Set<String>,
        val warnings: List<String>
    )

    override fun resolveRuntimeAllowlist(userChosenEmergencyApps: Set<String>): AllowlistResolution {
        FocusLog.d(FocusEventId.ALLOWLIST_RESOLVE, "┌── resolveRuntimeAllowlist() emergency=${userChosenEmergencyApps.size}")
        val allowlist = linkedSetOf<String>()
        val reasons = linkedMapOf<String, String>()

        addPackage(allowlist, reasons, context.packageName, "controller app")
        FocusLog.v(FocusEventId.ALLOWLIST_RESOLVE, "│ +controller: ${context.packageName}")

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

        FocusLog.d(FocusEventId.ALLOWLIST_RESOLVE, "└── resolveRuntimeAllowlist() total=${allowlist.size} pkgs: ${allowlist.joinToString(",")}")
        return AllowlistResolution(packages = allowlist, reasons = reasons)
    }

    fun computeSuspendTargets(allowlist: Set<String>): Set<String> {
        val targetablePackages = getInstalledTargetablePackages()
        FocusLog.d(FocusEventId.SUSPEND_TARGET, "computeSuspendTargets: targetable=${targetablePackages.size} allowlist=${allowlist.size}")
        val targets = targetablePackages
            .asSequence()
            .filterNot { allowlist.contains(it) }
            .filterNot(::isHardProtectedPackage)
            .toSet()
        val protectedCount = targetablePackages.count(::isHardProtectedPackage)
        FocusLog.d(FocusEventId.SUSPEND_TARGET, "computeSuspendTargets: result=${targets.size} allowlisted=${targetablePackages.count { allowlist.contains(it) }} protected=$protectedCount")
        return targets
    }

    override fun getInstalledTargetablePackages(): Set<String> {
        val launchables = resolveLaunchablePackages()
        val targetable = launchables
            .asSequence()
            .filter(::isInstalled)
            .filter(::isNonSystemPackage)
            .toCollection(linkedSetOf())
        FocusLog.d(
            FocusEventId.SUSPEND_TARGET,
            "Installed targetable packages=${targetable.size} launchable=${launchables.size}"
        )
        return targetable
    }

    override fun getHardProtectedPackages(): Set<String> {
        return linkedSetOf<String>().apply {
            add(context.packageName)
            addAll(FocusConfig.protectedExactPackages)
            resolveDefaultDialer()?.let(::add)
            resolveDefaultSms()?.let(::add)
            resolveDefaultLauncher()?.let(::add)
            resolvePermissionController()?.let(::add)
            resolveDefaultInputMethod()?.let(::add)
        }
    }

    override fun isHardProtectedPackage(packageName: String): Boolean {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return false
        return normalized in getHardProtectedPackages() ||
            isProtectedPackageName(normalized, context.packageName)
    }

    override fun computeUsageAccessRecoverySuspendTargets(recoveryAllowlist: Set<String>): Set<String> {
        return computeRecoverySuspendTargets(
            launchablePackages = resolveLaunchablePackages(),
            recoveryAllowlist = recoveryAllowlist,
            controllerPackageName = context.packageName,
            isNonSystemPackage = ::isNonSystemPackage
        )
    }

    override fun computeAccessibilityRecoverySuspendTargets(recoveryAllowlist: Set<String>): Set<String> {
        return computeRecoverySuspendTargets(
            launchablePackages = resolveLaunchablePackages(),
            recoveryAllowlist = recoveryAllowlist,
            controllerPackageName = context.packageName,
            isNonSystemPackage = ::isNonSystemPackage
        )
    }

    override fun filterSuspendable(packages: Set<String>, allowlist: Set<String>): Set<String> {
        val result = packages
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { pkg ->
                val inAllowlist = allowlist.contains(pkg)
                if (inAllowlist) FocusLog.v(FocusEventId.SUSPEND_TARGET, "filterSuspendable: $pkg skipped (in allowlist)")
                inAllowlist
            }
            .filter { isInstalled(it) }
            .filterNot { pkg ->
                val prot = isHardProtectedPackage(pkg)
                if (prot) FocusLog.v(FocusEventId.SUSPEND_TARGET, "filterSuspendable: $pkg skipped (protected)")
                prot
            }
            .toSet()
        FocusLog.d(FocusEventId.SUSPEND_TARGET, "filterSuspendable: input=${packages.size} allowlist=${allowlist.size} → suspendable=${result.size}")
        return result
    }

    override fun resolveUsageAccessRecoveryPackages(): UsageAccessRecoveryResolution {
        val resolution = resolveRecoveryPackages(
            primarySettingsIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            settingsLabel = "Usage Access"
        )
        return UsageAccessRecoveryResolution(
            packages = resolution.packages,
            settingsPackages = resolution.settingsPackages,
            launcherPackages = resolution.launcherPackages,
            warnings = resolution.warnings
        )
    }

    override fun resolveAccessibilityRecoveryPackages(): AccessibilityRecoveryResolution {
        val resolution = resolveRecoveryPackages(
            primarySettingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            settingsLabel = "Accessibility"
        )
        return AccessibilityRecoveryResolution(
            packages = resolution.packages,
            settingsPackages = resolution.settingsPackages,
            launcherPackages = resolution.launcherPackages,
            warnings = resolution.warnings
        )
    }

    private fun resolveRecoveryPackages(
        primarySettingsIntent: Intent,
        settingsLabel: String
    ): RecoveryResolution {
        val launcherPackages = resolveLauncherPackages()
        val resolvedSettingsPackages = resolveIntentPackages(primarySettingsIntent)
        val settingsPackages = if (resolvedSettingsPackages.isNotEmpty()) {
            resolvedSettingsPackages
        } else {
            resolveFallbackSettingsPackages()
        }
        val warnings = buildList {
            if (resolvedSettingsPackages.isEmpty()) {
                add("$settingsLabel Settings screen did not resolve; using generic Settings fallback")
            }
            if (settingsPackages.isEmpty()) {
                add("No Settings package resolved for $settingsLabel recovery")
            }
            if (launcherPackages.isEmpty()) {
                add("No launcher package resolved for $settingsLabel recovery")
            }
        }
        return RecoveryResolution(
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

    override fun isPackageInstalled(packageName: String): Boolean = isInstalled(packageName)

    override fun packageLabelOrPackage(packageName: String): String {
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

    private fun resolveDefaultInputMethod(): String? {
        val flattened = Secure.getString(context.contentResolver, Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
            ?.trim()
        return flattened?.takeIf(String::isNotBlank)
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
            return computeRecoverySuspendTargets(
                launchablePackages = launchablePackages,
                recoveryAllowlist = recoveryAllowlist,
                controllerPackageName = controllerPackageName,
                isNonSystemPackage = isNonSystemPackage
            )
        }

        internal fun computeRecoverySuspendTargets(
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

    private data class RecoveryResolution(
        val packages: Set<String>,
        val settingsPackages: Set<String>,
        val launcherPackages: Set<String>,
        val warnings: List<String>
    )
}
