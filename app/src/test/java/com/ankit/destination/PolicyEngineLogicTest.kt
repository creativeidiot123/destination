package com.ankit.destination

import com.ankit.destination.policy.ModeState
import com.ankit.destination.policy.PolicyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineLogicTest {

    @Test
    fun touchGrassBreakExpiry_exitsNuclear_afterNextEvaluation() {
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
    fun touchGrassActive_forcesNuclear() {
        val nowMs = 1_000_000L
        val mode = PolicyEngine.resolveEffectiveMode(
            manualMode = ModeState.NORMAL,
            scheduleComputed = false,
            touchGrassBreakUntilMs = nowMs + 60_000L,
            nowMs = nowMs
        )
        assertEquals(ModeState.NUCLEAR, mode)
    }

    @Test
    fun scheduleAlwaysWins_overManualAndTouchGrass() {
        val nowMs = 1_000_000L
        val mode = PolicyEngine.resolveEffectiveMode(
            manualMode = ModeState.NORMAL,
            scheduleComputed = true,
            touchGrassBreakUntilMs = null,
            nowMs = nowMs
        )
        assertEquals(ModeState.NUCLEAR, mode)
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
}