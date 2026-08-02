package org.example.overlay.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HudHistoryNavigationTest {
    @Test
    fun `next page advances before the end of history`() {
        assertEquals(2, nextHudHistoryIndex(current = 1, historySize = 3))
    }

    @Test
    fun `next page stays on the final history entry`() {
        assertEquals(2, nextHudHistoryIndex(current = 2, historySize = 3))
    }

    @Test
    fun `live feed has no page to the right`() {
        assertNull(nextHudHistoryIndex(current = null, historySize = 3))
    }
}
