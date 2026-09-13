package org.example.overlay.platform

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Structure
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.HCURSOR
import com.sun.jna.platform.win32.WinDef.POINT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Window

private const val CURSOR_SHOWING = 0x00000001

@Structure.FieldOrder("cbSize", "flags", "cursor", "screenPosition")
internal class CursorInfo : Structure() {
    @JvmField var cbSize: Int = 0
    @JvmField var flags: Int = 0
    @JvmField var cursor: HCURSOR? = null
    @JvmField var screenPosition: POINT = POINT()

    init {
        cbSize = size()
    }
}

internal interface CursorUser32 : StdCallLibrary {
    fun GetCursorInfo(cursorInfo: CursorInfo): Boolean

    companion object {
        val INSTANCE: CursorUser32 = Native.load("user32", CursorUser32::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

internal fun clickThroughExtendedStyle(style: Int, enabled: Boolean): Int =
    if (enabled) style or WinUser.WS_EX_TRANSPARENT else style and WinUser.WS_EX_TRANSPARENT.inv()

internal fun cursorIsShowing(flags: Int): Boolean = flags and CURSOR_SHOWING != 0

fun isSystemCursorVisible(): Boolean {
    if (!Platform.isWindows()) return true
    val cursorInfo = CursorInfo()
    return if (CursorUser32.INSTANCE.GetCursorInfo(cursorInfo)) cursorIsShowing(cursorInfo.flags) else true
}

fun setWindowClickThrough(window: Window, enabled: Boolean) {
    if (!Platform.isWindows()) return
    val handle = HWND(Native.getWindowPointer(window))
    val currentStyle = User32.INSTANCE.GetWindowLong(handle, WinUser.GWL_EXSTYLE)
    val nextStyle = clickThroughExtendedStyle(currentStyle, enabled)
    if (nextStyle != currentStyle) {
        User32.INSTANCE.SetWindowLong(handle, WinUser.GWL_EXSTYLE, nextStyle)
    }
}
