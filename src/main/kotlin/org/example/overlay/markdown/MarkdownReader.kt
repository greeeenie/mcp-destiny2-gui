package org.example.overlay.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * Markdown → [MdBlock]. Разбор берём у парсера IntelliJ с GFM-флейвором (он же держит таблицы
 * и зачёркивание), а в свою модель переводим сами: рисование у нас собственное.
 */
object MarkdownReader {

    private val flavour = GFMFlavourDescriptor()

    /** Служебные символы разметки внутри узлов начертания: `**`, `_`, `~~`, backticks. */
    private val MARKER = Regex("^[*_~`]+$")
    private val WHITESPACE_RUN = Regex("\\s{2,}")

    fun parse(source: String): List<MdBlock> {
        if (source.isBlank()) return emptyList()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(source)
        return blocks(tree.children, source)
    }

    private fun blocks(nodes: List<ASTNode>, src: String): List<MdBlock> = nodes.flatMap { node ->
        if (node.type == MarkdownElementTypes.HTML_BLOCK) {
            htmlBlock(node, src)
        } else {
            listOfNotNull(block(node, src))
        }
    }

    private fun block(node: ASTNode, src: String): MdBlock? = when (node.type) {
        MarkdownElementTypes.PARAGRAPH -> inline(node, src).takeUnless { it.isBlank }?.let(MdBlock::Paragraph)
        MarkdownElementTypes.ATX_1 -> heading(1, node, src)
        MarkdownElementTypes.ATX_2 -> heading(2, node, src)
        MarkdownElementTypes.ATX_3 -> heading(3, node, src)
        MarkdownElementTypes.ATX_4 -> heading(4, node, src)
        MarkdownElementTypes.ATX_5 -> heading(5, node, src)
        MarkdownElementTypes.ATX_6 -> heading(6, node, src)
        MarkdownElementTypes.SETEXT_1 -> heading(1, node, src)
        MarkdownElementTypes.SETEXT_2 -> heading(2, node, src)
        MarkdownElementTypes.CODE_FENCE -> codeFence(node, src)
        MarkdownElementTypes.CODE_BLOCK -> codeBlock(node, src)
        MarkdownElementTypes.UNORDERED_LIST -> list(node, src, ordered = false)
        MarkdownElementTypes.ORDERED_LIST -> list(node, src, ordered = true)
        MarkdownElementTypes.BLOCK_QUOTE -> MdBlock.Quote(blocks(node.children, src))
        GFMElementTypes.TABLE -> table(node, src)
        MarkdownTokenTypes.HORIZONTAL_RULE -> MdBlock.Rule
        else -> null
    }

    /**
     * Вставку HTML внутри Markdown отдаём [HtmlReader]: модель нередко пишет прозу разметкой
     * Markdown, а таблицу — тегами. Раньше такой блок молча пропадал.
     */
    private fun htmlBlock(node: ASTNode, src: String): List<MdBlock> = HtmlReader.parse(node.text(src))

    private fun heading(level: Int, node: ASTNode, src: String): MdBlock.Heading {
        val content = node.children.firstOrNull { it.type == MarkdownTokenTypes.ATX_CONTENT }
            ?: node.children.firstOrNull { it.type == MarkdownTokenTypes.SETEXT_CONTENT }
            ?: node
        return MdBlock.Heading(level, inline(content, src).trimmed())
    }

    private fun codeFence(node: ASTNode, src: String): MdBlock.CodeBlock {
        val language = node.children
            .firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
            ?.text(src)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        // Каждая строка внутри ограды — отдельный токен; переводы строк парсер отдаёт как EOL.
        val code = buildString {
            var sawContent = false
            node.children.forEach { child ->
                when (child.type) {
                    MarkdownTokenTypes.CODE_FENCE_CONTENT -> {
                        append(child.text(src))
                        sawContent = true
                    }

                    MarkdownTokenTypes.EOL -> if (sawContent) append('\n')
                    else -> Unit
                }
            }
        }
        return MdBlock.CodeBlock(code.trim('\n'), language)
    }

    private fun codeBlock(node: ASTNode, src: String): MdBlock.CodeBlock {
        val code = node.children
            .filter { it.type == MarkdownTokenTypes.CODE_LINE }
            .joinToString("\n") { it.text(src).removePrefix("    ") }
        return MdBlock.CodeBlock(code.trim('\n'))
    }

