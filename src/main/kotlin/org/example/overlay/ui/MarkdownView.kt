package org.example.overlay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.overlay.markdown.MdAlign
import org.example.overlay.markdown.MdBlock
import org.example.overlay.markdown.MdInline

/**
 * Рисование разобранного Markdown. Своё, а не библиотечное, чтобы вид совпадал с оверлеем:
 * тёмная подложка, узкие отбивки, моноширинный код и таблицы, которые читаются с одного взгляда.
 */
@Composable
fun MarkdownView(
    blocks: List<MdBlock>,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block -> MarkdownBlock(block, fontSize) }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, fontSize: TextUnit) {
    when (block) {
        is MdBlock.Heading -> Text(
            text = block.text.annotated(fontSize),
            inlineContent = block.text.iconContent(fontSize),
            fontSize = headingSize(block.level, fontSize),
            fontWeight = FontWeight.SemiBold,
            color = OverlayColors.Text,
            modifier = Modifier.padding(top = if (block.level <= 2) 4.dp else 2.dp),
        )

        is MdBlock.Paragraph -> Text(
            text = block.text.annotated(fontSize),
            inlineContent = block.text.iconContent(fontSize),
            fontSize = fontSize,
            color = OverlayColors.Text,
        )

        is MdBlock.CodeBlock -> CodeBlockView(block, fontSize)

        is MdBlock.Quote -> Row {
            // Полоса слева вместо кавычек: в оверлее она читается быстрее.
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(quoteHeight(block))
                    .background(OverlayColors.Accent, RoundedCornerShape(1.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.blocks.forEach { nested -> MarkdownBlock(nested, fontSize) }
            }
        }

        is MdBlock.MdList -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            block.items.forEachIndexed { index, item ->
                Row {
                    Text(
                        text = if (block.ordered) "${block.start + index}." else "•",
                        fontSize = fontSize,
                        color = OverlayColors.TextDim,
                        modifier = Modifier.width(if (block.ordered) 22.dp else 14.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        item.forEach { nested -> MarkdownBlock(nested, fontSize) }
                    }
                }
            }
        }

        is MdBlock.Table -> TableView(block, fontSize)

        MdBlock.Rule -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = OverlayColors.Surface,
        )
    }
}

@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock, fontSize: TextUnit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OverlayColors.Surface, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        block.language?.let {
            Text(it, fontSize = (fontSize.value - 3).sp, color = OverlayColors.TextDim)
            Spacer(Modifier.height(2.dp))
        }
        Text(
            text = block.code,
            fontFamily = FontFamily.Monospace,
            fontSize = (fontSize.value - 1).sp,
            color = OverlayColors.Text,
            // Код не переносим: сломанная посередине строка читается хуже, чем прокрутка.
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun TableView(table: MdBlock.Table, fontSize: TextUnit) {
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    val weights = columnWeights(table, columns)
    val scrollState = rememberScrollState()
    val characterWidth = with(LocalDensity.current) { fontSize.toDp() } * TABLE_CHAR_WIDTH_RATIO

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OverlayColors.Surface.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val preferredWidth = characterWidth * weights.sum() + (columns * TABLE_CELL_PADDING_DP).dp
            val contentWidth = maxOf(maxWidth, preferredWidth)

            Box(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState)) {
                Column(modifier = Modifier.width(contentWidth)) {
                    if (table.header.isNotEmpty()) {
                        TableRow(table.header, columns, weights, table.alignments, fontSize, header = true)
                        HorizontalDivider(color = OverlayColors.TextDim.copy(alpha = 0.4f))
                    }
                    table.rows.forEachIndexed { index, row ->
                        TableRow(row, columns, weights, table.alignments, fontSize, header = false)
                        if (index != table.rows.lastIndex) {
                            HorizontalDivider(color = OverlayColors.Surface)
                        }
                    }
                }
            }
        }

        if (scrollState.maxValue > 0) {
            Spacer(Modifier.height(4.dp))
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier.fillMaxWidth().height(3.dp),
                style = ScrollbarStyle(
                    minimalHeight = 24.dp,
                    thickness = 3.dp,
                    shape = RoundedCornerShape(2.dp),
                    hoverDurationMillis = 120,
                    unhoverColor = OverlayColors.TextDim.copy(alpha = 0.35f),
                    hoverColor = OverlayColors.Accent.copy(alpha = 0.85f),
                ),
            )
        }
    }
}

