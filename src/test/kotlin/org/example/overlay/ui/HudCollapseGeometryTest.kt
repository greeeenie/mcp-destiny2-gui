package org.example.overlay.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class HudCollapseGeometryTest {
    @Test
    fun `collapse keeps width until panel becomes a horizontal strip`() {
        val start = PanelGeometry(x = 20f, y = 30f, width = 480f, height = 240f)
        val target = PanelGeometry(x = 452f, y = 222f, width = 48f, height = 48f)

        val (strip, pill) = collapseWaypoints(start, target)

        assertEquals(PanelGeometry(x = 20f, y = 222f, width = 480f, height = 48f), strip)
        assertEquals(target, pill)
    }

    @Test
    fun `interrupted collapse rebuilds strip from current geometry`() {
        val interrupted = PanelGeometry(x = 140f, y = 120f, width = 260f, height = 110f)
        val target = PanelGeometry(x = 352f, y = 182f, width = 48f, height = 48f)

        val (strip, pill) = collapseWaypoints(interrupted, target)

        assertEquals(interrupted.x, strip.x)
        assertEquals(interrupted.width, strip.width)
        assertEquals(target.y, strip.y)
        assertEquals(target.height, strip.height)
        assertEquals(target, pill)
    }
}