    private fun list(node: ASTNode, src: String, ordered: Boolean): MdBlock.MdList {
        val items = node.children
            .filter { it.type == MarkdownElementTypes.LIST_ITEM }
            .map { item -> blocks(item.children, src) }
        val start = if (!ordered) {
            1
        } else {
            node.children
                .firstOrNull { it.type == MarkdownElementTypes.LIST_ITEM }
                ?.children
                ?.firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
                ?.text(src)
                ?.trim()
                ?.dropLast(1)
                ?.toIntOrNull()
                ?: 1
        }
        return MdBlock.MdList(items, ordered, start)
    }

    private fun table(node: ASTNode, src: String): MdBlock.Table {
        val header = node.children
            .firstOrNull { it.type == GFMElementTypes.HEADER }
            ?.let(::cellNodes)
            ?.map { inline(it, src).trimmed() }
            .orEmpty()
        val rows = node.children
            .filter { it.type == GFMElementTypes.ROW }
            .map { row -> cellNodes(row).map { inline(it, src).trimmed() } }
        return MdBlock.Table(header, rows, alignments(node, src, header.size))
    }

    private fun cellNodes(row: ASTNode): List<ASTNode> = row.children.filter { it.type == GFMTokenTypes.CELL }

    /**
     * Выравнивание колонок берётся из строки-разделителя: `:---` слева, `---:` справа,
     * `:---:` по центру. Парсер отдаёт разделитель то одним токеном на строку, то по токену
     * на колонку, поэтому собираем все и режем сами.
     */
    private fun alignments(node: ASTNode, src: String, columns: Int): List<MdAlign> {
        val separators = mutableListOf<String>()
        collectSeparators(node, src, separators)
        val cells = if (separators.size > 1) {
            separators
        } else {
            separators.firstOrNull()?.split('|').orEmpty()
        }
        val parsed = cells
            .map { it.trim() }
            .filter { it.contains('-') }
            .map { cell ->
                when {
                    cell.startsWith(':') && cell.endsWith(':') -> MdAlign.CENTER
                    cell.endsWith(':') -> MdAlign.RIGHT
                    else -> MdAlign.LEFT
                }
            }
        return List(columns) { index -> parsed.getOrElse(index) { MdAlign.LEFT } }
    }

    private fun collectSeparators(node: ASTNode, src: String, out: MutableList<String>) {
        if (node.type == GFMTokenTypes.TABLE_SEPARATOR) {
            val text = node.text(src).trim()
            if (text.contains('-')) out += text
            return
        }
        node.children.forEach { collectSeparators(it, src, out) }
    }

    // --- инлайн ---

