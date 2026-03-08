package com.ankit.destination

import com.ankit.destination.data.ScheduleBlock
import com.ankit.destination.data.ScheduleBlockKind
import com.ankit.destination.policy.ModeState
import com.ankit.destination.policy.EmergencyConfigInput
import com.ankit.destination.policy.GroupPolicyInput
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.schedule.ScheduleDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
        val strictActive = PolicyEngine.resolveStrictScheduleActive(
            scheduleStrictActive = true,
            groupInputs = listOf(
                GroupPolicyInput(
                    groupId = "study",
                    priorityIndex = 0,
                    strictEnabled = false,
                    dailyLimitMs = 0L,
                    hourlyLimitMs = 0L,
                    opensPerDay = 0,
                    members = setOf("app.one"),
                    emergencyConfig = EmergencyConfigInput(false, 0, 0),
                    scheduleBlocked = true
                )
            )
        )

        assertTrue(strictActive)
    }

    @Test
    fun resolveStrictScheduleActive_preservesExistingStrictGroupFallback() {
        val strictActive = PolicyEngine.resolveStrictScheduleActive(
            scheduleStrictActive = false,
            groupInputs = listOf(
                GroupPolicyInput(
                    groupId = "study",
                    priorityIndex = 0,
                    strictEnabled = true,
                    dailyLimitMs = 0L,
                    hourlyLimitMs = 0L,
                    opensPerDay = 0,
                    members = setOf("app.one"),
                    emergencyConfig = EmergencyConfigInput(false, 0, 0),
                    scheduleBlocked = true
                )
            )
        )

        assertTrue(strictActive)
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
}
