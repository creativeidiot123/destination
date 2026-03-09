package com.ankit.destination

import com.ankit.destination.policy.HiddenSuspendMethodSelector
import com.ankit.destination.policy.HiddenSuspendMethodVariant
import com.ankit.destination.policy.PackageSuspendBackend
import com.ankit.destination.policy.PackageSuspendBackendStatus
import com.ankit.destination.policy.PackageSuspendCallOptions
import com.ankit.destination.policy.PackageSuspendCoordinator
import com.ankit.destination.policy.PackageSuspendResult
import com.ankit.destination.policy.buildPackageSuspendCallOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageSuspendPrototypeTest {
    @Test
    fun hiddenSuccessUsesHiddenBackend() {
        val hidden = FakeSuspendBackend(
            result = PackageSuspendResult(
                failedPackages = setOf("failed.pkg"),
                errors = emptyList()
            )
        )
        val dpm = FakeSuspendBackend()
        val coordinator = PackageSuspendCoordinator(
            hiddenBackend = hidden,
            dpmBackend = dpm
        )

        val outcome = coordinator.setPackagesSuspended(
            packages = listOf("a", "b"),
            options = PackageSuspendCallOptions(
                suspended = true,
                dialogTitle = "Go Touch Grass! Destination is Judging you!",
                dialogMessageTemplate = "hello"
            )
        )

        assertEquals(PackageSuspendBackendStatus.HIDDEN, outcome.status.backend)
        assertNull(outcome.status.hiddenErrorMessage)
        assertEquals(setOf("failed.pkg"), outcome.result.failedPackages)
        assertEquals(1, hidden.calls.size)
        assertTrue(dpm.calls.isEmpty())
    }

    @Test
    fun hiddenFailureFallsBackToDpm() {
        val hidden = FakeSuspendBackend(throwable = IllegalStateException("blocked"))
        val dpm = FakeSuspendBackend(
            result = PackageSuspendResult(
                failedPackages = emptySet(),
                errors = emptyList()
            )
        )
        val coordinator = PackageSuspendCoordinator(
            hiddenBackend = hidden,
            dpmBackend = dpm
        )

        val outcome = coordinator.setPackagesSuspended(
            packages = listOf("a"),
            options = PackageSuspendCallOptions(
                suspended = true,
                dialogTitle = "Go Touch Grass! Destination is Judging you!",
                dialogMessageTemplate = "hello"
            )
        )

        assertEquals(PackageSuspendBackendStatus.DPM_FALLBACK, outcome.status.backend)
        assertEquals("blocked", outcome.status.hiddenErrorMessage)
        assertEquals(1, hidden.calls.size)
        assertEquals(1, dpm.calls.size)
    }

    @Test
    fun noHiddenBackendUsesDpmOnly() {
        val dpm = FakeSuspendBackend()
        val coordinator = PackageSuspendCoordinator(
            hiddenBackend = null,
            dpmBackend = dpm
        )

        val outcome = coordinator.setPackagesSuspended(
            packages = listOf("a"),
            options = PackageSuspendCallOptions(
                suspended = false,
                dialogTitle = null,
                dialogMessageTemplate = null
            )
        )

        assertEquals(PackageSuspendBackendStatus.DPM_ONLY, outcome.status.backend)
        assertEquals(1, dpm.calls.size)
    }

    @Test
    fun selectorPrefersSuspendDialogInfoOverLegacyString() {
        val selected = HiddenSuspendMethodSelector.select(
            listOf(
                String::class.java.name,
                "android.content.pm.SuspendDialogInfo"
            )
        )

        assertEquals(HiddenSuspendMethodVariant.SUSPEND_DIALOG_INFO, selected)
    }

    @Test
    fun selectorFallsBackToLegacyStringWhenNeeded() {
        val selected = HiddenSuspendMethodSelector.select(
            listOf(String::class.java.name)
        )

        assertEquals(HiddenSuspendMethodVariant.STRING_DIALOG_MESSAGE, selected)
    }

    @Test
    fun buildSuspendOptionsIncludesPrototypeMessageOnlyForSuspend() {
        val suspendOptions = buildPackageSuspendCallOptions(suspended = true)
        val unsuspendOptions = buildPackageSuspendCallOptions(suspended = false)

        assertEquals(true, suspendOptions.suspended)
        assertEquals("Go Touch Grass! Destination is Judging you!", suspendOptions.dialogTitle)
        assertTrue(suspendOptions.dialogMessageTemplate?.contains("%1\$s Blocked, policy block is active") == true)
        assertEquals(false, unsuspendOptions.suspended)
        assertNull(unsuspendOptions.dialogTitle)
        assertNull(unsuspendOptions.dialogMessageTemplate)
    }

    @Test
    fun buildSuspendOptionsIncludesGroupAndReasonWhenPresent() {
        val suspendOptions = buildPackageSuspendCallOptions(
            suspended = true,
            reasonTokens = setOf("GROUP:study_group:HOURLY_CAP")
        )

        assertEquals("Go Touch Grass! Destination is Judging you!", suspendOptions.dialogTitle)
        assertEquals(
            "%1\$s Blocked, Hourly limit reached/ study_group block is active",
            suspendOptions.dialogMessageTemplate
        )
    }

    @Test
    fun buildSuspendOptionsUsesScheduleDailyAndOpensTemplates() {
        val scheduleOptions = buildPackageSuspendCallOptions(
            suspended = true,
            reasonTokens = setOf("GROUP:study_group:SCHEDULED_BLOCK")
        )
        val dailyOptions = buildPackageSuspendCallOptions(
            suspended = true,
            reasonTokens = setOf("APP:DAILY_CAP")
        )
        val opensOptions = buildPackageSuspendCallOptions(
            suspended = true,
            reasonTokens = setOf("APP:OPENS_CAP")
        )

        assertEquals(
            "%1\$s Blocked, schedule block is active/ study_group block is active",
            scheduleOptions.dialogMessageTemplate
        )
        assertEquals(
            "%1\$s Blocked, Daily limit reached",
            dailyOptions.dialogMessageTemplate
        )
        assertEquals(
            "%1\$s Blocked, Daily opens limit reached",
            opensOptions.dialogMessageTemplate
        )
    }

    @Test
    fun buildSuspendOptionsFallsBackWhenReasonTokensUnknown() {
        val suspendOptions = buildPackageSuspendCallOptions(
            suspended = true,
            reasonTokens = setOf("UNRECOGNIZED_REASON_TOKEN")
        )

        assertEquals("Go Touch Grass! Destination is Judging you!", suspendOptions.dialogTitle)
        assertTrue(suspendOptions.dialogMessageTemplate?.contains("%1\$s Blocked, policy block is active") == true)
    }

    private class FakeSuspendBackend(
        private val result: PackageSuspendResult = PackageSuspendResult(
            failedPackages = emptySet(),
            errors = emptyList()
        ),
        private val throwable: Throwable? = null
    ) : PackageSuspendBackend {
        val calls = mutableListOf<Pair<List<String>, PackageSuspendCallOptions>>()

        override fun setPackagesSuspended(
            packages: List<String>,
            options: PackageSuspendCallOptions
        ): PackageSuspendResult {
            calls += packages to options
            throwable?.let { throw it }
            return result
        }
    }
}
