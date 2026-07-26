package org.example.overlay.markdown

/**
 * Единственная точка разбора ответа модели.
 *
 * Формат не навязываем: модель отвечает Markdown или HTML, а на выходе всегда одна и та же
 * блочная модель — значит рисование, таблицы и подгонка размера окна общие для обоих.
 */
object AnswerContent {

    /** Достаточно одного блочного тега в начале строки, чтобы считать ответ версткой HTML. */
    private val HTML_BLOCK_TAG = Regex(
        """(?im)^\s*<\s*(table|div|section|article|ul|ol|h[1-6]|p|pre|blockquote|hr)\b""",
    )

    fun parse(text: String): List<MdBlock> = when {
        text.isBlank() -> emptyList()
        looksLikeHtml(text) -> HtmlReader.parse(text)
        else -> MarkdownReader.parse(text)
    }

    fun looksLikeHtml(text: String): Boolean = HTML_BLOCK_TAG.containsMatchIn(text)
}
