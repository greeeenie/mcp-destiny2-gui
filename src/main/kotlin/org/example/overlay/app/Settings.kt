package org.example.overlay.app

/**
 * Пользовательские настройки. Лежат открытым текстом в `settings.json` — секретов здесь нет
 * (токен и BYOK-ключи живут в отдельных файлах под DPAPI).
 */
data class Settings(
    /** Меняется только правкой `settings.json`: в консоли этого поля нет. */
    val baseUrl: String = DEFAULT_BASE_URL,
    val hud: HudSettings = HudSettings(),
    val audio: AudioSettings = AudioSettings(),
    /** VK-код клавиши push-to-talk. По умолчанию Right Alt (`VK_RMENU`), §5.1. */
    val pttKeyCode: Int = DEFAULT_PTT_KEY_CODE,
    /** Модификаторы комбинации; отдельное поле сохраняет совместимость со старыми settings.json. */
    val pttModifierKeyCodes: List<Int> = emptyList(),
    /** Модель ответа из GET /voice/models. `null` — серверная по умолчанию. */
    val chatModel: String? = null,
    /** Модель распознавания речи оттуда же. `null` — клиентский дефолт [DEFAULT_STT_MODEL]. */
    val sttModel: String? = null,
    val rememberPassword: Boolean = false,
) {
    fun pttKeyCodes(): List<Int> = (pttModifierKeyCodes + pttKeyCode).distinct()

    companion object {
        const val DEFAULT_BASE_URL = "https://duoxik.space/mcp-destiny2-client"
        const val DEFAULT_PTT_KEY_CODE = 0xA5

        /**
         * Распознавание по умолчанию — Fish Audio, а не серверный Whisper: дефолт задан
         * на клиенте, чтобы не ждать выкатки сервера. `sttModel = null` означает именно его.
         */
        const val DEFAULT_STT_MODEL = "fish-audio/asr"
    }
}

/**
 * Положение хранится в логических единицах (Dp), см. риск 11. `null` — «ещё не двигали».
 * Размера здесь нет: окно всегда считает его из ответа (см. [org.example.overlay.ui.HudSizing]).
 */
data class HudSettings(
    val x: Float? = null,
    val y: Float? = null,
    /** Якорные края сохраняются явно: по одной координате нельзя однозначно восстановить их у центра экрана. */
    val anchorEnd: Boolean? = null,
    val anchorBottom: Boolean? = null,
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
