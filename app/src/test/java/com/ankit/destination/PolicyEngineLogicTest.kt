package com.ankit.destination

import com.ankit.destination.data.EnforcementStateEntity
import com.ankit.destination.data.ScheduleBlock
import com.ankit.destination.data.ScheduleBlockKind
import com.ankit.destination.policy.ModeState
import com.ankit.destination.policy.EmergencyConfigInput
import com.ankit.destination.policy.GroupPolicyInput
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.policy.AppPolicyInput
import com.ankit.destination.policy.ScheduleTargetDiagnosticCode
import com.ankit.destination.policy.UsageInputs
import com.ankit.destination.schedule.ScheduleDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest
import java.time.ZonedDateTime

class PolicyEngineLogicTest {

    @Test
    fun resolveEffectiveMode_staysNormal_whenLegacyTouchGrassStateExpires() {
        val nowMs = 1_000_000L
        val mode = PolicyEngine.resolveEffectiveMode(
            manualMode = ModeState.NORMAL,
            scheduleComputed = false,
            touchGrassBreakUntilMs = nowMs - 1L,
            nowMs = nowMs
        )
        assertEquals(ModeState.NORMAL, mode)
    }

    @Test
    fun resolveEffectiveMode_ignoresLegacyTouchGrassBreaks() {
        val nowMs = 1_000_000L
        val mode = PolicyEngine.resolveEffectiveMode(
            manualMode = ModeState.NORMAL,
            scheduleComputed = false,
            touchGrassBreakUntilMs = nowMs + 60_000L,
            nowMs = nowMs
        )
        assertEquals(ModeState.NORMAL, mode)
    }

    @Test
    fun resolveEffectiveMode_ignoresLegacyScheduleWideMode() {
        val nowMs = 1_000_000L
        val mode = PolicyEngine.resolveEffectiveMode(
            manualMode = ModeState.NORMAL,
            scheduleComputed = true,
            touchGrassBreakUntilMs = null,
            nowMs = nowMs
        )
        assertEquals(ModeState.NORMAL, mode)
    }

    @Test
    fun budgetDue_usesGraceWindow() {
        val nowMs = 10_000L
        val nextCheckAtMs = 20_000L
        assertFalse(PolicyEngine.isBudgetEvaluationDue(nowMs, nextCheckAtMs, graceMs = 5_000L))
        assertTrue(PolicyEngine.isBudgetEvaluationDue(nowMs, nextCheckAtMs, graceMs = 10_000L))
    }

    @Test
    fun budgetDue_whenNoNextCheckConfigured() {
        assertTrue(PolicyEngine.isBudgetEvaluationDue(nowMs = 1_000L, nextCheckAtMs = null))
    }

    @Test
    fun combinedBlockedPackages_mergesAllSourcesWithoutDroppingEntries() {
        val combined = PolicyEngine.combinedBlockedPackages(
            alwaysBlocked = setOf("a", "b"),
            budgetBlocked = setOf("b", "c"),
            scheduleBlocked = setOf("d"),
            strictInstallBlocked = setOf("e")
        )

        assertEquals(setOf("a", "b", "c", "d", "e"), combined)
    }

    @Test
    fun computePrimaryReasonByPackage_usesExpectedPrecedence() {
        val reasons = PolicyEngine.computePrimaryReasonByPackage(
            alwaysBlocked = setOf("shared", "always"),
            budgetBlocked = setOf("shared", "budgetOnly"),
            scheduleBlocked = setOf("shared", "scheduleOnly"),
            strictInstallBlocked = setOf("shared", "strictOnly")
        )

        assertEquals("ALWAYS_BLOCKED", reasons["shared"])
        assertEquals("ALWAYS_BLOCKED", reasons["always"])
        assertEquals("SCHEDULE_GROUP", reasons["scheduleOnly"])
        assertEquals("STRICT_INSTALL", reasons["strictOnly"])
        assertEquals("BUDGET", reasons["budgetOnly"])
        assertTrue(
            reasons.keys.containsAll(
                setOf("shared", "always", "scheduleOnly", "strictOnly", "budgetOnly")
            )
        )
    }

    @Test
    fun resolveStrictScheduleActive_usesActiveStrictScheduleBlock_evenWithoutStrictGroupFlag() {
        val strictActive = PolicyEngine.resolveStrictScheduleActive(scheduleStrictActive = true)

        assertTrue(strictActive)
    }

    @Test
    fun resolveStrictScheduleActive_doesNotBlendGroupStrictInstallParticipation() {
        val strictActive = PolicyEngine.resolveStrictScheduleActive(scheduleStrictActive = false)

        assertFalse(strictActive)
    }

