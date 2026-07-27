package org.example.overlay.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarkdownReaderTest {

    private fun single(source: String): MdBlock = MarkdownReader.parse(source).single()

    @Test
    fun `пустой текст даёт пустой документ`() {
        assertEquals(emptyList(), MarkdownReader.parse("   \n  "))
    }

    @Test
    fun `обычный текст становится абзацем`() {
        val block = assertIs<MdBlock.Paragraph>(single("Falling Guillotine лежит в сейфе."))

        assertEquals("Falling Guillotine лежит в сейфе.", block.text.plainText)
    }

    @Test
    fun `заголовки разбираются по уровням`() {
        val blocks = MarkdownReader.parse("# Оружие\n\n### Мечи")

        assertEquals(listOf(1 to "Оружие", 3 to "Мечи"), blocks.map { assertIs<MdBlock.Heading>(it).let { h -> h.level to h.text.plainText } })
    }

    @Test
    fun `жирный курсив код и зачёркнутый превращаются в начертания`() {
        val block = assertIs<MdBlock.Paragraph>(single("**жирный** и *курсив*, `код`, ~~вычеркнуто~~"))
        val spans = block.text.spans

        assertEquals("жирный и курсив, код, вычеркнуто", block.text.plainText)
        assertTrue(spans.single { it.text == "жирный" }.bold)
        assertTrue(spans.single { it.text == "курсив" }.italic)
        assertTrue(spans.single { it.text == "код" }.code)
        assertTrue(spans.single { it.text == "вычеркнуто" }.strikethrough)
    }

    @Test
    fun `ссылка сохраняет адрес и показывает подпись`() {
        val block = assertIs<MdBlock.Paragraph>(single("см. [профиль](https://bungie.net/profile)"))

        val link = block.text.spans.single { it.link != null }
        assertEquals("профиль", link.text)
        assertEquals("https://bungie.net/profile", link.link)
    }

    @Test
    fun `маркированный список отдаёт пункты`() {
        val list = assertIs<MdBlock.MdList>(single("- Меч\n- Пулемёт\n- Гранатомёт"))

        assertTrue(!list.ordered)
        assertEquals(
            listOf("Меч", "Пулемёт", "Гранатомёт"),
            list.items.map { assertIs<MdBlock.Paragraph>(it.single()).text.plainText },
        )
    }

    @Test
    fun `нумерованный список помнит, с какого номера начинается`() {
        val list = assertIs<MdBlock.MdList>(single("3. третий\n4. четвёртый"))

        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
    }

    @Test
    fun `вложенный список остаётся внутри пункта`() {
        val list = assertIs<MdBlock.MdList>(single("- Мечи\n    - Falling Guillotine\n    - The Other Half"))

        val nested = list.items.first().filterIsInstance<MdBlock.MdList>().single()
        assertEquals(2, nested.items.size)
    }

    @Test
    fun `цитата сохраняет вложенные блоки`() {
        val quote = assertIs<MdBlock.Quote>(single("> Внимание\n> вторая строка"))

        assertEquals("Внимание вторая строка", assertIs<MdBlock.Paragraph>(quote.blocks.single()).text.plainText)
    }

    @Test
    fun `огороженный код сохраняет язык и переводы строк`() {
        val code = assertIs<MdBlock.CodeBlock>(single("```kotlin\nval a = 1\nval b = 2\n```"))

        assertEquals("kotlin", code.language)
        assertEquals("val a = 1\nval b = 2", code.code)
    }

    @Test
    fun `таблица разбирается с шапкой строками и выравниванием`() {
        val table = assertIs<MdBlock.Table>(
            single(
                """
                | Оружие | Урон | Слот |
                | :--- | ---: | :---: |
                | Falling Guillotine | 1200 | тяжёлое |
                | Sunshot | 750 | энергия |
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("Оружие", "Урон", "Слот"), table.header.map { it.plainText })
        assertEquals(listOf(MdAlign.LEFT, MdAlign.RIGHT, MdAlign.CENTER), table.alignments)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Falling Guillotine", "1200", "тяжёлое"), table.rows.first().map { it.plainText })
    }

    @Test
    fun `начертания внутри ячеек сохраняются`() {
        val table = assertIs<MdBlock.Table>(
            single("| Оружие |\n| --- |\n| **Falling Guillotine** |"),
        )

        assertTrue(table.rows.single().single().spans.single().bold)
    }

    @Test
    fun `горизонтальная черта распознаётся`() {
        val blocks = MarkdownReader.parse("сверху\n\n---\n\nснизу")

        assertTrue(blocks.any { it is MdBlock.Rule }, "черты нет среди $blocks")
    }

    @Test
    fun `смешанный документ сохраняет порядок блоков`() {
        val blocks = MarkdownReader.parse(
            """
            # Итог

            Нашёл **два** меча.

            | Имя | Урон |
            | --- | --- |
            | Falling Guillotine | 1200 |

            - переложить на титана
            - оставить в сейфе
            """.trimIndent(),
        )

        assertEquals(
            listOf(MdBlock.Heading::class, MdBlock.Paragraph::class, MdBlock.Table::class, MdBlock.MdList::class),
            blocks.map { it::class },
        )
    }

    @Test
    fun `картинка становится отрезком с URL и alt`() {
        val block = assertIs<MdBlock.Paragraph>(
            single("![Фугасный снаряд](/common/destiny2_content/icons/abc.png)"),
        )
        val span = block.text.spans.single()

        assertEquals("https://www.bungie.net/common/destiny2_content/icons/abc.png", span.image)
        assertEquals("Фугасный снаряд", span.text)
    }

    @Test
    fun `картинка в ячейке таблицы разбирается`() {
        val table = assertIs<MdBlock.Table>(
            single(
                """
                | Перк |
                | --- |
                | ![Слайдшот](https://www.bungie.net/i.png) |
                """.trimIndent(),
            ),
        )
        val cell = table.rows.single().single()

        assertEquals("https://www.bungie.net/i.png", cell.spans.single().image)
    }
}
