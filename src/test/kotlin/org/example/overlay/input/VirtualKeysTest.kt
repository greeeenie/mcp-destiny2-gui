package org.example.overlay.input

import org.example.overlay.app.Settings
import kotlin.test.Test
import kotlin.test.assertEquals

class VirtualKeysTest {

    @Test
    fun `formats modifiers before the primary key`() {
        val shortcut = VirtualKeys.normalize(listOf(0x20, 0xA2, 0xA0))

        assertEquals("Left Shift + Left Ctrl + Space", VirtualKeys.combinationName(shortcut))
    }

    @Test
    fun `settings keep a legacy single push to talk key`() {
        val settings = Settings(pttKeyCode = VirtualKeys.RIGHT_ALT)

        assertEquals(listOf(VirtualKeys.RIGHT_ALT), settings.pttKeyCodes())
    }
}