    @Test
    fun enforcementStateSnapshotCache_persistUpdatesCurrentStateImmediately() {
        val initial = EnforcementStateEntity(scheduleLockEnforced = false, computedSnapshotVersion = 1L)
        val updated = initial.copy(scheduleLockEnforced = true, computedSnapshotVersion = 2L)
        val cache = PolicyEngine.EnforcementStateSnapshotCache(initial)

        cache.persist(updated)

        assertTrue(cache.current().scheduleLockEnforced)
        assertEquals(2L, cache.current().computedSnapshotVersion)
    }

    @Test
    fun enforcementStateSnapshotCache_loadFromPersistenceHydratesOnlyOnce() = runTest {
        val initial = EnforcementStateEntity(scheduleLockEnforced = false, computedSnapshotVersion = 1L)
        val persisted = initial.copy(scheduleLockEnforced = true, computedSnapshotVersion = 3L)
        val cache = PolicyEngine.EnforcementStateSnapshotCache(initial)
        var loadCount = 0

        val first = cache.loadFromPersistence {
            loadCount += 1
            persisted
        }
        val second = cache.loadFromPersistence {
            loadCount += 1
            initial.copy(scheduleLockEnforced = false, computedSnapshotVersion = 4L)
        }

        assertTrue(first.scheduleLockEnforced)
        assertTrue(second.scheduleLockEnforced)
        assertEquals(3L, cache.current().computedSnapshotVersion)
        assertEquals(1, loadCount)
    }

    @Test
    fun resolveEmergencyApps_keepsExplicitEmptySelection() {
        assertEquals(
            emptySet<String>(),
            PolicyEngine.resolveEmergencyApps(
                storedEmergencyApps = emptySet(),
                hasExplicitSelection = true,
                defaultEmergencyApps = setOf("maps")
            )
        )
        assertEquals(
            setOf("maps"),
            PolicyEngine.resolveEmergencyApps(
                storedEmergencyApps = emptySet(),
                hasExplicitSelection = false,
                defaultEmergencyApps = setOf("maps")
            )
        )
    }

    @Test
    fun retainInstalledTrackedPackages_dropsMissingPackages_butKeepsController() {
        val retained = PolicyEngine.retainInstalledTrackedPackages(
            trackedPackages = setOf("controller", "installed", "missing"),
            controllerPackageName = "controller"
        ) { pkg ->
            pkg == "installed"
        }

        assertEquals(setOf("controller", "installed"), retained)
    }

    @Test
    fun recoverTrackedSuspendedPackages_mergesDetectedSuspensionsIntoTrackedSet() {
        val recovered = PolicyEngine.recoverTrackedSuspendedPackages(
            trackedPackages = setOf("tracked.app"),
            candidatePackages = setOf("tracked.app", "stuck.app", "clear.app"),
            canVerifyPackageSuspension = true
        ) { packageName ->
            packageName == "tracked.app" || packageName == "stuck.app"
        }

        assertEquals(setOf("tracked.app", "stuck.app"), recovered)
    }

    @Test
    fun recoverTrackedSuspendedPackages_skipsRecoveryWhenVerificationUnavailable() {
        val recovered = PolicyEngine.recoverTrackedSuspendedPackages(
            trackedPackages = setOf("tracked.app"),
            candidatePackages = setOf("stuck.app"),
            canVerifyPackageSuspension = false
        ) { true }

        assertEquals(setOf("tracked.app"), recovered)
    }

    @Test
    fun pickNextAlarmAtMs_ignores_stale_candidates() {
        val next = PolicyEngine.pickNextAlarmAtMs(
            nowMs = 10_000L,
            scheduleNextTransitionAtMs = 9_000L,
            budgetNextCheckAtMs = 15_000L,
            touchGrassBreakUntilMs = 8_000L
        )

        assertEquals(15_000L, next)
    }

    @Test
    fun pickNextAlarmAtMs_returns_null_when_all_candidates_are_stale() {
        val next = PolicyEngine.pickNextAlarmAtMs(
            nowMs = 10_000L,
            scheduleNextTransitionAtMs = 9_000L,
            budgetNextCheckAtMs = 10_000L,
            touchGrassBreakUntilMs = null
        )

        assertNull(next)
    }

    @Test
    fun pickNextAlarmAtMs_reschedules_immediately_for_overdue_budget_when_requested() {
        val next = PolicyEngine.pickNextAlarmAtMs(
            nowMs = 10_000L,
            scheduleNextTransitionAtMs = null,
            budgetNextCheckAtMs = 10_000L,
            touchGrassBreakUntilMs = null,
            keepOverdueBudgetCheck = true
        )

        assertEquals(10_001L, next)
    }

