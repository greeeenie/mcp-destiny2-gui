package org.example.overlay.platform

import com.sun.jna.platform.win32.WinUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class WindowClickThroughTest {
    @Test
    fun `toggles transparent extended style without changing other flags`() {
        val original = 0x00000008
        val enabled = clickThroughExtendedStyle(original, true)

        assertNotEquals(0, enabled and WinUser.WS_EX_TRANSPARENT)
        assertEquals(original, clickThroughExtendedStyle(enabled, false))
    }

    @Test
    fun `recognizes the Windows cursor showing flag`() {
        assertEquals(false, cursorIsShowing(0))
        assertEquals(true, cursorIsShowing(1))
        assertEquals(true, cursorIsShowing(3))
    }

    @Test
    fun `reads the native cursor state`() {
        assertNotNull(isSystemCursorVisible())
    }
}
