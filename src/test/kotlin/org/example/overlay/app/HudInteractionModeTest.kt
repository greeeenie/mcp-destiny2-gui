package org.example.overlay.app

import kotlin.test.Test
import kotlin.test.assertEquals

class HudInteractionModeTest {
    @Test
    fun `double taps cycle expansion and interaction separately`() {
        val expanded = HudInteractionMode.PASS_THROUGH.next()
        val interactive = expanded.next()
        val reset = interactive.next()

        assertEquals(HudInteractionMode.EXPANDED_PASS_THROUGH, expanded)
        assertEquals(HudInteractionMode.INTERACTIVE, interactive)
        assertEquals(HudInteractionMode.PASS_THROUGH, reset)
    }
}
