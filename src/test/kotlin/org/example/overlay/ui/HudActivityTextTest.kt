package org.example.overlay.ui

import org.example.overlay.app.OverlayStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HudActivityTextTest {
    @Test
    fun `shows activity while a turn is active`() {
        assertEquals("Listening", hudActivityText(OverlayStatus.Listening))
        assertEquals("Thinking", hudActivityText(OverlayStatus.Thinking))
        assertEquals("Searching web", hudActivityText(OverlayStatus.SearchingWeb))
        assertEquals("Answering", hudActivityText(OverlayStatus.Answering))
        assertEquals(true, isAnimatedHudActivity(OverlayStatus.Listening))
        assertEquals(true, isAnimatedHudActivity(OverlayStatus.Answering))
    }

    @Test
    fun `returns to sync metadata when ready`() {
        assertNull(hudActivityText(OverlayStatus.Ready))
        assertEquals(false, isAnimatedHudActivity(OverlayStatus.Ready))
        assertEquals(false, isAnimatedHudActivity(OverlayStatus.Disconnected))
    }
}
