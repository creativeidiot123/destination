package com.ankit.destination

import com.ankit.destination.ui.deriveGroupId
import com.ankit.destination.ui.minuteToTimeLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class UiSelectionSupportTest {
    @Test
    fun deriveGroupId_normalizesSpacingAndSymbols() {
        assertEquals("social-media", deriveGroupId(" Social   Media! "))
        assertEquals("kids-youtube", deriveGroupId("Kids & YouTube"))
    }

    @Test
    fun minuteToTimeLabel_zeroPadsValues() {
        assertEquals("00:05", minuteToTimeLabel(5))
        assertEquals("13:45", minuteToTimeLabel(13 * 60 + 45))
    }
}
