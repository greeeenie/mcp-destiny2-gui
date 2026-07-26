package org.example.overlay.ui

import org.example.overlay.markdown.MarkdownReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HudHeightTest {

    private val fontSize = 13f

    private fun size(markdown: String, extras: HudSizing.Extras = HudSizing.Extras()): Pair<Float, Float> {
        val blocks = MarkdownReader.parse(markdown)
        val width = HudSizing.width(blocks, fontSize)
        return width to HudSizing.height(blocks, fontSize, width, extras)
    }

    @Test
    fun `без ответа окно остаётся полоской`() {
        val height = HudSizing.height(emptyList(), fontSize, HudSizing.MIN_WIDTH)

        assertTrue(height <= 160f, "пустой HUD не должен занимать $height")
    }

    @Test
    fun `таблица делает окно выше короткой фразы`() {
        val (_, short) = size("Переложил на титана.")
        val (_, table) = size(
            """
            | Оружие | Урон | Слот |
            | --- | --- | --- |
            | Falling Guillotine | 1200 | тяжёлое |
            | The Other Half | 1150 | тяжёлое |
            | Sunshot | 750 | энергия |
            """.trimIndent(),
        )

        assertTrue(table > short + 60f, "таблица $table должна быть заметно выше фразы $short")
    }

    @Test
    fun `длинная проза растёт в основном в высоту`() {
        val long = "Нашёл сразу несколько подходящих мечей и пулемётов в сейфе. ".repeat(8)
        val (width, height) = size(long)
        val (_, shortHeight) = size("Нашёл один меч.")
        val (tableWidth, _) = size(
            """
            | Оружие | Урон | Слот | Где лежит |
            | --- | --- | --- | --- |
            | Осквернённая гильотина | 1200 | тяжёлое | сейф второго титана |
            """.trimIndent(),
        )

        // Проза упирается в комфортную длину строки и дальше растёт вниз: строка в 52 символа
        // читается лучше, чем узкая колонка на весь экран в высоту.
        assertTrue(width < tableWidth, "проза $width не должна требовать столько же, сколько таблица $tableWidth")
        assertTrue(height > shortHeight * 2, "длинная проза должна занимать заметно больше строк")
    }

    @Test
    fun `высота не превышает потолок`() {
        val (_, height) = size("- пункт списка\n".repeat(80))

        assertEquals(HudSizing.MAX_HEIGHT, height)
    }

    @Test
    fun `постоянные части HUD добавляют высоту`() {
        val markdown = "Готово."
        val (_, bare) = size(markdown)
        val (_, withExtras) = size(
            markdown,
            HudSizing.Extras(userTranscript = true, toolLines = 3, authBanner = true, message = true),
        )

        assertTrue(withExtras > bare, "плашка авторизации и лог инструментов должны добавлять места")
    }

    @Test
    fun `блок кода занимает по строке на строку`() {
        val (_, oneLine) = size("```\nval a = 1\n```")
        val (_, fiveLines) = size("```\nval a = 1\nval b = 2\nval c = 3\nval d = 4\nval e = 5\n```")

        assertTrue(fiveLines > oneLine, "пять строк кода должны быть выше одной")
    }
}
