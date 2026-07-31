package org.example.overlay.ui

import androidx.compose.foundation.Canvas
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
import kotlin.math.roundToInt

/** Общая геометрия консоли: широкая шапка страницы и спокойная рабочая область. */
@Composable
fun ConsolePage(
    title: String,
    mark: ConsolePageMark,
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
            ConsolePageMarkIcon(
                mark = mark,
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
) {
    Row(
        modifier = modifier
            .height(42.dp)
            .background(if (selected) OverlayColors.ControlSelected else OverlayColors.Control)
            .border(1.dp, if (selected) OverlayColors.ControlBorderStrong else OverlayColors.ControlBorder)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(3.dp).height(42.dp)
                .background(if (selected) OverlayColors.Accent else Color.Transparent),
        )
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp),
            color = if (selected) OverlayColors.Text else OverlayColors.TextMuted,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
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
fun HistoryActionIcon(color: Color = OverlayColors.Text) {
    LucideIcon(HISTORY_GLYPH, color)
}

@Composable
fun RefreshActionIcon(color: Color = OverlayColors.Text) {
    LucideIcon(REFRESH_CW_GLYPH, color)
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

@Composable
private fun ConsolePageMarkIcon(mark: ConsolePageMark, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val scale = size.minDimension / PHOSPHOR_VIEWBOX
        val left = (size.width - PHOSPHOR_VIEWBOX * scale) / 2f
        val top = (size.height - PHOSPHOR_VIEWBOX * scale) / 2f
        withTransform({
            translate(left, top)
            scale(scale, scale, Offset.Zero)
        }) {
            drawPath(mark.glyph.secondary, OverlayColors.Text.copy(alpha = 0.48f))
            drawPath(mark.glyph.primary, OverlayColors.Text)
        }
    }
}

/** Крупные двухслойные watermark-эмблемы из Phosphor Duotone, MIT License. */
enum class ConsolePageMark(internal val glyph: PhosphorDuotoneGlyph) {
    Profile(
        PhosphorDuotoneGlyph(
            secondaryPathData = "M224 128a95.76 95.76 0 0 1-31.8 71.37A72 72 0 0 0 128 160a40 40 0 1 0-40-40 40 40 0 0 0 40 40 72 72 0 0 0-64.2 39.37A96 96 0 0 1 184.92 50.69a16 16 0 0 0 20.39 20.39A95.61 95.61 0 0 1 224 128Z",
            primaryPathData = "M228.25 63.07l-4.66-2.69a23.6 23.6 0 0 0 0-8.76l4.66-2.69a8 8 0 0 0-8-13.86l-4.67 2.7A23.92 23.92 0 0 0 208 33.38V28a8 8 0 0 0-16 0v5.38a23.92 23.92 0 0 0-7.58 4.39l-4.67-2.7a8 8 0 1 0-8 13.86l4.66 2.69a23.6 23.6 0 0 0 0 8.76l-4.66 2.69a8 8 0 0 0 4 14.93 7.92 7.92 0 0 0 4-1.07l4.67-2.7A23.92 23.92 0 0 0 192 78.62V84a8 8 0 0 0 16 0V78.62a23.92 23.92 0 0 0 7.58-4.39l4.67 2.7a7.92 7.92 0 0 0 4 1.07 8 8 0 0 0 4-14.93ZM192 56a8 8 0 1 1 8 8 8 8 0 0 1-8-8Zm29.35 48.11a8 8 0 0 0-6.57 9.21A88.85 88.85 0 0 1 216 128a87.62 87.62 0 0 1-22.24 58.41 79.66 79.66 0 0 0-36.06-28.75 48 48 0 1 0-59.4 0 79.66 79.66 0 0 0-36.06 28.75A88 88 0 0 1 128 40a88.76 88.76 0 0 1 14.68 1.22 8 8 0 0 0 2.64-15.78 103.92 103.92 0 1 0 85.24 85.24 8 8 0 0 0-9.21-6.45ZM96 120a32 32 0 1 1 32 32 32 32 0 0 1-32-32ZM74.08 197.5a64 64 0 0 1 107.84 0 87.83 87.83 0 0 1-107.84 0Z",
        ),
    ),
    Assistant(
        PhosphorDuotoneGlyph(
            secondaryPathData = "M240 124a48 48 0 0 1-32 45.27V176a40 40 0 0 1-80 0 40 40 0 0 1-80 0v-6.73a48 48 0 0 1 0-90.54V72a40 40 0 0 1 80 0 40 40 0 0 1 80 0v6.73A48 48 0 0 1 240 124Z",
            primaryPathData = "M248 124a56.11 56.11 0 0 0-32-50.61V72a48 48 0 0 0-88-26.49A48 48 0 0 0 40 72v1.39a56 56 0 0 0 0 101.2V176a48 48 0 0 0 88 26.49A48 48 0 0 0 216 176v-1.41A56.09 56.09 0 0 0 248 124ZM88 208a32 32 0 0 1-31.81-28.56A55.87 55.87 0 0 0 64 180h8a8 8 0 0 0 0-16h-8A40 40 0 0 1 50.67 86.27 8 8 0 0 0 56 78.73V72a32 32 0 0 1 64 0v68.26A47.8 47.8 0 0 0 88 128a8 8 0 0 0 0 16 32 32 0 0 1 0 64Zm104-44h-8a8 8 0 0 0 0 16h8a55.87 55.87 0 0 0 7.81-.56A32 32 0 1 1 168 144a8 8 0 0 0 0-16 47.8 47.8 0 0 0-32 12.26V72a32 32 0 0 1 64 0v6.73a8 8 0 0 0 5.33 7.54A40 40 0 0 1 192 164Zm16-52a8 8 0 0 1-8 8h-4a36 36 0 0 1-36-36v-4a8 8 0 0 1 16 0v4a20 20 0 0 0 20 20h4a8 8 0 0 1 8 8ZM60 120h-4a8 8 0 0 1 0-16h4a20 20 0 0 0 20-20v-4a8 8 0 0 1 16 0v4a36 36 0 0 1-36 36Z",
        ),
    ),
    Voice(
        PhosphorDuotoneGlyph(
            secondaryPathData = "M156.5 151 59 222.45a8 8 0 0 1-10.38-.79l-14.3-14.3a8 8 0 0 1-.77-10.36L105 99.5A64 64 0 0 0 156.48 151Z",
            primaryPathData = "M168 16a72.07 72.07 0 0 0-72 72 73.29 73.29 0 0 0 .63 9.42l-69.51 94.8A15.93 15.93 0 0 0 28.71 213L43 227.29a15.93 15.93 0 0 0 20.78 1.59l94.81-69.53A73.29 73.29 0 0 0 168 160a72 72 0 1 0 0-144Zm56 72a55.72 55.72 0 0 1-11.16 33.52l-78.35-78.36A56 56 0 0 1 224 88ZM54.32 216 40 201.68 102.14 117A72.37 72.37 0 0 0 139 153.86ZM112 88a55.67 55.67 0 0 1 11.16-33.51l78.34 78.34A56 56 0 0 1 112 88Zm-2.35 58.34a8 8 0 0 1 0 11.31l-8 8a8 8 0 1 1-11.31-11.31l8-8a8 8 0 0 1 11.33 0Z",
        ),
    ),
    Audio(
        PhosphorDuotoneGlyph(
            secondaryPathData = "M80 144v40a16 16 0 0 1-16 16H48a16 16 0 0 1-16-16v-56h32a16 16 0 0 1 16 16Zm112-16a16 16 0 0 0-16 16v40a16 16 0 0 0 16 16h16a16 16 0 0 0 16-16v-56Z",
            primaryPathData = "M201.89 54.66A104.08 104.08 0 0 0 24 128v56a24 24 0 0 0 24 24h16a24 24 0 0 0 24-24v-40a24 24 0 0 0-24-24H40.36A88 88 0 0 1 128 40h.67a87.71 87.71 0 0 1 87 80H192a24 24 0 0 0-24 24v40a24 24 0 0 0 24 24h16a24 24 0 0 0 24-24v-56a103.41 103.41 0 0 0-30.11-73.34ZM64 136a8 8 0 0 1 8 8v40a8 8 0 0 1-8 8H48a8 8 0 0 1-8-8v-48Zm152 48a8 8 0 0 1-8 8h-16a8 8 0 0 1-8-8v-40a8 8 0 0 1 8-8h24Z",
        ),
    ),
    Hud(
        PhosphorDuotoneGlyph(
            secondaryPathData = "M224 64v112a16 16 0 0 1-16 16H48a16 16 0 0 1-16-16V64a16 16 0 0 1 16-16h160a16 16 0 0 1 16 16Z",
            primaryPathData = "M208 40H48a24 24 0 0 0-24 24v112a24 24 0 0 0 24 24h160a24 24 0 0 0 24-24V64a24 24 0 0 0-24-24Zm8 136a8 8 0 0 1-8 8H48a8 8 0 0 1-8-8V64a8 8 0 0 1 8-8h160a8 8 0 0 1 8 8Zm-48 48a8 8 0 0 1-8 8H96a8 8 0 0 1 0-16h64a8 8 0 0 1 8 8Z",
        ),
    ),
}

internal class PhosphorDuotoneGlyph(secondaryPathData: String, primaryPathData: String) {
    val secondary: Path = PathParser().parsePathString(secondaryPathData).toPath()
    val primary: Path = PathParser().parsePathString(primaryPathData).toPath()
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

private val REFRESH_CW_GLYPH = LucideGlyph(
    pathData = listOf(
        "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8",
        "M21 3v5h-5",
        "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16",
        "M8 16H3v5",
    ),
)

private const val LUCIDE_VIEWBOX = 24f
private const val PHOSPHOR_VIEWBOX = 256f

@Composable
fun SpeakerActionIcon(color: Color = OverlayColors.Text) {
    Canvas(Modifier.fillMaxSize()) {
        val u = size.minDimension / 24f
        val stroke = Stroke(1.8f * u, cap = StrokeCap.Round)
        val speaker = Path().apply {
            moveTo(3f * u, 9f * u)
            lineTo(7f * u, 9f * u)
            lineTo(12f * u, 5f * u)
            lineTo(12f * u, 19f * u)
            lineTo(7f * u, 15f * u)
            lineTo(3f * u, 15f * u)
            close()
        }
        drawPath(speaker, color, style = stroke)
        drawArc(
            color,
            startAngle = -55f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(11f * u, 7f * u),
            size = androidx.compose.ui.geometry.Size(7f * u, 10f * u),
            style = stroke,
        )
        drawArc(
            color,
            startAngle = -52f,
            sweepAngle = 104f,
            useCenter = false,
            topLeft = Offset(11f * u, 4f * u),
            size = androidx.compose.ui.geometry.Size(12f * u, 16f * u),
            style = stroke,
        )
    }
}