    @Test
    fun encodeSingleAppScheduleTarget_roundTrip() {
        val target = PolicyEngine.encodeSingleAppScheduleTarget("com.example.app")
        assertEquals("com.example.app", PolicyEngine.decodeSingleAppScheduleTarget(target))
        assertTrue(PolicyEngine.isSingleAppScheduleTarget(target))
    }

    @Test
    fun decodeSingleAppScheduleTarget_rejects_invalidPayload() {
        assertNull(PolicyEngine.decodeSingleAppScheduleTarget("app:"))
        assertNull(PolicyEngine.decodeSingleAppScheduleTarget("group:com.example"))
        assertNull(PolicyEngine.decodeSingleAppScheduleTarget(""))
        assertFalse(PolicyEngine.isSingleAppScheduleTarget(""))
    }

    @Test
    fun resolveLiveScheduleState_marksAppOnlySchedulesActiveAndStrict() {
        val state = PolicyEngine.resolveLiveScheduleState(
            scheduleDecision = ScheduleDecision(
                shouldLock = false,
                strictActive = false,
                blockedGroupIds = emptySet(),
                reason = "Outside scheduled blocks",
                activeBlockIds = setOf(7L),
                nextTransitionAt = ZonedDateTime.parse("2026-01-05T11:00:00Z")
            ),
            scheduleBlocks = listOf(
                ScheduleBlock(
                    id = 7L,
                    name = "Solo app",
                    daysMask = 1,
                    startMinute = 600,
                    endMinute = 660,
                    kind = ScheduleBlockKind.GROUPS.name,
                    strict = true
                )
            ),
            targetedActiveBlockIds = setOf(7L),
            scheduledGroupIds = emptySet(),
            scheduledAppPackages = setOf("com.example.app")
        )

        assertTrue(state.active)
        assertTrue(state.strictActive)
        assertTrue(state.blockedGroupIds.isEmpty())
        assertEquals(setOf("com.example.app"), state.blockedAppPackages)
        assertTrue(state.reason.contains("app schedule", ignoreCase = true))
    }

    @Test
    fun resolveLiveScheduleState_keepsHelperReasonWhenNoTargetsResolve() {
        val state = PolicyEngine.resolveLiveScheduleState(
            scheduleDecision = ScheduleDecision(
                shouldLock = false,
                strictActive = false,
                blockedGroupIds = emptySet(),
                reason = "Group schedule active: Strict social",
                activeBlockIds = setOf(5L),
                nextTransitionAt = ZonedDateTime.parse("2026-01-05T11:00:00Z")
            ),
            scheduleBlocks = emptyList(),
            targetedActiveBlockIds = emptySet(),
            scheduledGroupIds = emptySet(),
            scheduledAppPackages = emptySet()
        )

        assertFalse(state.active)
        assertFalse(state.strictActive)
        assertEquals("Group schedule active: Strict social", state.reason)
    }

    @Test
    fun resolveActiveScheduleTargets_filtersOrphanGroupsAndUninstalledApps() {
        val targets = PolicyEngine.resolveActiveScheduleTargets(
            activeBlockIds = setOf(5L, 7L),
            scheduleBlocks = listOf(
                ScheduleBlock(
                    id = 5L,
                    name = "Strict social",
                    daysMask = 1,
                    startMinute = 600,
                    endMinute = 660,
                    kind = ScheduleBlockKind.GROUPS.name,
                    strict = true
                ),
                ScheduleBlock(
                    id = 7L,
                    name = "Solo app",
                    daysMask = 1,
                    startMinute = 600,
                    endMinute = 660,
                    kind = ScheduleBlockKind.GROUPS.name,
                    strict = true
                )
            ),
            scheduleGroupTargetsByBlock = mapOf(
                5L to setOf("orphan", "study")
            ),
            scheduleAppTargetsByBlock = mapOf(
                7L to setOf("missing.app", "installed.app")
            ),
            validGroupIds = setOf("study"),
            isPackageInstalled = { packageName -> packageName == "installed.app" }
        )

        assertEquals(setOf(5L, 7L), targets.targetedActiveBlockIds)
        assertEquals(setOf("study"), targets.scheduledGroupIds)
        assertEquals(setOf("installed.app"), targets.scheduledAppPackages)
        assertNull(targets.warning)
    }

