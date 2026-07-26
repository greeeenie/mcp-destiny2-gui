package org.example.overlay.ui

import org.example.overlay.markdown.MdBlock

/**
 * Ширина оверлея по содержимому ответа.
 *
 * Смысл в том, что разным ответам нужно разное место: «переложил на титана» умещается в узкую
 * полоску, а таблица на четыре колонки требует ширины, иначе колонки сплющиваются в кашу.
 * Прозу же расширять незачем — она переносится, и длинная строка только мешает читать.
 *
 * Высоту так не считают: её честно измеряет лейаут, потому что перенос строк зависит от
 * получившейся ширины.
 */
object HudSizing {

    const val MIN_WIDTH = 300f
    const val MAX_WIDTH = 900f
    const val MIN_HEIGHT = 120f
    const val MAX_HEIGHT = 560f

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

    /** Межстрочный интервал Material 3 для мелкого текста. */
    private const val LINE_HEIGHT_RATIO = 1.5f

    /** Постоянные части окна: статус с полоской уровня, кнопки и отступы панели. */
    private const val CHROME_HEIGHT = 112.0
    private const val TRANSCRIPT_HEIGHT = 26.0
    private const val TOOL_LINE_HEIGHT = 15.0
    private const val AUTH_BANNER_HEIGHT = 74.0
    private const val MESSAGE_HEIGHT = 20.0

    private const val BLOCK_SPACING = 6.0
    private const val HEADING_LINE_FACTOR = 1.4
    private const val TABLE_ROW_FACTOR = 1.6
    private const val CODE_PADDING_LINES = 0.9
    private const val RULE_LINES = 0.7

    fun width(blocks: List<MdBlock>, fontSize: Float): Float {
        val chars = blocks.maxOfOrNull(::charsOf) ?: 0
        return (chars * fontSize * CHAR_WIDTH_RATIO + HORIZONTAL_CHROME).coerceIn(MIN_WIDTH, MAX_WIDTH)
    }

    /**
     * Высота окна под тот же ответ.
     *
     * Считается, а не измеряется: содержимое живёт внутри окна и не может померить себя больше
     * окна — замер упирался бы в текущую высоту, и окно не росло бы никогда. Точность здесь и
     * не нужна: промах в пару строк гасится прокруткой внутри панели ответа.
     */
    fun height(
        blocks: List<MdBlock>,
        fontSize: Float,
        width: Float,
        extras: Extras = Extras(),
    ): Float {
        val charsPerLine = charsPerLine(width, fontSize)
        val lineHeight = fontSize * LINE_HEIGHT_RATIO
        val content = blocks.sumOf { linesOf(it, charsPerLine) * lineHeight.toDouble() + BLOCK_SPACING }
        return (content + extras.height() + CHROME_HEIGHT).toFloat().coerceIn(MIN_HEIGHT, MAX_HEIGHT)
    }

    /** Всё, что в HUD не относится к ответу, но занимает высоту. */
    data class Extras(
        val userTranscript: Boolean = false,
        val toolLines: Int = 0,
        val authBanner: Boolean = false,
        val message: Boolean = false,
    ) {
        fun height(): Double =
            (if (userTranscript) TRANSCRIPT_HEIGHT else 0.0) +
                toolLines * TOOL_LINE_HEIGHT +
                (if (authBanner) AUTH_BANNER_HEIGHT else 0.0) +
                (if (message) MESSAGE_HEIGHT else 0.0)
    }

    private fun charsPerLine(width: Float, fontSize: Float): Int =
        ((width - HORIZONTAL_CHROME) / (fontSize * CHAR_WIDTH_RATIO)).toInt().coerceAtLeast(1)

    private fun linesOf(block: MdBlock, charsPerLine: Int): Double = when (block) {
        is MdBlock.Heading -> wrapped(block.text.plainText.length, charsPerLine) * HEADING_LINE_FACTOR
        is MdBlock.Paragraph -> wrapped(block.text.plainText.length, charsPerLine)

        // Код не переносится: сколько строк, столько и высоты, плюс строка языка.
        is MdBlock.CodeBlock -> block.code.count { it == '\n' } + 1.0 +
            (if (block.language != null) 1.0 else 0.0) + CODE_PADDING_LINES

        is MdBlock.Quote -> block.blocks.sumOf { linesOf(it, charsPerLine - 2) }

        is MdBlock.MdList -> block.items.sumOf { item ->
            item.sumOf { nested -> linesOf(nested, charsPerLine - 2) }
        }

        // Ряд таблицы всегда занимает строку с запасом на разделители и отступы ячейки.
        is MdBlock.Table -> (block.rows.size + if (block.header.isEmpty()) 0 else 1) * TABLE_ROW_FACTOR

        MdBlock.Rule -> RULE_LINES
    }

    private fun wrapped(chars: Int, charsPerLine: Int): Double =
        maxOf(1.0, kotlin.math.ceil(chars.toDouble() / charsPerLine))

    private fun charsOf(block: MdBlock): Int = when (block) {
        is MdBlock.Heading -> minOf(block.text.plainText.length, PROSE_CHARS)
        is MdBlock.Paragraph -> minOf(block.text.plainText.length, PROSE_CHARS)

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
            val header = table.header.getOrNull(index)?.plainText?.length ?: 0
            val longestCell = table.rows.maxOfOrNull { it.getOrNull(index)?.plainText?.length ?: 0 } ?: 0
            minOf(maxOf(header, longestCell), MAX_COLUMN_CHARS) + COLUMN_PADDING_CHARS
        }
    }
}
