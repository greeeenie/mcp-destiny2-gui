package org.example.overlay.ui

import org.example.overlay.markdown.AnswerContent
import org.example.overlay.markdown.MarkdownReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HudSizingTest {

    private val fontSize = 13f

    private fun width(markdown: String): Float = HudSizing.width(MarkdownReader.parse(markdown), fontSize)

    @Test
    fun `пустой ответ оставляет окно узким`() {
        assertEquals(HudSizing.MIN_WIDTH, HudSizing.width(emptyList(), fontSize))
    }

    @Test
    fun `короткая фраза не расширяет окно`() {
        assertEquals(HudSizing.MIN_WIDTH, width("Переложил на титана."))
    }

    @Test
    fun `длинная проза не растягивается - она переносится`() {
        val long = "Нашёл сразу несколько подходящих мечей, ".repeat(10)

        val prose = width(long)

        assertTrue(prose < HudSizing.MAX_WIDTH, "проза не должна занимать всю ширину, а заняла $prose")
    }

    @Test
    fun `таблица шире прозы`() {
        val table = width(
            """
            | Оружие | Урон | Слот | Где лежит |
            | --- | --- | --- | --- |
            | Falling Guillotine | 1200 | тяжёлое | сейф |
            """.trimIndent(),
        )

        assertTrue(table > width("Нашёл один меч."), "таблица должна требовать больше места, а вышло $table")
    }

    @Test
    fun `широкая таблица упирается в потолок, а не уезжает за экран`() {
        val header = (1..12).joinToString("|", prefix = "|", postfix = "|") { "Колонка с длинным именем $it" }
        val separator = (1..12).joinToString("|", prefix = "|", postfix = "|") { "---" }
        val row = (1..12).joinToString("|", prefix = "|", postfix = "|") { "значение $it" }

        assertEquals(HudSizing.MAX_WIDTH, width("$header\n$separator\n$row"))
    }

    @Test
    fun `блок кода считается по самой длинной строке`() {
        val short = width("```\nval a = 1\n```")
        val long = width("```\nval a = 1\nval веснушкиИДлинноеИмяПеременной = someFunction(arg1, arg2, arg3)\n```")

        assertTrue(long > short, "длинная строка кода должна требовать больше места")
    }

    @Test
    fun `таблица HTML масштабируется так же, как Markdown`() {
        // Оба формата дают одну блочную модель, поэтому и размер окна должен получаться один.
        val markdown = AnswerContent.parse(
            """
            | Оружие | Урон | Слот |
            | --- | --- | --- |
            | Falling Guillotine | 1200 | тяжёлое |
            """.trimIndent(),
        )
        val html = AnswerContent.parse(
            "<table><tr><th>Оружие</th><th>Урон</th><th>Слот</th></tr>" +
                "<tr><td>Falling Guillotine</td><td>1200</td><td>тяжёлое</td></tr></table>",
        )

        assertEquals(HudSizing.width(markdown, fontSize), HudSizing.width(html, fontSize))
    }

    @Test
    fun `ширина растёт вместе с кеглем`() {
        // Таблица заведомо шире минимума: иначе обе ширины упрутся в нижнюю границу и сравнение
        // ничего не покажет.
        val blocks = MarkdownReader.parse(
            """
            | Оружие | Урон | Слот | Где лежит |
            | --- | --- | --- | --- |
            | Falling Guillotine | 1200 | тяжёлое | сейф |
            """.trimIndent(),
        )

        assertTrue(HudSizing.width(blocks, 18f) > HudSizing.width(blocks, 11f))
    }
}
