package com.ankit.destination

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import com.ankit.destination.data.GlobalControls
import com.ankit.destination.policy.DevicePolicyClient
import com.ankit.destination.policy.ManagedNetworkPolicy
import com.ankit.destination.policy.ModeState
import com.ankit.destination.policy.PackageSuspendResult
import com.ankit.destination.policy.PolicyApplier
import com.ankit.destination.policy.PolicyState
import com.ankit.destination.policy.desiredUserControlDisabledPackages
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

    private fun policyState(
        suspendTargets: Set<String>,
        previouslySuspended: Set<String>
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
            restrictions = emptySet(),
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
            blockReasonsByPackage = emptyMap(),
            globalControls = GlobalControls()
        )
    }

    private data class SuspendCall(
        val packages: List<String>,
        val suspended: Boolean
    )

    private class FakeDevicePolicyClient : DevicePolicyClient {
        override val adminComponent: ComponentName = ComponentName("test", "Admin")
        override val packageName: String = "com.ankit.destination"
        val suspendCalls = mutableListOf<SuspendCall>()
        private val suspendedPackages = linkedSetOf<String>()
        private val uninstallBlockedPackages = linkedSetOf<String>()
        private var autoTimeRequired = false
        private var lockTaskPackages = emptyList<String>()
        private var lockTaskFeatures = 0

        override fun isAdminActive(): Boolean = true
        override fun isDeviceOwner(): Boolean = true
        override fun clearDeviceOwnerApp() = Unit
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
        override fun setPackagesSuspended(packages: List<String>, suspended: Boolean): PackageSuspendResult {
            suspendCalls += SuspendCall(packages = packages, suspended = suspended)
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
        override fun setAutoTimeRequired(required: Boolean) {
            autoTimeRequired = required
        }
        override fun isAutoTimeRequired(): Boolean = autoTimeRequired
        override fun canVerifyPackageSuspension(): Boolean = true
        override fun isPackageSuspended(packageName: String): Boolean? = suspendedPackages.contains(packageName)
        override fun lockTaskModeState(): Int? = 0
        override fun isHomeAppPinnedToSelf(): Boolean? = false
        override fun setAsHomeForKiosk(enabled: Boolean) = Unit
    }
}
