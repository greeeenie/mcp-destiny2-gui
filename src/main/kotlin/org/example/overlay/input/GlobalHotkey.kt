package org.example.overlay.input

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Глобальная клавиша push-to-talk через опрос `GetAsyncKeyState` (§5.1 плана).
 *
 * Почему именно опрос, а не хук: `RegisterHotKey` не даёт отпускания клавиши, а
 * `SetWindowsHookExW(WH_KEYBOARD_LL)` — это глобальный хук клавиатуры, максимально «шумный»
 * профиль рядом с BattlEye (риск 1). `GetAsyncKeyState` — чтение состояния клавиатуры:
 * без хуков, без инъекций, без синтеза ввода в игру. Ниже профиля не бывает.
 */
class GlobalHotkey(
    private val keyCode: () -> Int,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val isKeyDown: (Int) -> Boolean = ::isPhysicalKeyDown,
) {
    private var job: Job? = null

    fun start(onDown: suspend () -> Unit, onUp: suspend () -> Unit) {
        if (job != null) return
        if (!Platform.isWindows()) {
            log.warn("Не Windows — горячая клавиша недоступна")
            return
        }
        job = scope.launch(Dispatchers.IO) {
            var pressed = false
            while (isActive) {
                val down = runCatching { isKeyDown(keyCode()) }.getOrDefault(false)
                if (down != pressed) {
                    pressed = down
                    if (down) onDown() else onUp()
                }
                delay(pollIntervalMs)
            }
        }
    }

    suspend fun stop() {
        val active = job ?: return
        job = null
        active.cancel()
    }

    private companion object {
        /** 16 мс — примерно кадр игры: задержка незаметна, нагрузка на ядро нулевая. */
        const val DEFAULT_POLL_INTERVAL_MS = 16L
        val log = LoggerFactory.getLogger(GlobalHotkey::class.java)
    }
}

/** Старший бит — «клавиша нажата прямо сейчас». */
private fun isPhysicalKeyDown(virtualKeyCode: Int): Boolean =
    (User32Keys.INSTANCE.GetAsyncKeyState(virtualKeyCode).toInt() and 0x8000) != 0

/** Собственный минимальный биндинг: не зависим от того, что именно объявлено в JNA User32. */
private interface User32Keys : StdCallLibrary {
    fun GetAsyncKeyState(vKey: Int): Short

    companion object {
        val INSTANCE: User32Keys = Native.load("user32", User32Keys::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

/** Коды клавиш, которые имеет смысл предлагать в настройках. */
object VirtualKeys {
    const val RIGHT_ALT = 0xA5
    const val LEFT_ALT = 0xA4
    const val RIGHT_CONTROL = 0xA3
    const val CAPS_LOCK = 0x14
    const val F13 = 0x7C
    const val MOUSE_BUTTON_4 = 0x05
    const val MOUSE_BUTTON_5 = 0x06

    val NAMES: Map<Int, String> = linkedMapOf(
        RIGHT_ALT to "Right Alt",
        LEFT_ALT to "Left Alt",
        RIGHT_CONTROL to "Right Ctrl",
        CAPS_LOCK to "Caps Lock",
        F13 to "F13",
        MOUSE_BUTTON_4 to "Мышь X1",
        MOUSE_BUTTON_5 to "Мышь X2",
    )

    fun name(code: Int): String = NAMES[code] ?: "0x%02X".format(code)
}
