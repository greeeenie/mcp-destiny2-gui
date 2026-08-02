package org.example.overlay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
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
 * [micLevel] — живой уровень микрофона: в «Слушает» им дышит ядро призрака.
 */
@Composable
fun HudStatusIcon(status: OverlayStatus, micLevel: Float = 0f, modifier: Modifier = Modifier) {
    GhostIcon(status, micLevel, modifier)
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

/**
 * Две дуги со стрелками: значок синхронизации перед давностью инвентаря. Контуры из макета
 * (viewBox 12×14); у стрелок есть и заливка, и обводка, поэтому путей два набора.
 */
@Composable
fun SyncIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val scale = minOf(size.width / SYNC_WIDTH, size.height / SYNC_HEIGHT)
        withTransform({
            translate(
                (size.width - SYNC_WIDTH * scale) / 2f,
                (size.height - SYNC_HEIGHT * scale) / 2f,
            )
            scale(scale, scale, Offset.Zero)
        }) {
            SYNC_FILLS.forEach { drawPath(it, color) }
            SYNC_STROKES.forEach { path ->
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = SYNC_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

/**
 * Шеврон листания истории. Рисуется линиями, а не текстовым «‹»: глиф центруется по метрикам
 * шрифта вместе с выносными элементами, поэтому в ряду с иконками сидел заметно ниже их.
 */
@Composable
fun ChevronIcon(color: Color, pointsRight: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = unit()
        val tipX = if (pointsRight) 15f else 9f
        val baseX = if (pointsRight) 9f else 15f
        val path = Path()
        path.moveTo(at(baseX, 6f).x, at(baseX, 6f).y)
        path.lineTo(at(tipX, 12f).x, at(tipX, 12f).y)
        path.lineTo(at(baseX, 18f).x, at(baseX, 18f).y)
        drawPath(path, color, style = line(u))
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

/** Размеры макета значка синхронизации и толщина его обводки. */
private const val SYNC_WIDTH = 12f
private const val SYNC_HEIGHT = 14f
private const val SYNC_STROKE = 1.3f

/** Остриё стрелок: залитые треугольники, они же обведены вместе с дугами. */
private val SYNC_FILLS: List<Path> = listOf(
    "M3.58356 12.4121L2.77087 11.0582L4.23101 10.7798L3.90569 11.6L3.58356 12.4121Z",
    "M7.47483 1.44661L8.38649 2.78923L6.92634 3.06755L7.20859 2.23344L7.47483 1.44661Z",
).map { PathParser().parsePathString(it).toPath() }
/** Дуги со стрелками: нижняя идёт вправо, верхняя — влево. */
private val SYNC_STROKES: List<Path> = listOf(
    "M2.77087 11.0582C3.69153 11.6519 4.78307 11.9235 5.87461 11.8306C6.96616 11.7377 7.99609 " +
        "11.2855 8.80317 10.5447C9.61024 9.80395 10.1489 8.81648 10.3348 7.73687C10.5208 6.65727 " +
        "10.6397 6.70805 10.2505 5.54032M2.77087 11.0582L3.58356 12.4121L4.23101 10.7798L2.77087 " +
        "11.0582Z",
    "M8.38649 2.78923C7.46583 2.19552 6.37429 1.92386 5.28274 2.01676C4.1912 2.10967 3.16127 " +
        "2.5619 2.35419 3.30266C1.54711 4.04342 1.00845 5.03089 0.822514 6.11049C0.636583 " +
        "7.19009 0.520008 7.48627 0.909248 8.65399M8.38649 2.78923L7.47483 1.44661L6.92634 " +
        "3.06755L8.38649 2.78923Z",
).map { PathParser().parsePathString(it).toPath() }
