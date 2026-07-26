package org.example.overlay.app

/**
 * Пользовательские настройки. Лежат открытым текстом в `settings.json` — секретов здесь нет
 * (токен бэкенда живёт в `session.bin` под DPAPI, JWT Inworld на диск не пишется вовсе).
 */
data class Settings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val hud: HudSettings = HudSettings(),
    val audio: AudioSettings = AudioSettings(),
    /** VK-код клавиши push-to-talk. По умолчанию Right Alt (`VK_RMENU`), §5.1. */
    val pttKeyCode: Int = DEFAULT_PTT_KEY_CODE,
    /** Hands-free выключен по умолчанию: он стоит денег и ломается на колонках (§5.1, §6). */
    val handsFree: Boolean = false,
    val showAssistantText: Boolean = true,
    /**
     * Озвучивать ответы. Выключено: ответ читается в оверлее размеченным текстом.
     *
     * Выключение не сводится к «не играть звук» — иначе Inworld всё равно синтезировал бы речь
     * и брал за неё деньги. Оверлей дополнительно просит текстовую модальность отдельным кадром
     * `session.update` после серверного (см. `InworldProtocol.textOnlyOutput`). Серверную строку
     * он при этом не разбирает и не меняет — правило §3.2 остаётся в силе.
     *
     * Правильное место для этой настройки — бэкенд: там она уберёт и синтез, и лишний трафик.
     */
    val speakResponses: Boolean = false,
    /**
     * Просить у Inworld только текстовую модальность отдельным кадром. Это и есть экономия:
     * без него речь синтезируется и тарифицируется.
     *
     * Вынесено в отдельную настройку на случай, если конкретный деплой Inworld отвергнет патч
     * `session.update` — тогда его можно выключить, не включая звук обратно.
     */
    val requestTextOnly: Boolean = true,
    /**
     * Закрывать ход явным `input_audio_buffer.commit` вместо хвоста тишины. Выключено:
     * решение принимается по замеру на живом стенде (§5.2, открытый вопрос плана).
     */
    val commitOnRelease: Boolean = false,
    val rememberPassword: Boolean = false,
    /** До первой настройки консоль открывается сама: без устройств и клавиши оверлей бесполезен. */
    val firstRunCompleted: Boolean = false,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://duoxik.space/mcp-destiny2-client"
        const val DEFAULT_PTT_KEY_CODE = 0xA5
    }
}

/** Положение хранится в логических единицах (Dp), см. риск 11. `null` — «ещё не двигали». */
data class HudSettings(
    val x: Float? = null,
    val y: Float? = null,
    val opacity: Float = 0.82f,
    /**
     * Подгонять размер окна под ответ: узкая полоска под короткую фразу, широкое окно под
     * таблицу. Сохранённые [width] и [height] при этом не используются — они нужны, только
     * если игрок выставил размер руками.
     */
    val autoSize: Boolean = true,
    val width: Float = 360f,
    val height: Float = 200f,
    val fontSize: Float = 13f,
)

/** `null` — «системное устройство по умолчанию» (риск 3). */
data class AudioSettings(
    val inputMixer: String? = null,
    val outputMixer: String? = null,
)
