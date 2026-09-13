package org.example.overlay.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HudVisibilityTest {
    @Test
    fun `hides an idle prepared HUD`() {
        assertFalse(
            shouldShowHudWindow(
                prepared = true,
                expansionRequested = false,
                expanded = false,
                countingDown = false,
                dismissed = false,
            ),
        )
    }

    @Test
    fun `keeps HUD visible through activity and collapse`() {
        assertTrue(shouldShowHudWindow(true, true, false, false, false))
        assertTrue(shouldShowHudWindow(true, false, true, false, false))
        assertTrue(shouldShowHudWindow(true, false, false, true, false))
        assertTrue(shouldShowHudWindow(true, false, false, false, true))
    }
}