    private data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val strikethrough: Boolean = false,
        val link: String? = null,
    )

    private fun inline(node: ASTNode, src: String): MdInline {
        val spans = mutableListOf<MdSpan>()
        if (node.children.isEmpty()) {
            append(spans, node.text(src), Style())
        } else {
            node.children.forEach { appendInline(it, src, Style(), spans) }
        }
        return MdInline(merge(spans))
    }

    private fun appendInline(node: ASTNode, src: String, style: Style, out: MutableList<MdSpan>) {
        when (node.type) {
            MarkdownElementTypes.STRONG -> descend(node, src, style.copy(bold = true), out)
            MarkdownElementTypes.EMPH -> descend(node, src, style.copy(italic = true), out)
            GFMElementTypes.STRIKETHROUGH -> descend(node, src, style.copy(strikethrough = true), out)
            MarkdownElementTypes.CODE_SPAN -> append(spans = out, text = codeSpanText(node, src), style = style.copy(code = true))

            MarkdownElementTypes.INLINE_LINK -> {
                val destination = node.children
                    .firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                    ?.text(src)
                    ?.trim()
                val textNode = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                if (textNode == null) {
                    append(out, node.text(src), style.copy(link = destination))
                } else {
                    descend(textNode, src, style.copy(link = destination), out)
                }
            }

            MarkdownElementTypes.IMAGE -> appendImage(node, src, out)

            MarkdownElementTypes.AUTOLINK, GFMTokenTypes.GFM_AUTOLINK -> {
                val text = node.text(src).trim('<', '>')
                append(out, text, style.copy(link = text))
            }

            MarkdownTokenTypes.EOL -> append(out, " ", style)

            // Маркер продолжения цитаты — разметка, а не текст: во второй строке `>` не нужен.
            MarkdownTokenTypes.BLOCK_QUOTE -> Unit

            // Одиночный тег посреди абзаца показывать угловыми скобками некрасиво: начертание
            // такой вставки всё равно потеряно, а сам тег игроку не нужен.
            MarkdownTokenTypes.HTML_TAG -> Unit

            else -> if (node.children.isEmpty()) {
                append(out, node.text(src), style)
            } else {
                descend(node, src, style, out)
            }
        }
    }

    /** `![alt](url)` — узел IMAGE со ссылкой внутри. Без пригодного url остаётся alt текстом. */
    private fun appendImage(node: ASTNode, src: String, out: MutableList<MdSpan>) {
        val link = node.children.firstOrNull { it.type == MarkdownElementTypes.INLINE_LINK } ?: node
        val destination = link.children
            .firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
            ?.text(src)
            ?.trim()
        val alt = link.children
            .firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
            ?.text(src)
            ?.trim('[', ']')
            ?.trim()
            .orEmpty()
        val url = MdImages.resolve(destination)
        if (url == null) {
            if (alt.isNotEmpty()) append(out, alt, Style())
        } else {
            out += MdSpan(text = alt, image = url)
        }
    }

    /** Внутрь узла начертания заходим, но сами символы разметки в текст не тянем. */
    private fun descend(node: ASTNode, src: String, style: Style, out: MutableList<MdSpan>) {
        node.children.forEach { child ->
            val isMarker = child.children.isEmpty() && MARKER.matches(child.text(src))
            val isBracket = child.children.isEmpty() && child.text(src) in BRACKETS
            if (!isMarker && !isBracket) appendInline(child, src, style, out)
        }
    }

    private fun codeSpanText(node: ASTNode, src: String): String =
        node.text(src).trim().trim('`')

    private fun append(spans: MutableList<MdSpan>, text: String, style: Style) {
        if (text.isEmpty()) return
        // Пробелы схлопываем: перевод строки, снятый маркер цитаты и отбивка списка иначе
        // складываются в дырки посреди фразы. Внутри кода текст остаётся как есть.
        val display = if (style.code) {
            text
        } else {
            val collapsed = text.replace(WHITESPACE_RUN, " ")
            // Пробел на стыке отрезков: перевод строки и снятый маркер цитаты дают по пробелу каждый.
            val previousEndsWithSpace = spans.lastOrNull()?.text?.lastOrNull()?.isWhitespace() ?: true
            if (previousEndsWithSpace) collapsed.trimStart() else collapsed
        }
        if (display.isEmpty()) return
        spans += MdSpan(
            text = display,
            bold = style.bold,
            italic = style.italic,
            code = style.code,
            strikethrough = style.strikethrough,
            link = style.link,
        )
    }

    /** Склеиваем соседние отрезки с одинаковым начертанием — иначе их сотни на абзац. */
    private fun merge(spans: List<MdSpan>): List<MdSpan> {
        val merged = mutableListOf<MdSpan>()
        spans.forEach { span ->
            val last = merged.lastOrNull()
            // Картинки не склеиваются даже одинаковые: две иконки подряд — это две иконки.
            val glueable = span.image == null && last?.image == null
            if (last != null && glueable && last.copy(text = "") == span.copy(text = "")) {
                merged[merged.lastIndex] = last.copy(text = last.text + span.text)
            } else {
                merged += span
            }
        }
        return merged
    }

    private fun MdInline.trimmed(): MdInline {
        if (spans.isEmpty()) return this
        val trimmed = spans.toMutableList()
        trimmed[0] = trimmed[0].copy(text = trimmed[0].text.trimStart())
        trimmed[trimmed.lastIndex] = trimmed.last().copy(text = trimmed.last().text.trimEnd())
        // Пустой текст — ещё не пустой отрезок: у картинки alt бывает пустым.
        return MdInline(trimmed.filter { it.text.isNotEmpty() || it.image != null })
    }

    private fun ASTNode.text(src: String): String = getTextInNode(src).toString()

    private val BRACKETS = setOf("[", "]", "(", ")", "!")
}
