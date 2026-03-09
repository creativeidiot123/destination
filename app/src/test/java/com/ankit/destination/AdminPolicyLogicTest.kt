package com.ankit.destination

import com.ankit.destination.data.GlobalControls
import com.ankit.destination.data.ManagedNetworkModeSetting
import com.ankit.destination.policy.AccessibilityComplianceState
import com.ankit.destination.policy.ModeState
import com.ankit.destination.policy.ManagedNetworkPolicy
import com.ankit.destination.policy.PackageResolver
import com.ankit.destination.policy.PackageResolverClient
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.policy.PolicyEvaluator
import com.ankit.destination.policy.PolicyRestrictions
import com.ankit.destination.policy.RecoveryLockdownState
import com.ankit.destination.policy.PolicyState
import com.ankit.destination.policy.PolicyStore
import com.ankit.destination.policy.AllowlistResolution
import com.ankit.destination.policy.EffectiveBlockReason
import com.ankit.destination.policy.UsageAccessComplianceState
import com.ankit.destination.policy.UsageSnapshotStatus
import com.ankit.destination.usage.UsageAccessMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminPolicyLogicTest {

    @Test
    fun normalizeConfiguredPackages_trimsAndKeepsImplicitPackagesOnce() {
        val normalized = PolicyEngine.normalizeConfiguredPackages(
            packages = listOf("  com.example.alpha  ", "", "com.example.alpha", "   "),
            implicitPackages = setOf("com.ankit.destination", "com.example.alpha")
        )

        assertEquals(
            setOf("com.example.alpha", "com.ankit.destination"),
            normalized
        )
    }

    @Test
    fun desiredUninstallProtectedPackages_alwaysKeepsControllerWhenSelfProtectionEnabled() {
        val state = PolicyState(
            mode = ModeState.NORMAL,
            lockTaskAllowlist = emptySet(),
            lockTaskFeatures = 0,
            statusBarDisabled = false,
            suspendTargets = emptySet(),
            previouslySuspended = emptySet(),
            uninstallProtectedPackages = setOf("com.example.target"),
            previouslyUninstallProtectedPackages = emptySet(),
            restrictions = emptySet(),
            enforceRestrictions = false,
            blockSelfUninstall = true,
            requireAutoTime = false,
            emergencyApps = emptySet(),
            allowlistReasons = emptyMap(),
            vpnRequired = false,
            managedNetworkPolicy = ManagedNetworkPolicy.Unmanaged,
            lockReason = null,
            budgetBlockedPackages = emptySet(),
            touchGrassBreakActive = false,
            primaryReasonByPackage = emptyMap(),
            globalControls = GlobalControls()
        )

        assertEquals(
            setOf("com.example.target", "com.ankit.destination"),
            PolicyStore.desiredUninstallProtectedPackages(state, "com.ankit.destination")
        )
    }

    @Test
    fun mergeSuspendTargets_includesStrictInstallPackages_inNormalMode() {
        val merged = PolicyEvaluator.mergeSuspendTargets(
            budgetBlockedSuspendable = setOf("budget"),
            alwaysBlockedSuspendable = setOf("always"),
            strictInstallSuspendable = setOf("fresh.install")
        )

        assertEquals(setOf("budget", "always", "fresh.install"), merged)
    }

    @Test
    fun reconcileTrackedPackages_usesActualPostApplyState_afterPartialFailures() {
        assertEquals(
            setOf("keep", "new", "stuck"),
            PolicyStore.reconcileTrackedPackages(
                previousPackages = setOf("keep", "remove", "stuck"),
                targetPackages = setOf("keep", "new"),
                failedAdds = emptySet(),
                failedRemovals = setOf("stuck")
            )
        )
    }

    @Test
    fun globalControls_skipUnsupportedRestrictionsOnOlderSdk() {
        val restrictions = PolicyRestrictions.build(
            globalControls = GlobalControls(
                lockTime = true,
                lockWorkProfile = true,
                lockCloningBestEffort = true
            ),
            sdkInt = 26
        )

        assertFalse(restrictions.contains(android.os.UserManager.DISALLOW_CONFIG_DATE_TIME))
        assertTrue(restrictions.contains(android.os.UserManager.DISALLOW_ADD_MANAGED_PROFILE))
        assertFalse(restrictions.contains(PolicyRestrictions.NO_ADD_CLONE_PROFILE))
        assertFalse(restrictions.contains(PolicyRestrictions.NO_ADD_PRIVATE_PROFILE))
    }

    @Test
    fun globalControls_includeBestEffortRestrictionsOnlyWhenSupported() {
        val cloneOnly = PolicyRestrictions.build(
            globalControls = GlobalControls(lockCloningBestEffort = true),
            sdkInt = 34
        )
        val cloneAndPrivate = PolicyRestrictions.build(
            globalControls = GlobalControls(lockCloningBestEffort = true),
            sdkInt = 35
        )

        assertTrue(cloneOnly.contains(PolicyRestrictions.NO_ADD_CLONE_PROFILE))
        assertFalse(cloneOnly.contains(PolicyRestrictions.NO_ADD_PRIVATE_PROFILE))
        assertTrue(cloneAndPrivate.contains(PolicyRestrictions.NO_ADD_CLONE_PROFILE))
        assertTrue(cloneAndPrivate.contains(PolicyRestrictions.NO_ADD_PRIVATE_PROFILE))
    }

    @Test
    fun globalControls_includeSafeBootRestriction_onlyWhenSupported() {
        val unsupported = PolicyRestrictions.build(
            globalControls = GlobalControls(disableSafeMode = true),
            sdkInt = 25
        )
        val supported = PolicyRestrictions.build(
            globalControls = GlobalControls(disableSafeMode = true),
            sdkInt = 26
        )

        assertFalse(unsupported.contains(android.os.UserManager.DISALLOW_SAFE_BOOT))
        assertTrue(supported.contains(android.os.UserManager.DISALLOW_SAFE_BOOT))
    }

    @Test
    fun managedRestrictions_doNotTrackUnsupportedBestEffortRestrictions() {
        val managed = PolicyRestrictions.managed(emptySet(), sdkInt = 33)

        assertFalse(managed.contains(PolicyRestrictions.NO_ADD_CLONE_PROFILE))
        assertFalse(managed.contains(PolicyRestrictions.NO_ADD_PRIVATE_PROFILE))
    }

    @Test
    fun shouldEnforceVpnLockdown_requiresForcedVpnModeAndFeatureFlag() {
        assertTrue(
            PolicyEngine.shouldEnforceVpnLockdown(
                globalControls = GlobalControls(
                    managedNetworkMode = ManagedNetworkModeSetting.FORCED_VPN.name,
                    managedVpnPackage = "com.example.vpn",
                    managedVpnLockdown = true
                ),
                controllerPackageName = "com.ankit.destination",
                featureEnabled = true
            )
        )
        assertFalse(
            PolicyEngine.shouldEnforceVpnLockdown(
                globalControls = GlobalControls(
                    managedNetworkMode = ManagedNetworkModeSetting.UNMANAGED.name
                ),
                controllerPackageName = "com.ankit.destination",
                featureEnabled = true
            )
        )
        assertFalse(
            PolicyEngine.shouldEnforceVpnLockdown(
                globalControls = GlobalControls(
                    managedNetworkMode = ManagedNetworkModeSetting.FORCED_VPN.name,
                    managedVpnPackage = "com.example.vpn",
                    managedVpnLockdown = true
                ),
                controllerPackageName = "com.ankit.destination",
                featureEnabled = false
            )
        )
    }

    @Test
    fun managedNetworkRestrictions_blockVpnAndPrivateDnsInForcedModes() {
        val forcedVpn = PolicyRestrictions.build(
            globalControls = GlobalControls(),
            managedNetworkPolicy = ManagedNetworkPolicy.ForcedVpn(
                packageName = "com.example.vpn",
                lockdown = true
            ),
            sdkInt = 34
        )
        val forcedDns = PolicyRestrictions.build(
            globalControls = GlobalControls(),
            managedNetworkPolicy = ManagedNetworkPolicy.ForcedPrivateDns("dns.example.com"),
            sdkInt = 34
        )

        assertTrue(forcedVpn.contains(android.os.UserManager.DISALLOW_CONFIG_VPN))
        assertTrue(forcedVpn.contains(android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS))
        assertTrue(forcedDns.contains(android.os.UserManager.DISALLOW_CONFIG_VPN))
        assertTrue(forcedDns.contains(android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS))
    }

    @Test
    fun globalControls_lockVpnDns_blocksVpnAndPrivateDnsWithoutForcedManagedMode() {
        val restrictions = PolicyRestrictions.build(
            globalControls = GlobalControls(lockVpnDns = true),
            managedNetworkPolicy = ManagedNetworkPolicy.Unmanaged,
            sdkInt = 34
        )

        assertTrue(restrictions.contains(android.os.UserManager.DISALLOW_CONFIG_VPN))
        assertTrue(restrictions.contains(android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS))
    }

    @Test
    fun usageAccessLockdownEligible_requiresDeviceOwnerAndSuccessfulEnrollmentOrLegacyApply() {
        assertTrue(
            PolicyEngine.isUsageAccessLockdownEligible(
                deviceOwnerActive = true,
                provisioningFinalizationState = "SUCCESS",
                hasSuccessfulPolicyApply = false
            )
        )
        assertTrue(
            PolicyEngine.isUsageAccessLockdownEligible(
                deviceOwnerActive = true,
                provisioningFinalizationState = null,
                hasSuccessfulPolicyApply = true
            )
        )
        // FAILED + prior apply exists → eligible (device was functional before)
        assertTrue(
            PolicyEngine.isUsageAccessLockdownEligible(
                deviceOwnerActive = true,
                provisioningFinalizationState = "FAILED",
                hasSuccessfulPolicyApply = true,
                hasAnyPriorApply = true
            )
        )
        // FAILED + no prior apply → not eligible (genuine fresh failure)
        assertFalse(
            PolicyEngine.isUsageAccessLockdownEligible(
                deviceOwnerActive = true,
                provisioningFinalizationState = "FAILED",
                hasSuccessfulPolicyApply = false,
                hasAnyPriorApply = false
            )
        )
        // PENDING + prior apply → eligible
        assertTrue(
            PolicyEngine.isUsageAccessLockdownEligible(
                deviceOwnerActive = true,
                provisioningFinalizationState = "PENDING",
                hasSuccessfulPolicyApply = false,
                hasAnyPriorApply = true
            )
        )
        // PENDING + no prior apply → not eligible
        assertFalse(
            PolicyEngine.isUsageAccessLockdownEligible(
                deviceOwnerActive = true,
                provisioningFinalizationState = "PENDING",
                hasSuccessfulPolicyApply = false,
                hasAnyPriorApply = false
            )
        )
        assertFalse(
            PolicyEngine.isUsageAccessLockdownEligible(
                deviceOwnerActive = false,
                provisioningFinalizationState = "SUCCESS",
                hasSuccessfulPolicyApply = true
            )
        )
    }

    @Test
    fun usageAccessRecoverySuspendTargets_excludeRecoveryAllowlistProtectedAndSystemPackages() {
        val targets = PackageResolver.computeUsageAccessRecoverySuspendTargets(
            launchablePackages = setOf(
                "com.example.launcher",
                "com.example.app",
                "com.example.system",
                "com.ankit.destination",
                "android"
            ),
            recoveryAllowlist = setOf("com.example.launcher", "com.ankit.destination"),
            controllerPackageName = "com.ankit.destination",
            isNonSystemPackage = { pkg -> pkg != "com.example.system" }
        )

        assertEquals(setOf("com.example.app"), targets)
    }

    @Test
    fun accessibilityRecoveryLockdown_usesAccessibilityReasonTokenAndAllowlist() {
        val resolver = object : PackageResolverClient {
            override fun resolveRuntimeAllowlist(userChosenEmergencyApps: Set<String>): AllowlistResolution {
                return AllowlistResolution(emptySet(), emptyMap())
            }

            override fun computeUsageAccessRecoverySuspendTargets(recoveryAllowlist: Set<String>): Set<String> {
                return emptySet()
            }

            override fun computeAccessibilityRecoverySuspendTargets(recoveryAllowlist: Set<String>): Set<String> {
                return setOf("com.example.blocked")
            }

            override fun filterSuspendable(packages: Set<String>, allowlist: Set<String>): Set<String> {
                return packages - allowlist
            }

            override fun resolveUsageAccessRecoveryPackages(): PackageResolver.UsageAccessRecoveryResolution {
                return PackageResolver.UsageAccessRecoveryResolution(emptySet(), emptySet(), emptySet(), emptyList())
            }

            override fun resolveAccessibilityRecoveryPackages(): PackageResolver.AccessibilityRecoveryResolution {
                return PackageResolver.AccessibilityRecoveryResolution(emptySet(), emptySet(), emptySet(), emptyList())
            }

            override fun isPackageInstalled(packageName: String): Boolean = true

            override fun packageLabelOrPackage(packageName: String): String = packageName

            override fun getInstalledTargetablePackages(): Set<String> = setOf("com.example.blocked")

            override fun getHardProtectedPackages(): Set<String> = setOf("com.ankit.destination")

            override fun isHardProtectedPackage(packageName: String): Boolean {
                return packageName == "com.ankit.destination"
            }
        }
        val evaluator = PolicyEvaluator(resolver, "com.ankit.destination")

        val state = evaluator.evaluate(
            mode = ModeState.NORMAL,
            emergencyApps = emptySet(),
            protectionSnapshot = com.ankit.destination.policy.AppProtectionSnapshot(
                allowlistedPackages = emptySet(),
                hiddenPackages = emptySet(),
                lockedHiddenPackages = emptySet(),
                runtimeExemptPackages = emptySet(),
                runtimeExemptionReasons = emptyMap(),
                hardProtectedPackages = emptySet()
            ),
            alwaysBlockedApps = emptySet(),
            uninstallProtectedApps = emptySet(),
            globalControls = GlobalControls(),
            previouslySuspended = emptySet(),
            previouslyUninstallProtected = emptySet(),
            budgetBlockedPackages = emptySet(),
            primaryReasonByPackage = emptyMap(),
            lockReason = "Accessibility missing: recovery lockdown active",
            touchGrassBreakActive = false,
            usageAccessComplianceState = UsageAccessComplianceState(
                snapshotStatus = UsageSnapshotStatus.OK,
                usageAccessGranted = true,
                lockdownEligible = true,
                lockdownActive = false,
                recoveryAllowlist = emptySet(),
                reason = null
            ),
            accessibilityComplianceState = AccessibilityComplianceState(
                accessibilityServiceEnabled = false,
                accessibilityServiceRunning = false,
                lockdownEligible = true,
                lockdownActive = true,
                recoveryAllowlist = setOf(
                    "com.ankit.destination",
                    "com.example.settings",
                    "com.example.launcher"
                ),
                reason = "Accessibility missing: recovery lockdown active"
            ),
            recoveryLockdownState = RecoveryLockdownState(
                active = true,
                allowlist = setOf(
                    "com.ankit.destination",
                    "com.example.settings",
                    "com.example.launcher"
                ),
                allowlistReasons = mapOf(
                    "com.ankit.destination" to "accessibility recovery",
                    "com.example.settings" to "accessibility recovery",
                    "com.example.launcher" to "accessibility recovery"
                ),
                reasonTokens = setOf(EffectiveBlockReason.ACCESSIBILITY_RECOVERY_LOCKDOWN.name),
                reason = "Accessibility missing: recovery lockdown active"
            )
        )

        assertEquals(
            setOf("com.ankit.destination", "com.example.settings", "com.example.launcher"),
            state.lockTaskAllowlist
        )
        assertEquals(
            setOf(EffectiveBlockReason.ACCESSIBILITY_RECOVERY_LOCKDOWN.name),
            state.blockReasonsByPackage["com.example.blocked"]
        )
        assertEquals(
            EffectiveBlockReason.ACCESSIBILITY_RECOVERY_LOCKDOWN.name,
            state.primaryReasonByPackage["com.example.blocked"]
        )
    }

    @Test
    fun usageAccessMonitorDebounce_suppressesDuplicateRefreshForSameState() {
        assertTrue(
            UsageAccessMonitor.shouldSuppressPolicyRefresh(
                lastPolicyRefreshGranted = false,
                nextUsageAccessGranted = false,
                lastPolicyRefreshAtMs = 1_000L,
                nowMs = 1_500L,
                debounceMs = 1_000L
            )
        )
        assertFalse(
            UsageAccessMonitor.shouldSuppressPolicyRefresh(
                lastPolicyRefreshGranted = false,
                nextUsageAccessGranted = true,
                lastPolicyRefreshAtMs = 1_000L,
                nowMs = 1_500L,
                debounceMs = 1_000L
            )
        )
        assertFalse(
            UsageAccessMonitor.shouldSuppressPolicyRefresh(
                lastPolicyRefreshGranted = false,
                nextUsageAccessGranted = false,
                lastPolicyRefreshAtMs = 1_000L,
                nowMs = 2_100L,
                debounceMs = 1_000L
            )
        )
    }
}
