package org.example.overlay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import kotlin.math.roundToInt

/** Общая геометрия консоли: широкая шапка страницы и спокойная рабочая область. */
@Composable
fun ConsolePage(
    title: String,
    markAsset: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(142.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(OverlayColors.Header, OverlayColors.HeaderEnd),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 36.dp),
            ) {
                Text(
                    "НАСТРОЙКИ",
                    color = OverlayColors.TextDim,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp,
                )
                Spacer(Modifier.height(7.dp))
                Text(title, color = OverlayColors.Text, fontSize = 34.sp, fontWeight = FontWeight.Light)
            }
            Image(
                painter = painterResource(markAsset),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 52.dp)
                    .size(112.dp)
                    .alpha(0.12f),
            )
        }
        HorizontalDivider(color = OverlayColors.Divider)
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 26.dp),
            content = content,
        )
    }
}

@Composable
fun ConsoleSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier) {
        Text(
            title.uppercase(),
            color = OverlayColors.TextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
        Spacer(Modifier.height(18.dp))
        content()
    }
}

@Composable
fun ConsoleHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        color = OverlayColors.TextFaint,
        fontSize = 11.sp,
        fontStyle = FontStyle.Italic,
        lineHeight = 16.sp,
    )
}

/** Плоская кнопка для редких служебных действий. */
@Composable
fun ConsoleUtilityButton(
    text: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .height(46.dp)
            .background(if (emphasized) OverlayColors.ControlSelected else OverlayColors.Control)
            .border(1.dp, OverlayColors.ControlBorder, RectangleShape)
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (emphasized) {
            Box(Modifier.width(3.dp).height(46.dp).background(OverlayColors.Accent))
        }
        Spacer(Modifier.width(if (emphasized) 16.dp else 19.dp))
        Box(Modifier.size(21.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(14.dp))
        Text(
            text,
            color = if (enabled) OverlayColors.Text else OverlayColors.TextFaint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(20.dp))
    }
}

/** Выбор одного варианта: выбранное состояние обозначает узкая фирменная полоса. */
@Composable
fun ConsoleChoice(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    Row(
        modifier = modifier
            .height(if (supportingText == null) 42.dp else 56.dp)
            .background(if (selected) OverlayColors.ControlSelected else OverlayColors.Control)
            .border(1.dp, if (selected) OverlayColors.ControlBorderStrong else OverlayColors.ControlBorder)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(3.dp).fillMaxHeight()
                .background(if (selected) OverlayColors.Accent else Color.Transparent),
        )
        Column(Modifier.padding(horizontal = 14.dp)) {
            Text(
                text,
                color = if (selected) OverlayColors.Text else OverlayColors.TextMuted,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
            supportingText?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = OverlayColors.TextDim, fontSize = 10.sp)
            }
        }
    }
}

/**
 * Слайдер консоли без Material-геометрии: тонкая шкала, риски и ромбовидный маркер.
 * [divisions] задаёт число равных интервалов и включает привязку к ним; null оставляет
 * значение непрерывным, а риски остаются только визуальной координатной сеткой.
 */
@Composable
fun ConsoleSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    divisions: Int? = null,
    tickCount: Int = divisions ?: 10,
) {
    val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
        .coerceIn(0f, 1f)

    Canvas(
        modifier
            .height(30.dp)
            .pointerInput(valueRange, divisions) {
                fun updateValue(x: Float) {
                    val rawFraction = (x / size.width.toFloat()).coerceIn(0f, 1f)
                    val snappedFraction = divisions?.takeIf { it > 0 }?.let { count ->
                        (rawFraction * count).roundToInt() / count.toFloat()
                    } ?: rawFraction
                    onValueChange(
                        valueRange.start +
                            (valueRange.endInclusive - valueRange.start) * snappedFraction,
                    )
                }

                awaitEachGesture {
                    var change = awaitFirstDown()
                    updateValue(change.position.x)
                    while (change.pressed) {
                        change = awaitPointerEvent().changes.first()
                        if (change.pressed) {
                            updateValue(change.position.x)
                            change.consume()
                        }
                    }
                }
            },
    ) {
        val trackStart = 7.dp.toPx()
        val trackEnd = size.width - trackStart
        val trackWidth = trackEnd - trackStart
        val centerY = size.height / 2f
        val trackHeight = 5.dp.toPx()
        val activeEnd = trackStart + trackWidth * fraction

        drawRect(
            color = OverlayColors.Control,
            topLeft = Offset(trackStart, centerY - trackHeight / 2f),
            size = androidx.compose.ui.geometry.Size(trackWidth, trackHeight),
        )
        drawRect(
            color = OverlayColors.Accent,
            topLeft = Offset(trackStart, centerY - trackHeight / 2f),
            size = androidx.compose.ui.geometry.Size((activeEnd - trackStart).coerceAtLeast(0f), trackHeight),
        )

        if (tickCount > 1) {
            repeat(tickCount + 1) { index ->
                val x = trackStart + trackWidth * index / tickCount.toFloat()
                drawLine(
                    color = if (x <= activeEnd) OverlayColors.Header else OverlayColors.TextFaint,
                    start = Offset(x, centerY - 2.dp.toPx()),
                    end = Offset(x, centerY + 2.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }

        val thumbRadius = 7.dp.toPx()
        val thumb = Path().apply {
            moveTo(activeEnd, centerY - thumbRadius)
            lineTo(activeEnd + thumbRadius, centerY)
            lineTo(activeEnd, centerY + thumbRadius)
            lineTo(activeEnd - thumbRadius, centerY)
            close()
        }
        drawPath(thumb, OverlayColors.Header)
        drawPath(thumb, OverlayColors.Accent, style = Stroke(width = 1.5.dp.toPx()))
        drawCircle(OverlayColors.Accent, radius = 1.5.dp.toPx(), center = Offset(activeEnd, centerY))
    }
}

@Composable
fun SectionColumns(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        content = content,
    )
}

@Composable
fun HistoryActionIcon(color: Color = OverlayColors.Text, modifier: Modifier = Modifier.fillMaxSize()) {
    LucideIcon(HISTORY_GLYPH, color, modifier)
}

@Composable
private fun LucideIcon(glyph: LucideGlyph, color: Color, modifier: Modifier = Modifier.fillMaxSize()) {
    Canvas(modifier) {
        val scale = size.minDimension / LUCIDE_VIEWBOX
        val left = (size.width - LUCIDE_VIEWBOX * scale) / 2f
        val top = (size.height - LUCIDE_VIEWBOX * scale) / 2f
        withTransform({
            translate(left, top)
            scale(scale, scale, Offset.Zero)
        }) {
            val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            glyph.paths.forEach { drawPath(it, color, style = stroke) }
        }
    }
}

internal class LucideGlyph(
    pathData: List<String>,
) {
    val paths: List<Path> = pathData.map { PathParser().parsePathString(it).toPath() }
}

private val HISTORY_GLYPH = LucideGlyph(
    pathData = listOf(
        "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",
        "M3 3v5h5",
        "M12 7v5l4 2",
    ),
)

private const val LUCIDE_VIEWBOX = 24f