    @Test
    fun resolveActiveScheduleTargets_warnsWhenWindowHasZeroEffectiveTargets() {
        val targets = PolicyEngine.resolveActiveScheduleTargets(
            activeBlockIds = setOf(5L),
            scheduleBlocks = listOf(
                ScheduleBlock(
                    id = 5L,
                    name = "Strict social",
                    daysMask = 1,
                    startMinute = 600,
                    endMinute = 660,
                    kind = ScheduleBlockKind.GROUPS.name,
                    strict = true
                )
            ),
            scheduleGroupTargetsByBlock = mapOf(5L to setOf("orphan")),
            scheduleAppTargetsByBlock = mapOf(5L to setOf("missing.app")),
            validGroupIds = emptySet(),
            isPackageInstalled = { false }
        )

        assertTrue(targets.targetedActiveBlockIds.isEmpty())
        assertTrue(targets.scheduledGroupIds.isEmpty())
        assertTrue(targets.scheduledAppPackages.isEmpty())
        assertEquals(ScheduleTargetDiagnosticCode.NO_EFFECTIVE_GROUP_AND_APP_TARGETS, targets.diagnosticCode)
        assertEquals(
            "Schedule window active but group targets are invalid and app targets are not installed: Strict social",
            targets.warning
        )
    }

    @Test
    fun resolveActiveScheduleTargets_warnsWhenActiveBlocksHaveNoConfiguredTargets() {
        val targets = PolicyEngine.resolveActiveScheduleTargets(
            activeBlockIds = setOf(5L),
            scheduleBlocks = listOf(
                ScheduleBlock(
                    id = 5L,
                    name = "Strict social",
                    daysMask = 1,
                    startMinute = 600,
                    endMinute = 660,
                    kind = ScheduleBlockKind.GROUPS.name,
                    strict = true
                )
            ),
            scheduleGroupTargetsByBlock = emptyMap(),
            scheduleAppTargetsByBlock = emptyMap(),
            validGroupIds = emptySet(),
            isPackageInstalled = { false }
        )

        assertTrue(targets.targetedActiveBlockIds.isEmpty())
        assertTrue(targets.scheduledGroupIds.isEmpty())
        assertTrue(targets.scheduledAppPackages.isEmpty())
        assertEquals(ScheduleTargetDiagnosticCode.NO_CONFIGURED_TARGETS, targets.diagnosticCode)
        assertEquals(
            "Schedule window active but no targets are configured: Strict social",
            targets.warning
        )
    }

    @Test
    fun shouldRefreshStrictScheduleForInstall_whenTransitionIsOverdue() {
        assertTrue(
            PolicyEngine.shouldRefreshStrictScheduleForInstall(
                scheduleStrictComputed = true,
                scheduleNextTransitionAtMs = 10_000L,
                nowMs = 10_000L
            )
        )
    }

    @Test
    fun shouldRefreshStrictScheduleForInstall_whenStrictStateIsStillFresh() {
        assertFalse(
            PolicyEngine.shouldRefreshStrictScheduleForInstall(
                scheduleStrictComputed = true,
                scheduleNextTransitionAtMs = 20_000L,
                nowMs = 10_000L
            )
        )
    }

    @Test
    fun computeClosestBudgetCheckAtMs_excludesFullyExemptButKeepsAllowlistEligible() {
        val next = PolicyEngine.computeClosestBudgetCheckAtMs(
            nowMs = 10_000L,
            baseNextCheckAtMs = null,
            groupPolicies = listOf(
                GroupPolicyInput(
                    groupId = "g1",
                    priorityIndex = 0,
                    strictInstallParticipates = false,
                    dailyLimitMs = 60_000L,
                    hourlyLimitMs = null,
                    opensPerDay = null,
                    members = setOf("allow.app", "hidden.app"),
                    emergencyConfig = EmergencyConfigInput(false, 0, 0),
                    scheduleBlocked = false
                )
            ),
            appPolicies = listOf(
                AppPolicyInput(
                    packageName = "allow.app",
                    dailyLimitMs = 60_000L,
                    hourlyLimitMs = null,
                    opensPerDay = null,
                    emergencyConfig = EmergencyConfigInput(false, 0, 0),
                    scheduleBlocked = false
                )
            ),
            usageInputs = UsageInputs(
                usedTodayMs = mapOf("allow.app" to 30_000L, "hidden.app" to 59_000L),
                usedHourMs = emptyMap(),
                opensToday = emptyMap()
            ),
            fullyExemptPackages = setOf("hidden.app"),
            emergencyStates = emptyList()
        )

        assertEquals(40_000L, next)
    }
}