@Composable
private fun TableRow(
    cells: List<MdInline>,
    columns: Int,
    weights: List<Float>,
    alignments: List<MdAlign>,
    fontSize: TextUnit,
    header: Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        repeat(columns) { index ->
            val cell = cells.getOrNull(index)
            Text(
                text = cell?.annotated(fontSize) ?: AnnotatedString(""),
                inlineContent = cell?.iconContent(fontSize).orEmpty(),
                fontSize = if (header) (fontSize.value - 1).sp else fontSize,
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                color = if (header) OverlayColors.TextDim else OverlayColors.Text,
                textAlign = when (alignments.getOrElse(index) { MdAlign.LEFT }) {
                    MdAlign.LEFT -> TextAlign.Start
                    MdAlign.CENTER -> TextAlign.Center
                    MdAlign.RIGHT -> TextAlign.End
                },
                modifier = Modifier.weight(weights[index]).padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * Ширина колонок — по самой длинной ячейке, но с потолком: одна многословная ячейка не должна
 * сплющивать остальные до нечитаемого состояния.
 */
private fun columnWeights(table: MdBlock.Table, columns: Int): List<Float> = List(columns) { index ->
    val header = table.header.getOrNull(index)?.visualLength ?: 0
    val longestCell = table.rows.maxOfOrNull { it.getOrNull(index)?.visualLength ?: 0 } ?: 0
    maxOf(header, longestCell).coerceIn(MIN_COLUMN_CHARS, MAX_COLUMN_CHARS).toFloat()
}

private const val MIN_COLUMN_CHARS = 4
private const val MAX_COLUMN_CHARS = 18
private const val TABLE_CHAR_WIDTH_RATIO = 0.62f
private const val TABLE_CELL_PADDING_DP = 8

private fun headingSize(level: Int, base: TextUnit): TextUnit = when (level) {
    1 -> (base.value + 5).sp
    2 -> (base.value + 3).sp
    3 -> (base.value + 1).sp
    else -> base
}

/** Оценка высоты полосы цитаты: точную даст лейаут, но полосе достаточно приблизительной. */
private fun quoteHeight(quote: MdBlock.Quote): androidx.compose.ui.unit.Dp =
    (18 * quote.blocks.size.coerceAtLeast(1)).dp

/** Насколько иконка крупнее кегля: перк в размер строки неразличим, чуть крупнее — читается. */
private const val ICON_SCALE = 1.7f

/**
 * Врезки для картинок: Compose рисует их внутри текста через `inlineContent`, ключ — URL.
 * Пока иконка едет по сети, место держит заглушка того же размера.
 */
@Composable
private fun MdInline.iconContent(fontSize: TextUnit): Map<String, InlineTextContent> {
    val side = (fontSize.value * ICON_SCALE).sp
    return spans.mapNotNull { span -> span.image?.let { url -> url to span } }
        .associate { (url, span) ->
            url to InlineTextContent(
                Placeholder(width = side, height = side, PlaceholderVerticalAlign.TextCenter),
            ) {
                RemoteIcon(
                    url = url,
                    contentDescription = span.text.ifBlank { null },
                    modifier = Modifier.size(side.value.dp),
                )
            }
        }
}

@Composable
private fun MdInline.annotated(fontSize: TextUnit): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        span.image?.let { url ->
            // Alt уходит альтернативным текстом: он виден копированию и скринридеру, но не глазу.
            appendInlineContent(id = url, alternateText = span.text.ifEmpty { " " })
            return@forEach
        }
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.SemiBold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            textDecoration = if (span.strikethrough) TextDecoration.LineThrough else null,
            color = if (span.code) OverlayColors.Ok else OverlayColors.Text,
            background = if (span.code) OverlayColors.Surface else androidx.compose.ui.graphics.Color.Unspecified,
            fontSize = if (span.code) (fontSize.value - 1).sp else fontSize,
        )
        val url = span.link
        if (url == null) {
            withStyle(style) { append(span.text) }
        } else {
            // Ссылку делаем настоящей: ссылка авторизации Bungie должна открываться кликом.
            withLink(
                LinkAnnotation.Url(
                    url = url,
                    styles = TextLinkStyles(
                        style = style.copy(
                            color = OverlayColors.Accent,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                ),
            ) {
                append(span.text)
            }
        }
    }
}
