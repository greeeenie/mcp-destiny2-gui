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
    private val keyCodes: () -> List<Int>,
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean = { true },
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val isKeyDown: (Int) -> Boolean = ::isPhysicalKeyDown,
) {
    private var job: Job? = null

    fun start(onDown: suspend () -> Unit, onUp: suspend () -> Unit) {
        if (job != null) return
        if (!Platform.isWindows()) {
            log.warn("Global shortcut is unavailable outside Windows")
            return
        }
        job = scope.launch(Dispatchers.IO) {
            var pressed = false
            while (isActive) {
                val down = runCatching {
                    enabled() && keyCodes().let { keys -> keys.isNotEmpty() && keys.all(isKeyDown) }
                }.getOrDefault(false)
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
internal fun isPhysicalKeyDown(virtualKeyCode: Int): Boolean =
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
    private const val MOUSE_BUTTON_4 = 0x05
    private const val MOUSE_BUTTON_5 = 0x06
    private const val BACKSPACE = 0x08
    private const val TAB = 0x09
    private const val ENTER = 0x0D
    private const val CAPS_LOCK = 0x14
    private const val ESCAPE = 0x1B
    private const val SPACE = 0x20
    private const val PAGE_UP = 0x21
    private const val PAGE_DOWN = 0x22
    private const val END = 0x23
    private const val HOME = 0x24
    private const val LEFT = 0x25
    private const val UP = 0x26
    private const val RIGHT = 0x27
    private const val DOWN = 0x28
    private const val INSERT = 0x2D
    private const val DELETE = 0x2E
    private const val LEFT_SHIFT = 0xA0
    private const val RIGHT_SHIFT = 0xA1
    private const val LEFT_CONTROL = 0xA2
    const val RIGHT_ALT = 0xA5
    private const val LEFT_ALT = 0xA4
    private const val RIGHT_CONTROL = 0xA3

    private val SPECIAL_NAMES: Map<Int, String> = mapOf(
        MOUSE_BUTTON_4 to "Mouse X1", MOUSE_BUTTON_5 to "Mouse X2",
        BACKSPACE to "Backspace", TAB to "Tab", ENTER to "Enter", CAPS_LOCK to "Caps Lock",
        ESCAPE to "Esc", SPACE to "Space", PAGE_UP to "Page Up", PAGE_DOWN to "Page Down",
        END to "End", HOME to "Home", LEFT to "Left", UP to "Up", RIGHT to "Right", DOWN to "Down",
        INSERT to "Insert", DELETE to "Delete",
        LEFT_SHIFT to "Left Shift", RIGHT_SHIFT to "Right Shift",
        LEFT_CONTROL to "Left Ctrl", RIGHT_CONTROL to "Right Ctrl",
        LEFT_ALT to "Left Alt", RIGHT_ALT to "Right Alt",
    )

    val RECORDABLE_CODES: List<Int> = buildList {
        addAll(SPECIAL_NAMES.keys)
        addAll(0x30..0x39)
        addAll(0x41..0x5A)
        addAll(0x60..0x69)
        addAll(0x70..0x87)
    }.distinct()

    fun normalize(codes: List<Int>): List<Int> = codes.distinct().sortedWith(
        compareBy<Int> { if (it in MODIFIERS) 0 else 1 }.thenBy { RECORDABLE_CODES.indexOf(it) },
    )

    fun name(code: Int): String = SPECIAL_NAMES[code] ?: when (code) {
        in 0x30..0x39, in 0x41..0x5A -> code.toChar().toString()
        in 0x60..0x69 -> "Numpad ${code - 0x60}"
        in 0x70..0x87 -> "F${code - 0x6F}"
        else -> "0x%02X".format(code)
    }

    fun combinationName(codes: List<Int>): String = normalize(codes).joinToString(" + ", transform = ::name)

    private val MODIFIERS = setOf(LEFT_SHIFT, RIGHT_SHIFT, LEFT_CONTROL, RIGHT_CONTROL, LEFT_ALT, RIGHT_ALT)
}
