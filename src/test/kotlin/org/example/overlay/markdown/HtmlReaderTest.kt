package org.example.overlay.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HtmlReaderTest {

    private fun single(html: String): MdBlock = HtmlReader.parse(html).single()

    @Test
    fun `пустая разметка даёт пустой документ`() {
        assertEquals(emptyList(), HtmlReader.parse("   "))
    }

    @Test
    fun `абзац становится абзацем`() {
        val block = assertIs<MdBlock.Paragraph>(single("<p>Falling Guillotine лежит в сейфе.</p>"))

        assertEquals("Falling Guillotine лежит в сейфе.", block.text.plainText)
    }

    @Test
    fun `текст без тегов не теряется`() {
        val block = assertIs<MdBlock.Paragraph>(single("Просто ответ без разметки"))

        assertEquals("Просто ответ без разметки", block.text.plainText)
    }

    @Test
    fun `заголовки разбираются по уровням`() {
        val blocks = HtmlReader.parse("<h1>Оружие</h1><h3>Мечи</h3>")

        assertEquals(
            listOf(1 to "Оружие", 3 to "Мечи"),
            blocks.map { assertIs<MdBlock.Heading>(it).let { h -> h.level to h.text.plainText } },
        )
    }

    @Test
    fun `начертания и ссылки переносятся в отрезки`() {
        val block = assertIs<MdBlock.Paragraph>(
            single("""<p><b>жирный</b> и <em>курсив</em>, <code>код</code>, <s>вычеркнуто</s>, <a href="https://bungie.net">ссылка</a></p>"""),
        )
        val spans = block.text.spans

        assertTrue(spans.single { it.text == "жирный" }.bold)
        assertTrue(spans.single { it.text == "курсив" }.italic)
        assertTrue(spans.single { it.text == "код" }.code)
        assertTrue(spans.single { it.text == "вычеркнуто" }.strikethrough)
        assertEquals("https://bungie.net", spans.single { it.text == "ссылка" }.link)
    }

    @Test
    fun `HTML-сущности превращаются в символы`() {
        val block = assertIs<MdBlock.Paragraph>(single("<p>1 &lt; 2 &amp;&nbsp;3 &gt; 0</p>"))

        assertTrue(block.text.plainText.startsWith("1 < 2 &"), "получилось '${block.text.plainText}'")
    }

    @Test
    fun `таблица разбирается с шапкой строками и выравниванием`() {
        val table = assertIs<MdBlock.Table>(
            single(
                """
                <table>
                  <thead>
                    <tr><th>Оружие</th><th align="right">Урон</th><th align="center">Слот</th></tr>
                  </thead>
                  <tbody>
                    <tr><td>Falling Guillotine</td><td>1200</td><td>тяжёлое</td></tr>
                    <tr><td>Sunshot</td><td>750</td><td>энергия</td></tr>
                  </tbody>
                </table>
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("Оружие", "Урон", "Слот"), table.header.map { it.plainText })
        assertEquals(listOf(MdAlign.LEFT, MdAlign.RIGHT, MdAlign.CENTER), table.alignments)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Sunshot", "750", "энергия"), table.rows.last().map { it.plainText })
    }

    @Test
    fun `выравнивание читается и из inline-стиля`() {
        val table = assertIs<MdBlock.Table>(
            single("""<table><tr><th style="text-align: right">Урон</th></tr><tr><td>1200</td></tr></table>"""),
        )

        assertEquals(listOf(MdAlign.RIGHT), table.alignments)
    }

    @Test
    fun `таблица без шапки не теряет строки`() {
        val table = assertIs<MdBlock.Table>(single("<table><tr><td>Меч</td><td>1200</td></tr></table>"))

        assertTrue(table.header.isEmpty())
        assertEquals(listOf("Меч", "1200"), table.rows.single().map { it.plainText })
    }

    @Test
    fun `начертания внутри ячеек сохраняются`() {
        val table = assertIs<MdBlock.Table>(
            single("<table><tr><th>Оружие</th></tr><tr><td><b>Falling Guillotine</b></td></tr></table>"),
        )

        assertTrue(table.rows.single().single().spans.single().bold)
    }

    @Test
    fun `списки разбираются с номером начала`() {
        val bullets = assertIs<MdBlock.MdList>(single("<ul><li>Меч</li><li>Пулемёт</li></ul>"))
        val ordered = assertIs<MdBlock.MdList>(single("""<ol start="3"><li>третий</li></ol>"""))

        assertTrue(!bullets.ordered)
        assertEquals(2, bullets.items.size)
        assertTrue(ordered.ordered)
        assertEquals(3, ordered.start)
    }

    @Test
    fun `вложенный список остаётся внутри пункта`() {
        val list = assertIs<MdBlock.MdList>(single("<ul><li>Мечи<ul><li>Falling Guillotine</li></ul></li></ul>"))

        assertEquals(1, list.items.first().filterIsInstance<MdBlock.MdList>().single().items.size)
    }

    @Test
    fun `блок кода сохраняет язык и переводы строк`() {
        val code = assertIs<MdBlock.CodeBlock>(
            single("""<pre><code class="language-kotlin">val a = 1
val b = 2</code></pre>"""),
        )

        assertEquals("kotlin", code.language)
        assertEquals("val a = 1\nval b = 2", code.code)
    }

    @Test
    fun `цитата и черта распознаются`() {
        assertIs<MdBlock.Quote>(single("<blockquote><p>Внимание</p></blockquote>"))
        assertEquals(MdBlock.Rule, single("<hr>"))
    }

    @Test
    fun `обёртки разворачиваются, а скрипты выбрасываются`() {
        val blocks = HtmlReader.parse(
            "<div><script>alert(1)</script><p>Первый</p><section><p>Второй</p></section></div>",
        )

        assertEquals(listOf("Первый", "Второй"), blocks.map { assertIs<MdBlock.Paragraph>(it).text.plainText })
    }

    @Test
    fun `незакрытые теги не роняют разбор - в потоке дельт это норма`() {
        val blocks = HtmlReader.parse("<table><tr><td>Меч</td><td>1200</td>")

        assertIs<MdBlock.Table>(blocks.single())
    }
}
