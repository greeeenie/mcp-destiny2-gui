package org.example.overlay.platform

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import java.awt.Window

internal fun clickThroughExtendedStyle(style: Int, enabled: Boolean): Int =
    if (enabled) style or WinUser.WS_EX_TRANSPARENT else style and WinUser.WS_EX_TRANSPARENT.inv()

fun setWindowClickThrough(window: Window, enabled: Boolean) {
    if (!Platform.isWindows()) return
    val handle = HWND(Native.getWindowPointer(window))
    val currentStyle = User32.INSTANCE.GetWindowLong(handle, WinUser.GWL_EXSTYLE)
    val nextStyle = clickThroughExtendedStyle(currentStyle, enabled)
    if (nextStyle != currentStyle) {
        User32.INSTANCE.SetWindowLong(handle, WinUser.GWL_EXSTYLE, nextStyle)
    }
}
