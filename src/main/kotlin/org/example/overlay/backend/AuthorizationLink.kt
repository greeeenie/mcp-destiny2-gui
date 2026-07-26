package org.example.overlay.backend

import org.example.overlay.tools.ToolResult
import tools.jackson.databind.JsonNode

/**
 * Достаём ссылку авторизации Bungie из ответа инструмента `authorize` (риск 5 плана).
 *
 * Инструмент возвращает голую строку с URL. `McpToolMapper` бэкенда сначала пробует разобрать
 * одиночный `TextContent` как JSON, а URL им не является — значит в `output` приходит
 * **JSON-строка**, а не объект. Лезть в несуществующее поле нельзя, ищем ссылку регэкспом.
 */
object AuthorizationLink {

    private val URL_PATTERN = Regex("""https?://[^\s"'<>\\]+""")

    const val TOOL_NAME = "authorize"

    /** Текст бэкенда при протухшей авторизации (риск 8) — ловим его и показываем ту же плашку. */
    private const val RELINK_HINT = "link the bungie account"

    fun extract(result: ToolResult): String? =
        extract(result.output) ?: result.message?.let(::firstUrl)

    fun extract(node: JsonNode?): String? = when {
        node == null || node.isNull || node.isMissingNode -> null
        node.isString -> firstUrl(node.asString())
        else -> firstUrl(node.toString())
    }

    fun mentionsRelink(result: ToolResult): Boolean {
        val haystack = (result.message.orEmpty() + " " + result.output?.toString().orEmpty()).lowercase()
        return RELINK_HINT in haystack
    }

    private fun firstUrl(text: String): String? = URL_PATTERN.find(text)?.value
}
