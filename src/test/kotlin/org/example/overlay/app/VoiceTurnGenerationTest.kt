package org.example.overlay.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceTurnGenerationTest {
    @Test
    fun `two nearby taps form a double tap`() {
        val detector = DoubleTapDetector(maxGapMs = 350)

        assertFalse(detector.registerTap(1_000_000_000))
        assertTrue(detector.registerTap(1_300_000_000))
        assertFalse(detector.registerTap(1_400_000_000))
    }

    @Test
    fun `late second tap starts a new pair`() {
        val detector = DoubleTapDetector(maxGapMs = 350)

        assertFalse(detector.registerTap(1_000_000_000))
        assertFalse(detector.registerTap(1_500_000_000))
        assertTrue(detector.registerTap(1_700_000_000))
    }

    @Test
    fun `new turn invalidates callbacks from previous turn`() {
        val turns = VoiceTurnGeneration()
        val first = turns.next()
        val second = turns.next()
        var callbacks = 0

        assertFalse(turns.runIfCurrent(first) { callbacks++ })
        assertTrue(turns.runIfCurrent(second) { callbacks++ })
        assertEquals(1, callbacks)
    }

    @Test
    fun `stop invalidation suppresses callbacks from active turn`() {
        val turns = VoiceTurnGeneration()
        val active = turns.next()

        turns.invalidate()

        assertFalse(turns.isCurrent(active))
        assertFalse(turns.runIfCurrent(active) { error("stale callback must not run") })
    }

    @Test
    fun `turn numbers increase monotonically`() {
        val turns = VoiceTurnGeneration()

        val first = turns.next()
        val second = turns.next()

        assertTrue(second > first)
    }
}
