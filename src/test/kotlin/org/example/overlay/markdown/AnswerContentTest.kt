package org.example.overlay.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnswerContentTest {

    @Test
    fun `разметка Markdown разбирается как Markdown`() {
        val blocks = AnswerContent.parse("**Нашёл** два меча")

        assertTrue(assertIs<MdBlock.Paragraph>(blocks.single()).text.spans.first().bold)
    }

    @Test
    fun `ответ тегами разбирается как HTML`() {
        val blocks = AnswerContent.parse("<table><tr><th>Оружие</th></tr><tr><td>Меч</td></tr></table>")

        assertIs<MdBlock.Table>(blocks.single())
    }

    @Test
    fun `таблица HTML внутри Markdown больше не теряется`() {
        // Модель нередко пишет прозу как Markdown, а таблицу — тегами.
        val blocks = AnswerContent.parse(
            """
            Нашёл два меча.

            <table>
              <tr><th>Оружие</th><th>Урон</th></tr>
              <tr><td>Falling Guillotine</td><td>1200</td></tr>
            </table>
            """.trimIndent(),
        )

        assertEquals(
            listOf(MdBlock.Paragraph::class, MdBlock.Table::class),
            blocks.map { it::class },
        )
    }

    @Test
    fun `одиночный тег посреди фразы не показывается угловыми скобками`() {
        val blocks = AnswerContent.parse("Меч лежит <br> в сейфе")

        assertFalse("<br>" in assertIs<MdBlock.Paragraph>(blocks.single()).text.plainText)
    }

    @Test
    fun `упоминание тега в тексте не делает ответ версткой`() {
        assertFalse(AnswerContent.looksLikeHtml("Сравнение оружия по урону и слоту"))
        assertFalse(AnswerContent.looksLikeHtml("| Оружие | Урон |\n| --- | --- |\n| Меч | 1200 |"))
    }

    @Test
    fun `пустой ответ не роняет разбор`() {
        assertEquals(emptyList(), AnswerContent.parse("  "))
    }
}
