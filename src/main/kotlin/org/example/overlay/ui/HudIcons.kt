package org.example.overlay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import org.example.overlay.app.OverlayStatus
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Иконки HUD, нарисованные вручную.
 *
 * Готовый набор Material сюда не тянем: он добавил бы зависимость ради четырёх значков, а
 * рисовать их всё равно пришлось бы под свою палитру и толщину линии. Все иконки живут в сетке
 * 24×24 и масштабируются размером [Modifier], поэтому одинаково смотрятся и в 16, и в 24 dp.
 */
private const val GRID = 24f

/** Толщина линии в единицах сетки: тоньше — рябит на полупрозрачном фоне поверх игры. */
private const val STROKE_UNITS = 1.8f

/**
 * Что происходит с голосом — одним живым призраком [GhostIcon]. Текста в шапке нет: подпись
 * игрок читать не успевает, а цвет и темп движения ловит боковым зрением (§5.3).
 */
@Composable
fun HudStatusIcon(status: OverlayStatus, modifier: Modifier = Modifier) {
    GhostIcon(status, modifier)
}

/**
 * Обратный отсчёт до сворачивания: дуга на бледном кольце тает по часовой стрелке.
 * [fraction] — сколько времени осталось, от 1 (полное кольцо) до 0.
 */
@Composable
fun CountdownIcon(color: Color, fraction: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = unit()
        val topLeft = at(3f, 3f)
        val box = Size(18f * u, 18f * u)
        drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, topLeft, box, style = line(u))
        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, topLeft, box, style = line(u))
    }
}

/** Шестерёнка: открывает консоль. Контур, а не заливка — дырку в прозрачном окне не вырезать. */
@Composable
fun GearIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = unit()
        val centre = at(12f, 12f)
        val path = Path()
        // Зубцы — вершины через одну на большем и меньшем радиусе.
        repeat(GEAR_TEETH * 2) { index ->
            val radius = if (index % 2 == 0) 10.5f * u else 7.6f * u
            val angle = (index * PI / GEAR_TEETH).toFloat()
            val point = Offset(centre.x + radius * cos(angle), centre.y + radius * sin(angle))
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        path.close()

        drawPath(path, color, style = line(u))
        drawCircle(color, radius = 3.4f * u, center = centre, style = line(u))
    }
}

/** Сколько пикселей в одной единице сетки 24×24 при текущем размере холста. */
private fun DrawScope.unit(): Float = size.minDimension / GRID

/** Точка сетки в координатах холста: сетка центрируется, если холст не квадратный. */
private fun DrawScope.at(x: Float, y: Float): Offset {
    val u = unit()
    return Offset(
        x = (size.width - GRID * u) / 2f + x * u,
        y = (size.height - GRID * u) / 2f + y * u,
    )
}

private fun line(unit: Float) =
    Stroke(width = STROKE_UNITS * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)

private const val GEAR_TEETH = 8
