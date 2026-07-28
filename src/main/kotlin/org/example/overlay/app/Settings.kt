package org.example.overlay.app

/**
 * Пользовательские настройки. Лежат открытым текстом в `settings.json` — секретов здесь нет
 * (токен бэкенда живёт в `session.bin` под DPAPI, JWT Inworld на диск не пишется вовсе).
 */
data class Settings(
    /** Меняется только правкой `settings.json`: в консоли этого поля нет. */
    val baseUrl: String = DEFAULT_BASE_URL,
    val hud: HudSettings = HudSettings(),
    val audio: AudioSettings = AudioSettings(),
    /** VK-код клавиши push-to-talk. По умолчанию Right Alt (`VK_RMENU`), §5.1. */
    val pttKeyCode: Int = DEFAULT_PTT_KEY_CODE,
    /** Модель ответа из GET /voice/models. `null` — серверная по умолчанию. */
    val chatModel: String? = null,
    /** Модель распознавания речи оттуда же. `null` — серверная по умолчанию. */
    val sttModel: String? = null,
    /** Язык распознавания: `ru` или `en`. `null` — авто: сервер не передаёт язык в Inworld. */
    val sttLanguage: String? = null,
    val rememberPassword: Boolean = false,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://duoxik.space/mcp-destiny2-client"
        const val DEFAULT_PTT_KEY_CODE = 0xA5
    }
}

/**
 * Положение хранится в логических единицах (Dp), см. риск 11. `null` — «ещё не двигали».
 * Размера здесь нет: окно всегда считает его из ответа (см. [org.example.overlay.ui.HudSizing]).
 */
data class HudSettings(
    val x: Float? = null,
    val y: Float? = null,
    val opacity: Float = 0.82f,
    val fontSize: Float = 13f,
    /** Через сколько секунд после ответа HUD сворачивается в пилюлю. Меньше [MIN_COLLAPSE_SECONDS] не даём. */
    val collapseSeconds: Int = DEFAULT_COLLAPSE_SECONDS,
) {
    companion object {
        const val MIN_COLLAPSE_SECONDS = 3
        const val MAX_COLLAPSE_SECONDS = 30
        const val DEFAULT_COLLAPSE_SECONDS = 6
    }
}

/** `null` — «системное устройство по умолчанию» (риск 3). */
data class AudioSettings(
    val inputMixer: String? = null,
    val outputMixer: String? = null,
)
