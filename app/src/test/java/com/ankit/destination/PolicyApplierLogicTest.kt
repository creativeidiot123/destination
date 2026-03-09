package com.ankit.destination

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.UserManager
import android.provider.Settings
import com.ankit.destination.data.GlobalControls
import com.ankit.destination.policy.DevicePolicyClient
import com.ankit.destination.policy.ManagedNetworkPolicy
import com.ankit.destination.policy.ModeState
import com.ankit.destination.policy.PackageSuspendResult
import com.ankit.destination.policy.PolicyApplier
import com.ankit.destination.policy.PolicyState
import com.ankit.destination.policy.ProtectedPackagesProvider
import com.ankit.destination.policy.desiredUserControlDisabledPackages
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyApplierLogicTest {

    @Test
    fun desiredUserControlDisabledPackages_returnsNormalizedPackages_onAndroid11AndLater() {
        val packages = desiredUserControlDisabledPackages(
            uninstallProtectedPackages = setOf(" com.example.one ", "", "com.example.two"),
            sdkInt = 30
        )

        assertEquals(setOf("com.example.one", "com.example.two"), packages)
    }

    @Test
    fun desiredUserControlDisabledPackages_returnsEmpty_beforeAndroid11() {
        val packages = desiredUserControlDisabledPackages(
            uninstallProtectedPackages = setOf("com.example.one"),
            sdkInt = 29
        )

        assertEquals(emptySet<String>(), packages)
    }

    @Test
    fun apply_usesFacadeStubToDriveSuspendDelta() {
        val facade = FakeDevicePolicyClient()
        val applier = PolicyApplier(facade)

        val first = applier.apply(
            state = policyState(
                suspendTargets = setOf("a", "b"),
                previouslySuspended = emptySet()
            )
        )
        val second = applier.apply(
            state = policyState(
                suspendTargets = setOf("b"),
                previouslySuspended = setOf("a", "b")
            )
        )

        assertTrue(first.errors.isEmpty())
        assertTrue(second.errors.isEmpty())
        assertEquals(
            listOf(
                SuspendCall(packages = listOf("a", "b"), suspended = true),
                SuspendCall(packages = listOf("a"), suspended = false)
            ),
            facade.suspendCalls
        )
    }

    @Test
    fun apply_skipsProtectedPackagesDuringSuspensionReconciliation() {
        val facade = FakeDevicePolicyClient()
        val applier = PolicyApplier(
            facade = facade,
            protectedPackagesProvider = object : ProtectedPackagesProvider {
                override fun getHardProtectedPackages(): Set<String> = setOf("protected.app")
                override fun isHardProtectedPackage(packageName: String): Boolean = packageName == "protected.app"
            }
        )

        applier.apply(
            state = policyState(
                suspendTargets = setOf("protected.app", "normal.app"),
                previouslySuspended = setOf("protected.app")
            )
        )

        assertEquals(
            listOf(SuspendCall(packages = listOf("normal.app"), suspended = true)),
            facade.suspendCalls
        )
    }

    @Test
    fun apply_blanksDeviceOwnerLockScreenInfo_everyTime() {
        val facade = FakeDevicePolicyClient()
        val applier = PolicyApplier(facade)

        val result = applier.apply(
            state = policyState(
                suspendTargets = emptySet(),
                previouslySuspended = emptySet()
            )
        )

        assertTrue(result.errors.isEmpty())
        assertEquals(" ", facade.deviceOwnerLockScreenInfo)
    }

    @Test
    fun apply_forwardsBlockReasonsToSuspendCall() {
        val facade = FakeDevicePolicyClient()
        val applier = PolicyApplier(facade)

        val result = applier.apply(
            state = policyState(
                suspendTargets = setOf("a"),
                previouslySuspended = emptySet(),
                blockReasonsByPackage = mapOf(
                    "a" to setOf("GROUP:focus_group:DAILY_CAP")
                )
            )
        )

        assertTrue(result.errors.isEmpty())
        assertEquals(1, facade.suspendCalls.size)
        assertEquals(
            mapOf("a" to setOf("GROUP:focus_group:DAILY_CAP")),
            facade.suspendCalls.first().blockReasonsByPackage
        )
    }

    @Test
    fun apply_usesObservedSuspendedState_notTrackedState_forSuspendDelta() {
        val facade = FakeDevicePolicyClient().apply {
            seedSuspended("a")
        }
        val applier = PolicyApplier(facade)

        val result = applier.apply(
            state = policyState(
                suspendTargets = setOf("a"),
                previouslySuspended = emptySet()
            )
        )

        assertTrue(result.errors.isEmpty())
        assertTrue(facade.suspendCalls.isEmpty())
        assertEquals(setOf("a"), result.observedState.suspendedPackages)
    }

    @Test
    fun apply_usesObservedSuspendedState_notTrackedState_forUnsuspendDelta() {
        val facade = FakeDevicePolicyClient()
        val applier = PolicyApplier(facade)

        val result = applier.apply(
            state = policyState(
                suspendTargets = emptySet(),
                previouslySuspended = setOf("a")
            )
        )

        assertTrue(result.errors.isEmpty())
        assertTrue(facade.suspendCalls.isEmpty())
        assertTrue(result.observedState.suspendedPackages.isEmpty())
    }

    @Test
    fun apply_turnsOffAdbWhenDeveloperRestrictionIsEnabled() {
        val facade = FakeDevicePolicyClient()
        val applier = PolicyApplier(facade)

        val result = applier.apply(
            state = policyState(
                suspendTargets = emptySet(),
                previouslySuspended = emptySet(),
                restrictions = setOf(UserManager.DISALLOW_DEBUGGING_FEATURES)
            )
        )

        assertTrue(result.errors.isEmpty())
        assertEquals("0", facade.globalSettings[Settings.Global.ADB_ENABLED])
    }

    @Test
    fun apply_coreVerificationFailure_requestsRepair() {
        val facade = FakeDevicePolicyClient().apply {
            reportedSuspendedOverrides["a"] = false
        }
        val applier = PolicyApplier(facade)

        val result = applier.apply(
            state = policyState(
                suspendTargets = setOf("a"),
                previouslySuspended = emptySet()
            )
        )

        assertTrue(result.coreFailure)
        assertTrue(result.repairPlan?.required == true)
        assertEquals(60_000L, result.repairPlan?.delayMs)
        assertFalse(result.verification?.passed ?: true)
    }

    @Test
    fun apply_supportingFailure_isReportedWithoutPretendingSuccess() {
        val facade = FakeDevicePolicyClient().apply {
            ignoreAutoTimeWrites = true
        }
        val applier = PolicyApplier(facade)

        val result = applier.apply(
            state = policyState(
                suspendTargets = emptySet(),
                previouslySuspended = emptySet()
            ).copy(requireAutoTime = true)
        )

        assertTrue(result.supportingFailure)
        assertTrue(result.repairPlan?.required == true)
        assertTrue(result.verification?.supportingIssues?.contains("Auto time requirement mismatch") == true)
    }

    private fun policyState(
        suspendTargets: Set<String>,
        previouslySuspended: Set<String>,
        blockReasonsByPackage: Map<String, Set<String>> = emptyMap(),
        restrictions: Set<String> = emptySet()
    ): PolicyState {
        return PolicyState(
            mode = ModeState.NORMAL,
            lockTaskAllowlist = emptySet(),
            lockTaskFeatures = 0,
            statusBarDisabled = false,
            suspendTargets = suspendTargets,
            previouslySuspended = previouslySuspended,
            uninstallProtectedPackages = emptySet(),
            previouslyUninstallProtectedPackages = emptySet(),
            restrictions = restrictions,
            enforceRestrictions = false,
            blockSelfUninstall = false,
            requireAutoTime = false,
            emergencyApps = emptySet(),
            allowlistReasons = emptyMap(),
            vpnRequired = false,
            managedNetworkPolicy = ManagedNetworkPolicy.Unmanaged,
            lockReason = null,
            budgetBlockedPackages = suspendTargets,
            touchGrassBreakActive = false,
            primaryReasonByPackage = emptyMap(),
            blockReasonsByPackage = blockReasonsByPackage,
            globalControls = GlobalControls()
        )
    }

    private data class SuspendCall(
        val packages: List<String>,
        val suspended: Boolean,
        val blockReasonsByPackage: Map<String, Set<String>> = emptyMap()
    )

    private class FakeDevicePolicyClient : DevicePolicyClient {
        override val adminComponent: ComponentName = ComponentName("test", "Admin")
        override val packageName: String = "com.ankit.destination"
        val suspendCalls = mutableListOf<SuspendCall>()
        val globalSettings = linkedMapOf<String, String>()
        private val suspendedPackages = linkedSetOf<String>()
        private val uninstallBlockedPackages = linkedSetOf<String>()
        private var autoTimeRequired = false
        private var lockTaskPackages = emptyList<String>()
        private var lockTaskFeatures = 0
        var deviceOwnerLockScreenInfo: CharSequence? = null
        var ignoreAutoTimeWrites: Boolean = false
        val reportedSuspendedOverrides = linkedMapOf<String, Boolean?>()

        fun seedSuspended(packageName: String) {
            suspendedPackages += packageName
        }

        override fun isAdminActive(): Boolean = true
        override fun isDeviceOwner(): Boolean = true
        override fun clearDeviceOwnerApp() = Unit
        override fun setBlankDeviceOwnerLockScreenInfo() {
            deviceOwnerLockScreenInfo = " "
        }
        override fun setLockTaskPackages(packages: List<String>) {
            lockTaskPackages = packages
        }
        override fun setLockTaskFeatures(features: Int) {
            lockTaskFeatures = features
        }
        override fun getLockTaskFeatures(): Int? = lockTaskFeatures
        override fun getLockTaskPackages(): List<String> = lockTaskPackages
        override fun setStatusBarDisabled(disabled: Boolean): Boolean? = true
        override fun setAlwaysOnVpnPackage(vpnPackage: String?, lockdownEnabled: Boolean) = Unit
        override fun getAlwaysOnVpnPackage(): String? = null
        override fun isAlwaysOnVpnLockdownEnabled(): Boolean? = false
        override fun getGlobalPrivateDnsMode(): Int? = DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC
        override fun getGlobalPrivateDnsHost(): String? = null
        override fun supportsGlobalPrivateDns(): Boolean = true
        override fun setGlobalPrivateDnsModeOpportunistic(): Int? = DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC
        override fun setGlobalPrivateDnsModeSpecifiedHost(host: String): Int? = DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME
        override fun setPackagesSuspended(
            packages: List<String>,
            suspended: Boolean,
            blockReasonsByPackage: Map<String, Set<String>>
        ): PackageSuspendResult {
            suspendCalls += SuspendCall(
                packages = packages,
                suspended = suspended,
                blockReasonsByPackage = blockReasonsByPackage
            )
            if (suspended) {
                suspendedPackages += packages
            } else {
                suspendedPackages -= packages.toSet()
            }
            return PackageSuspendResult(
                failedPackages = emptySet(),
                errors = emptyList()
            )
        }
        override fun setUninstallBlocked(packageName: String, blocked: Boolean) {
            if (blocked) uninstallBlockedPackages += packageName else uninstallBlockedPackages -= packageName
        }
        override fun isUninstallBlocked(packageName: String): Boolean = uninstallBlockedPackages.contains(packageName)
        override fun supportsUserControlDisabledPackages(): Boolean = false
        override fun setUserControlDisabledPackages(packages: List<String>) = Unit
        override fun getUserControlDisabledPackages(): Set<String> = emptySet()
        override fun addUserRestriction(restriction: String) = Unit
        override fun clearUserRestriction(restriction: String) = Unit
        override fun hasUserRestriction(restriction: String): Boolean = false
        override fun setGlobalSetting(setting: String, value: String) {
            globalSettings[setting] = value
        }
        override fun setAutoTimeRequired(required: Boolean) {
            if (!ignoreAutoTimeWrites) {
                autoTimeRequired = required
            }
        }
        override fun isAutoTimeRequired(): Boolean = autoTimeRequired
        override fun canVerifyPackageSuspension(): Boolean = true
        override fun isPackageSuspended(packageName: String): Boolean? {
            return reportedSuspendedOverrides[packageName] ?: suspendedPackages.contains(packageName)
        }
        override fun lockTaskModeState(): Int? = 0
        override fun isHomeAppPinnedToSelf(): Boolean? = false
        override fun setAsHomeForKiosk(enabled: Boolean) = Unit
    }
}
