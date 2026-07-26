package org.example.overlay.markdown

/**
 * Своя модель разобранного Markdown.
 *
 * Между парсером и Compose она нужна затем, что рисование у нас собственное (вид оверлея),
 * а модель ещё и тестируется без UI: по строке Markdown видно, что именно получится на экране.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: MdInline) : MdBlock

    data class Paragraph(val text: MdInline) : MdBlock

    data class CodeBlock(val code: String, val language: String? = null) : MdBlock

    data class Quote(val blocks: List<MdBlock>) : MdBlock

    data class MdList(
        val items: List<List<MdBlock>>,
        val ordered: Boolean,
        val start: Int = 1,
    ) : MdBlock

    data class Table(
        val header: List<MdInline>,
        val rows: List<List<MdInline>>,
        val alignments: List<MdAlign>,
    ) : MdBlock

    data object Rule : MdBlock
}

enum class MdAlign { LEFT, CENTER, RIGHT }

/** Строка с разметкой: последовательность отрезков со своими начертаниями. */
data class MdInline(val spans: List<MdSpan>) {
    val plainText: String get() = spans.joinToString("") { it.text }

    val isBlank: Boolean get() = plainText.isBlank()

    companion object {
        val EMPTY = MdInline(emptyList())

        fun plain(text: String) = MdInline(listOf(MdSpan(text)))
    }
}

data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strikethrough: Boolean = false,
    val link: String? = null,
)
