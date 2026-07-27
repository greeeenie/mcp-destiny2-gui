package org.example.overlay.markdown

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * HTML → та же [MdBlock], что и Markdown.
 *
 * Модель вправе ответить любой из двух разметок, а рисование, таблицы и подгонка размера окна
 * остаются одни на оба формата. Разбор отдан jsoup: он чинит незакрытые теги, чего в потоке
 * дельт хватает.
 *
 * Поддерживается подмножество, которое имеет смысл в оверлее. Незнакомые теги разворачиваются
 * в свой текст, `script` и `style` выбрасываются целиком.
 */
object HtmlReader {

    fun parse(html: String): List<MdBlock> {
        if (html.isBlank()) return emptyList()
        val body = Jsoup.parseBodyFragment(html).body()
        return blocks(body)
    }

    private fun blocks(parent: Element): List<MdBlock> {
        val result = mutableListOf<MdBlock>()
        // Текст, лежащий прямо в контейнере без абзаца, тоже нужно показать.
        val looseInline = mutableListOf<Node>()

        fun flushLoose() {
            if (looseInline.isEmpty()) return
            val inline = inlineOf(looseInline)
            looseInline.clear()
            if (!inline.isBlank) result += MdBlock.Paragraph(inline)
        }

        parent.childNodes().forEach { node ->
            val produced = (node as? Element)?.let(::blocksOf)
            if (produced == null) {
                if (node !is Element || node.normalName() !in DROPPED) looseInline += node
            } else {
                flushLoose()
                result += produced
            }
        }
        flushLoose()
        return result
    }

    /** `null` — элемент не блочный, значит его текст уходит в общий абзац. */
    private fun blocksOf(element: Element): List<MdBlock>? = when (element.normalName()) {
        "h1" -> listOf(MdBlock.Heading(1, inlineOf(element)))
        "h2" -> listOf(MdBlock.Heading(2, inlineOf(element)))
        "h3" -> listOf(MdBlock.Heading(3, inlineOf(element)))
        "h4" -> listOf(MdBlock.Heading(4, inlineOf(element)))
        "h5" -> listOf(MdBlock.Heading(5, inlineOf(element)))
        "h6" -> listOf(MdBlock.Heading(6, inlineOf(element)))

        "p" -> inlineOf(element).takeUnless { it.isBlank }?.let { listOf(MdBlock.Paragraph(it)) } ?: emptyList()

        "ul" -> listOf(listBlock(element, ordered = false))
        "ol" -> listOf(listBlock(element, ordered = true))

        "blockquote" -> listOf(MdBlock.Quote(blocks(element)))

        "pre" -> listOf(codeBlock(element))
        "hr" -> listOf(MdBlock.Rule)
        "table" -> listOf(table(element))

        // Контейнеры прозрачны: разбирается содержимое, сама обёртка ничего не значит.
        "div", "section", "article", "main", "body" -> blocks(element)

        else -> null
    }

    private fun listBlock(element: Element, ordered: Boolean): MdBlock.MdList {
        val items = element.children()
            .filter { it.normalName() == "li" }
            .map { item -> itemBlocks(item) }
        val start = element.attr("start").toIntOrNull() ?: 1
        return MdBlock.MdList(items, ordered, start)
    }

    /** Пункт списка обычно содержит голый текст, иногда — вложенный список. */
    private fun itemBlocks(item: Element): List<MdBlock> =
        blocks(item).ifEmpty { listOf(MdBlock.Paragraph(inlineOf(item))) }

    private fun codeBlock(element: Element): MdBlock.CodeBlock {
        val code = element.selectFirst("code")
        val language = code?.classNames()
            ?.firstOrNull { it.startsWith("language-") }
            ?.removePrefix("language-")
            ?.takeIf { it.isNotBlank() }
        val text = (code ?: element).wholeText().trim('\n')
        return MdBlock.CodeBlock(text, language)
    }

    private fun table(element: Element): MdBlock.Table {
        val rows = element.select("tr")
        val headerRow = rows.firstOrNull { row -> row.children().any { it.normalName() == "th" } }
        val header = headerRow?.children()?.map(::inlineOf).orEmpty()
        val bodyRows = rows
            .filter { it !== headerRow }
            .map { row -> row.children().map(::inlineOf) }
            .filter { it.isNotEmpty() }
        val columns = maxOf(header.size, bodyRows.maxOfOrNull { it.size } ?: 0)
        // Выравнивание задаёт шапка: у ячеек тела оно обычно то же и повторять его незачем.
        val alignments = List(columns) { index -> alignmentOf(headerRow?.children()?.getOrNull(index)) }
        return MdBlock.Table(header, bodyRows, alignments)
    }

    /** Выравнивание берём из `align` или из inline-стиля — больше CSS мы не понимаем. */
    private fun alignmentOf(cell: Element?): MdAlign {
        val raw = cell?.attr("align")?.lowercase().orEmpty().ifBlank {
            cell?.attr("style")?.lowercase()?.substringAfter("text-align:", "")?.trim()?.substringBefore(';').orEmpty()
        }
        return when {
            raw.startsWith("center") -> MdAlign.CENTER
            raw.startsWith("right") -> MdAlign.RIGHT
            else -> MdAlign.LEFT
        }
    }

    // --- инлайн ---

    private data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val strikethrough: Boolean = false,
        val link: String? = null,
    )

    private fun inlineOf(element: Element): MdInline = inlineOf(element.childNodes())

    private fun inlineOf(nodes: List<Node>): MdInline {
        val spans = mutableListOf<MdSpan>()
        nodes.forEach { appendInline(it, Style(), spans) }
        return MdInline(merge(spans)).trimmed()
    }

    private fun appendInline(node: Node, style: Style, out: MutableList<MdSpan>) {
        when (node) {
            is TextNode -> append(out, node.text(), style)

            is Element -> when (node.normalName()) {
                "b", "strong" -> descend(node, style.copy(bold = true), out)
                "i", "em" -> descend(node, style.copy(italic = true), out)
                "code", "kbd", "samp" -> descend(node, style.copy(code = true), out)
                "s", "del", "strike" -> descend(node, style.copy(strikethrough = true), out)
                "a" -> descend(node, style.copy(link = node.attr("href").takeIf { it.isNotBlank() }), out)
                "img" -> appendImage(node, out)
                "br" -> append(out, " ", style)
                in DROPPED -> Unit
                else -> descend(node, style, out)
            }

            else -> Unit
        }
    }

    private fun descend(element: Element, style: Style, out: MutableList<MdSpan>) {
        element.childNodes().forEach { appendInline(it, style, out) }
    }

    /** Картинка. Без пригодного `src` от неё остаётся только alt обычным текстом. */
    private fun appendImage(element: Element, out: MutableList<MdSpan>) {
        val alt = element.attr("alt").trim()
        val url = MdImages.resolve(element.attr("src"))
        if (url == null) {
            if (alt.isNotEmpty()) append(out, alt, Style())
        } else {
            out += MdSpan(text = alt, image = url)
        }
    }

    private fun append(spans: MutableList<MdSpan>, text: String, style: Style) {
        val display = if (style.code) {
            text
        } else {
            val collapsed = text.replace(WHITESPACE_RUN, " ")
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

    private val WHITESPACE_RUN = Regex("\\s{2,}")
    private val DROPPED = setOf("script", "style", "head", "meta", "link", "title")
}
