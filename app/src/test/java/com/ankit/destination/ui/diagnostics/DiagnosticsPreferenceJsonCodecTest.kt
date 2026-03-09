package com.ankit.destination.ui.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsPreferenceJsonCodecTest {

    @Test
    fun roundTrip_preservesNullStringValues() {
        val preferences = linkedMapOf<String, Any?>(
            "budget_reason" to null,
            "last_error" to null,
            "next_policy_wake_reason" to "wake",
            "schedule_blocked_groups" to setOf("alpha", "beta"),
            "touch_grass_threshold" to 3
        )

        val restored = DiagnosticsPreferenceJsonCodec.decode(
            DiagnosticsPreferenceJsonCodec.encode(preferences)
        )

        assertTrue(restored.containsKey("budget_reason"))
        assertNull(restored["budget_reason"])
        assertTrue(restored.containsKey("last_error"))
        assertNull(restored["last_error"])
        assertEquals("wake", restored["next_policy_wake_reason"])
        assertEquals(setOf("alpha", "beta"), restored["schedule_blocked_groups"])
        assertEquals(3, restored["touch_grass_threshold"])
    }

    @Test
    fun fromJson_rejectsNullStoredAsStringType() {
        val failure = runCatching {
            DiagnosticsPreferenceJsonCodec.decode(
                mapOf(
                    "schedule_lock_reason" to DiagnosticsPreferenceJsonEntry(
                        type = "string",
                        value = null
                    )
                )
            )
        }.exceptionOrNull()

        assertTrue(failure is Exception)
    }
}
