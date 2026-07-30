package org.example.overlay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import org.example.overlay.app.OverlayStatus
import kotlin.math.PI
import kotlin.math.sin

/**
 * Живой призрак вместо набора статусных значков: четыре сегмента оболочки (контуры — из
 * фирменного логотипа) крутятся вокруг ядра, как у призрака в игре. Статус читается по
 * поведению, а не по форме: цвет, скорость вращения и дыхание ядра.
 *
 * Угол и фаза пульса живут в [remember] и накапливаются по кадрам, а не через
 * `infiniteRepeatable`: при смене статуса меняется только скорость, и оболочка продолжает
 * вращение с того же места — без рывка. У «мёртвых» статусов скорость нулевая, призрак
 * просто замирает как есть.
 */
@Composable
fun GhostIcon(status: OverlayStatus, modifier: Modifier = Modifier) {
    val spec = ghostSpec(status)

    var rotation by remember { mutableFloatStateOf(0f) }
    var pulsePhase by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(spec.spinDegPerSec, spec.pulseHz) {
        if (spec.spinDegPerSec == 0f && spec.pulseHz == 0f) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = (now - last) / 1_000_000_000f
                last = now
                rotation = (rotation + spec.spinDegPerSec * dt) % 360f
                pulsePhase = (pulsePhase + spec.pulseHz * dt) % 1f
            }
        }
    }

    Canvas(modifier) {
        val s = size.minDimension / VIEWBOX
        // 0..1: и дыхание ядра, и «выдох» сегментов сидят на одной фазе, чтобы двигаться в такт.
        val wave = (sin(pulsePhase * 2f * PI.toFloat()) + 1f) / 2f
        val spread = spec.spreadUnits * wave

        withTransform({
            translate((size.width - VIEWBOX * s) / 2f, (size.height - VIEWBOX * s) / 2f)
            scale(s, s, Offset.Zero)
        }) {
            rotate(rotation, pivot = Offset(CENTRE, CENTRE)) {
                for ((path, direction) in shellSegments) {
                    translate(direction.x * spread, direction.y * spread) {
                        drawPath(path, spec.shell)
                    }
                }
            }

            if (spec.coreHollow) {
                // Погасшее ядро: пустое кольцо — призрак «выключен».
                drawCircle(
                    color = spec.core,
                    radius = CORE_RADIUS,
                    center = Offset(CENTRE, CENTRE),
                    style = Stroke(width = HOLLOW_STROKE),
                )
            } else {
                val radius = CORE_RADIUS + CORE_PULSE * spec.corePulse * (wave * 2f - 1f)
                drawCircle(color = spec.core, radius = radius, center = Offset(CENTRE, CENTRE))
            }
        }
    }
}

/** Поведение призрака в одном статусе: цвета, скорость оболочки и амплитуды дыхания. */
private data class GhostSpec(
    val shell: Color,
    val core: Color,
    /** Скорость вращения оболочки, градусов в секунду. 0 — призрак замер. */
    val spinDegPerSec: Float,
    /** Частота дыхания (ядро и разлёт сегментов), герц. */
    val pulseHz: Float,
    /** Амплитуда пульса ядра, 0..1 от [CORE_PULSE]. */
    val corePulse: Float,
    /** Насколько сегменты отходят от ядра на «выдохе», в единицах вьюбокса. */
    val spreadUnits: Float,
    /** Ядро кольцом вместо заливки — статусы, где линия мертва. */
    val coreHollow: Boolean = false,
)

private fun ghostSpec(status: OverlayStatus): GhostSpec = when (status) {
    // Дежурит: едва заметно вращается и дышит, не отвлекая от игры.
    OverlayStatus.Ready -> GhostSpec(
        shell = OverlayColors.TextDim, core = OverlayColors.TextDim,
        spinDegPerSec = 24f, pulseHz = 0.25f, corePulse = 0.3f, spreadUnits = 10f,
    )

    // Слушает: оживился, ядро дышит в полную силу.
    OverlayStatus.Listening -> GhostSpec(
        shell = OverlayColors.Ok, core = OverlayColors.Ok,
        spinDegPerSec = 70f, pulseHz = 1.1f, corePulse = 1f, spreadUnits = 26f,
    )

    // Думает: оболочка крутится быстро — призрак «сканирует».
    OverlayStatus.Thinking -> GhostSpec(
        shell = OverlayColors.Accent, core = OverlayColors.Accent,
        spinDegPerSec = 300f, pulseHz = 1.6f, corePulse = 0.4f, spreadUnits = 32f,
    )

    OverlayStatus.Connecting, is OverlayStatus.Reconnecting -> GhostSpec(
        shell = OverlayColors.Warn, core = OverlayColors.Warn,
        spinDegPerSec = 180f, pulseHz = 1.2f, corePulse = 0.3f, spreadUnits = 20f,
    )

    // Отвечает/говорит: вращение спокойное, зато ядро частит — «речь».
    OverlayStatus.Answering, OverlayStatus.Speaking -> GhostSpec(
        shell = OverlayColors.Accent, core = OverlayColors.Accent,
        spinDegPerSec = 90f, pulseHz = 2.4f, corePulse = 1f, spreadUnits = 14f,
    )

    // Линия мертва: призрак замирает, ядро гаснет до кольца.
    OverlayStatus.Disconnected -> GhostSpec(
        shell = OverlayColors.TextDim, core = OverlayColors.TextDim,
        spinDegPerSec = 0f, pulseHz = 0f, corePulse = 0f, spreadUnits = 0f, coreHollow = true,
    )

    is OverlayStatus.Failed -> GhostSpec(
        shell = OverlayColors.Error, core = OverlayColors.Error,
        spinDegPerSec = 0f, pulseHz = 0f, corePulse = 0f, spreadUnits = 0f, coreHollow = true,
    )
}

