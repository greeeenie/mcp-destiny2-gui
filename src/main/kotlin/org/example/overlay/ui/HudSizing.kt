package org.example.overlay.ui

import org.example.overlay.markdown.MdBlock

/**
 * Ширина оверлея по содержимому ответа.
 *
 * Смысл в том, что разным ответам нужно разное место: «переложил на титана» умещается в узкую
 * полоску, а таблица на четыре колонки требует ширины, иначе колонки сплющиваются в кашу.
 * Прозу же расширять незачем — она переносится, и длинная строка только мешает читать.
 *
 * Ширину приходится считать: содержимое согласится на любую и не скажет, какая ему нужна.
 * Высоту, наоборот, меряет сам лейаут — см. `HudWindow`.
 */
object HudSizing {

    const val MIN_WIDTH = 300f
    const val MAX_WIDTH = 900f

    /** Пустая полоса: шапка с иконкой, дорожкой уровня и шестерёнкой плюс поля окна. */
    const val MIN_HEIGHT = 50f
    const val MAX_HEIGHT = 560f

    /** Сторона свёрнутой пилюли: в покое HUD — квадрат с одной иконкой микрофона. */
    const val COLLAPSED = 48f

    /** Ширина панели за вычетом текста: отступы окна и полоса прокрутки. */
    private const val HORIZONTAL_CHROME = 44f

    /** Проза переносится, поэтому шире этого её строку не растягиваем. */
    private const val PROSE_CHARS = 52

    /** Потолок на колонку — тот же, что в таблице рендера. */
    private const val MAX_COLUMN_CHARS = 28

    /** Разделители и отступы ячейки в символах. */
    private const val COLUMN_PADDING_CHARS = 3

    private const val LIST_MARKER_CHARS = 2
    private const val QUOTE_MARKER_CHARS = 2

    /**
     * Приближение средней ширины символа относительно кегля. Точную даст только измерение
     * шрифта, но для выбора ширины окна этого хватает: ошибка в пару символов не видна.
     */
    private const val CHAR_WIDTH_RATIO = 0.62f

    fun width(blocks: List<MdBlock>, fontSize: Float): Float {
        val chars = blocks.maxOfOrNull(::charsOf) ?: 0
        return (chars * fontSize * CHAR_WIDTH_RATIO + HORIZONTAL_CHROME).coerceIn(MIN_WIDTH, MAX_WIDTH)
    }

    /**
     * Потолок ширины прозы — колонка в [PROSE_CHARS] символов. Пока первый абзац ответа
     * дописывается, [width] растёт с каждым словом; кожуху окна на время хода нужна сразу
     * конечная ширина, иначе каждая дельта дёргала бы границы (см. `HudWindow`).
     */
    fun proseWidth(fontSize: Float): Float =
        (PROSE_CHARS * fontSize * CHAR_WIDTH_RATIO + HORIZONTAL_CHROME).coerceIn(MIN_WIDTH, MAX_WIDTH)

    private fun charsOf(block: MdBlock): Int = when (block) {
        is MdBlock.Heading -> minOf(block.text.visualLength, PROSE_CHARS)
        is MdBlock.Paragraph -> minOf(block.text.visualLength, PROSE_CHARS)

        // Код не переносим — считаем по самой длинной строке, но с тем же потолком окна.
        is MdBlock.CodeBlock -> block.code.lineSequence().maxOfOrNull { it.length } ?: 0

        is MdBlock.Quote -> (block.blocks.maxOfOrNull(::charsOf) ?: 0) + QUOTE_MARKER_CHARS

        is MdBlock.MdList -> block.items
            .maxOfOrNull { item -> (item.maxOfOrNull(::charsOf) ?: 0) + LIST_MARKER_CHARS }
            ?: 0

        is MdBlock.Table -> tableChars(block)

        MdBlock.Rule -> 0
    }

    private fun tableChars(table: MdBlock.Table): Int {
        val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
        if (columns == 0) return 0
        return (0 until columns).sumOf { index ->
            val header = table.header.getOrNull(index)?.visualLength ?: 0
            val longestCell = table.rows.maxOfOrNull { it.getOrNull(index)?.visualLength ?: 0 } ?: 0
            minOf(maxOf(header, longestCell), MAX_COLUMN_CHARS) + COLUMN_PADDING_CHARS
        }
    }
}
