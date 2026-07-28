package org.example.overlay.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
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
 * Что происходит с голосом, одной иконкой. Текста в шапке нет: подпись игрок читать не успевает,
 * а форму и цвет ловит боковым зрением (§5.3).
 */
@Composable
fun HudStatusIcon(status: OverlayStatus, modifier: Modifier = Modifier) {
    when (status) {
        // Микрофон поднят, но клавиша не зажата.
        OverlayStatus.Ready -> MicIcon(OverlayColors.TextDim, muted = false, modifier = modifier)
        OverlayStatus.Listening -> MicIcon(OverlayColors.Ok, muted = false, modifier = modifier)

        // Линия не поднялась: микрофон перечёркнут, причина — строкой ошибки под шапкой.
        OverlayStatus.Disconnected -> MicIcon(OverlayColors.TextDim, muted = true, modifier = modifier)
        is OverlayStatus.Failed -> MicIcon(OverlayColors.Error, muted = true, modifier = modifier)

        // Речь ушла на расшифровку — ждём текст.
        OverlayStatus.Thinking -> SpinnerIcon(OverlayColors.Accent, modifier = modifier)
        OverlayStatus.Connecting -> SpinnerIcon(OverlayColors.Warn, modifier = modifier)
        is OverlayStatus.Reconnecting -> SpinnerIcon(OverlayColors.Warn, modifier = modifier)

        // Модель пишет ответ.
        OverlayStatus.Answering -> DotsIcon(OverlayColors.Accent, modifier = modifier)
        OverlayStatus.Speaking -> DotsIcon(OverlayColors.Accent, modifier = modifier)
    }
}

/** Микрофон: капсула, дуга держателя и ножка. [muted] перечёркивает его по диагонали. */
@Composable
fun MicIcon(color: Color, muted: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = unit()
        drawRoundRect(
            color = color,
            topLeft = at(9f, 3f),
            size = Size(6f * u, 11f * u),
            cornerRadius = CornerRadius(3f * u, 3f * u),
        )
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = at(6f, 9f),
            size = Size(12f * u, 10f * u),
            style = line(u),
        )
        drawLine(color, at(12f, 19f), at(12f, 21.5f), strokeWidth = STROKE_UNITS * u, cap = StrokeCap.Round)

        if (muted) {
            drawLine(color, at(3.5f, 3f), at(20.5f, 21f), strokeWidth = STROKE_UNITS * u, cap = StrokeCap.Round)
        }
    }
}

/** Загрузка: дуга, бегущая по бледному кольцу. */
@Composable
fun SpinnerIcon(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "spinner-angle",
    )

    Canvas(modifier) {
        val u = unit()
        val topLeft = at(3f, 3f)
        val box = Size(18f * u, 18f * u)
        drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, topLeft, box, style = line(u))
        drawArc(color, angle, SPINNER_SWEEP, false, topLeft, box, style = line(u))
    }
}

/** Ответ пишется: три точки, перекатывающиеся волной. */
@Composable
fun DotsIcon(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 1_100, easing = LinearEasing)),
        label = "dots-phase",
    )

    Canvas(modifier) {
        val u = unit()
        repeat(3) { index ->
            val wave = (sin(phase - index * DOT_PHASE_SHIFT) + 1f) / 2f
            drawCircle(
                color = color.copy(alpha = DOT_MIN_ALPHA + (1f - DOT_MIN_ALPHA) * wave),
                radius = 2.4f * u,
                center = at(5f + index * 7f, 12f),
            )
        }
    }
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

private const val SPINNER_SWEEP = 90f
private const val GEAR_TEETH = 8
private const val DOT_MIN_ALPHA = 0.3f
private const val DOT_PHASE_SHIFT = 0.9f