/** Контуры сегментов — как в icon_to_animate.svg, вьюбокс 965×965. */
private const val VIEWBOX = 965f
private const val CENTRE = VIEWBOX / 2f

/** Радиус ядра и размах его пульса: внутренний край сегментов ~230, зазор остаётся всегда. */
private const val CORE_RADIUS = 150f
private const val CORE_PULSE = 34f
private const val HOLLOW_STROKE = 38f

/**
 * Пути сегментов из SVG и направление «выдоха» каждого — от центра наружу.
 * Порядок: верхний, правый, нижний, левый.
 */
private val shellSegments: List<Pair<Path, Offset>> by lazy {
    listOf(
        SEGMENT_TOP to Offset(0f, -1f),
        SEGMENT_RIGHT to Offset(1f, 0f),
        SEGMENT_BOTTOM to Offset(0f, 1f),
        SEGMENT_LEFT to Offset(-1f, 0f),
    ).map { (data, direction) -> PathParser().parsePathString(data).toPath() to direction }
}

private const val SEGMENT_TOP =
    "M598.173 261.659C606.633 266.308 617.062 266.686 625.26 261.589L670.042 233.742C682.382 226.069 685.126 " +
        "209.277 675.871 198.075L535.946 28.7137C531.386 23.1953 524.602 20 517.444 20.0001L504.52 20.0001L481.551 " +
        "20L459.58 20.0001H445.685C438.512 20.0001 431.714 23.2091 427.155 28.7479L287.717 198.155C278.515 209.335 " +
        "281.234 226.056 293.505 233.745L337.82 261.511C346.025 266.652 356.494 266.291 364.981 261.63C389.259 " +
        "248.297 432.456 231.216 481.551 230.929C530.68 231.217 573.905 248.322 598.173 261.659Z"

private const val SEGMENT_RIGHT =
    "M702.442 598.173C697.793 606.633 697.415 617.062 702.513 625.26L730.359 670.042C738.032 682.382 754.824 " +
        "685.126 766.026 675.871L935.387 535.946C940.906 531.386 944.101 524.602 944.101 517.444L944.101 " +
        "504.52L944.101 481.551L944.101 459.58L944.101 445.685C944.101 438.512 940.892 431.714 935.353 " +
        "427.155L765.946 287.717C754.766 278.515 738.045 281.234 730.357 293.505L702.59 337.82C697.449 346.025 " +
        "697.81 356.494 702.471 364.981C715.804 389.259 732.885 432.456 733.172 481.551C732.885 530.68 715.779 " +
        "573.905 702.442 598.173Z"

private const val SEGMENT_BOTTOM =
    "M365.928 702.442C357.469 697.793 347.039 697.415 338.842 702.512L294.059 730.359C281.72 738.032 278.975 " +
        "754.824 288.231 766.026L428.156 935.387C432.715 940.906 439.499 944.101 446.658 944.101L459.581 " +
        "944.101L482.55 944.101L504.522 944.101L518.416 944.101C525.59 944.101 532.387 940.892 536.946 " +
        "935.353L676.384 765.946C685.586 754.766 682.867 738.045 670.597 730.356L626.281 702.59C618.076 697.449 " +
        "607.608 697.81 599.12 702.471C574.843 715.803 531.645 732.884 482.55 733.171C433.421 732.884 390.196 " +
        "715.778 365.928 702.442Z"

private const val SEGMENT_LEFT =
    "M261.659 365.928C266.308 357.468 266.686 347.039 261.589 338.842L233.742 294.059C226.069 281.719 209.277 " +
        "278.975 198.075 288.23L28.7137 428.155C23.1953 432.715 20 439.499 20.0001 446.657L20.0001 459.581L20 " +
        "482.55L20.0001 504.521L20.0001 518.416C20.0001 525.59 23.2091 532.387 28.7479 536.946L198.155 " +
        "676.384C209.335 685.586 226.056 682.867 233.745 670.597L261.511 626.281C266.652 618.076 266.291 607.607 " +
        "261.63 599.12C248.297 574.843 231.216 531.645 230.929 482.55C231.217 433.421 248.322 390.196 261.659 365.928Z"
