package org.example.overlay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import org.example.overlay.app.OverlayStatus
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Живой призрак вместо набора статусных значков: четыре сегмента оболочки (контуры — из
 * фирменного логотипа) движутся вокруг ядра, как у призрака в игре.
 *
 * Движение импульсное, а не непрерывное: цикл «раскрылся → крутанулся → собрался → отдых»
 * с периодом [GhostSpec.periodSec]. Статус читается по трём каналам — цвет, темп цикла и
 * характер вращения; смысл каждого статуса расписан у [ghostSpec].
 *
 * Угол и часы цикла живут в [GhostMotion] и накапливаются приращениями по кадрам: при смене
 * статуса меняются только коэффициенты, и оболочка продолжает движение с того же места без
 * рывка. Хвост рывка на границе цикла докручивается направлением старого цикла, поэтому
 * чередование и случайные направления тоже бесшовны.
 */
@Composable
fun GhostIcon(status: OverlayStatus, modifier: Modifier = Modifier) {
    val spec = ghostSpec(status)

    var rotation by remember { mutableFloatStateOf(0f) }
    var envelope by remember { mutableFloatStateOf(0f) }
    var coreEnvelope by remember { mutableFloatStateOf(0f) }
    val motion = remember { GhostMotion() }

    LaunchedEffect(spec) {
        if (spec.periodSec == 0f && spec.idleDegPerSec == 0f) {
            // Мёртвые статусы: призрак замирает как есть, кадры не жжём.
            envelope = 0f
            coreEnvelope = 0f
            return@LaunchedEffect
        }
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
                last = now
                motion.advance(spec, dt)
                rotation = motion.rotation
                envelope = motion.envelope
                coreEnvelope = motion.coreEnvelope
            }
        }
    }

    Canvas(modifier) {
        drawGhost(spec, rotation, envelope, coreEnvelope)
    }
}

/**
 * Обратный отсчёт до сворачивания HUD. Идёт сразу после «печатает», поэтому стартует в его
 * акцентном цвете и гасит крылья по очереди — верхнее, правое, нижнее, левое: каждое
 * погасшее крыло — минус четверть времени. Ядро гаснет вместе с последним крылом, движение —
 * как у «Готов», так что к нулю призрак плавно приходит ровно к тому виду, в который HUD
 * и спадает. [fraction] — сколько времени осталось, от 1 до 0.
 */
@Composable
fun GhostCountdownIcon(fraction: Float, modifier: Modifier = Modifier) {
    var rotation by remember { mutableFloatStateOf(0f) }
    var envelope by remember { mutableFloatStateOf(0f) }
    var coreEnvelope by remember { mutableFloatStateOf(0f) }
    val motion = remember { GhostMotion() }

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
                last = now
                motion.advance(COUNTDOWN_SPEC, dt)
                rotation = motion.rotation
                envelope = motion.envelope
                coreEnvelope = motion.coreEnvelope
            }
        }
    }

    Canvas(modifier) {
        val f = fraction.coerceIn(0f, 1f)
        // Крыло i живёт в своей четверти времени и плавно остывает внутри неё.
        val shellColors = List(SEGMENT_COUNT) { i ->
            val warmth = (f * SEGMENT_COUNT - (SEGMENT_COUNT - 1 - i)).coerceIn(0f, 1f)
            lerp(OverlayColors.TextDim, OverlayColors.Accent, warmth)
        }
        val coreColor = lerp(OverlayColors.TextDim, OverlayColors.Accent, (f * SEGMENT_COUNT).coerceIn(0f, 1f))
        drawGhost(COUNTDOWN_SPEC, rotation, envelope, coreEnvelope, shellColors, coreColor)
    }
}

/** Движение отсчёта — как у «Готов»: HUD спадает в пилюлю без смены пластики. */
private val COUNTDOWN_SPEC = GhostSpec(
    shell = OverlayColors.Accent, core = OverlayColors.Accent,
    periodSec = 3f, spreadUnits = 0f, burstDeg = 20f, idleDegPerSec = 4f,
    direction = SpinDirection.Alternate, glow = 0.2f, corePulse = 0.2f,
)

/** Направление рывков оболочки. */
private enum class SpinDirection {
    Clockwise, CounterClockwise,

    /** Каждый цикл — в противоположную сторону: призрак «вертит головой». */
    Alternate,

    /** Случайная сторона на каждый цикл: хаос, «сканирую». */
    Random,
}

/** Поведение призрака в одном статусе. */
private data class GhostSpec(
    val shell: Color,
    val core: Color,
    /** Период цикла «раскрылся → крутанулся → собрался», сек. 0 — призрак замер. */
    val periodSec: Float,
    /** Насколько сегменты отходят от ядра на пике раскрытия, в единицах вьюбокса. */
    val spreadUnits: Float,
    /** Угол рывка за цикл. Кратный 90° бесшовен: у оболочки четырёхлучевая симметрия. */
    val burstDeg: Float,
    /** Фоновое вращение между рывками, градусов в секунду. */
    val idleDegPerSec: Float,
    val direction: SpinDirection = SpinDirection.Clockwise,
    /** Сила свечения на раскрытии, 0..1. */
    val glow: Float = 0f,
    /** Каждый N-й цикл — вспышка: шире и ярче. 0 — без вспышек. */
    val flareEveryN: Int = 0,
    val flareBoost: Float = 1.6f,
    /** Амплитуда дыхания ядра, 0..1 от [CORE_PULSE]. Ядро — про голос, оболочка — про работу. */
    val corePulse: Float = 0f,
    /**
     * Свой ритм ядра вместо дыхания в такт оболочке: «два удара → кулдаун», как сердцебиение.
     * Значение — период всего ритма в секундах; 0 — ядро дышит вместе с циклом оболочки.
     */
    val coreBeatPeriodSec: Float = 0f,
    /** Ядро кольцом вместо заливки — статусы, где линия мертва. */
    val coreHollow: Boolean = false,
)

/**
 * Логика соответствия «действие → поведение»: энергия движения = объём работы, оболочка — про
 * процесс, ядро — про голос, редкая вспышка — «я живой», полный замер — «я умер».
 */
private fun ghostSpec(status: OverlayStatus): GhostSpec = when (status) {
    // Дежурит: сегменты не расходятся, только тихое покачивание ±20° — жив, но ничего не происходит.
    OverlayStatus.Ready -> GhostSpec(
        shell = OverlayColors.TextDim, core = OverlayColors.TextDim,
        periodSec = 3f, spreadUnits = 0f, burstDeg = 20f, idleDegPerSec = 4f,
        direction = SpinDirection.Alternate, glow = 0.1f, corePulse = 0.2f,
    )

    // Слушает: проснулся, неглубоко дышит и «вертит головой», ядро ходит в такт.
    OverlayStatus.Listening -> GhostSpec(
        shell = OverlayColors.Ok, core = OverlayColors.Ok,
        periodSec = 1.3f, spreadUnits = 18f, burstDeg = 20f, idleDegPerSec = 0f,
        direction = SpinDirection.Alternate, glow = 0.55f, corePulse = 0.6f,
    )

    // Думает: самый быстрый режим — широкие полуобороты туда-сюда, светится на каждом цикле.
    OverlayStatus.Thinking -> GhostSpec(
        shell = OverlayColors.Accent, core = OverlayColors.Accent,
        periodSec = 0.8f, spreadUnits = 62f, burstDeg = 180f, idleDegPerSec = 24f,
        direction = SpinDirection.Alternate, glow = 0.65f, flareEveryN = 1, flareBoost = 1.4f,
        corePulse = 0.3f,
    )

    // Стучится: ровные методичные обороты в одну сторону — попытка за попыткой.
    OverlayStatus.Connecting, is OverlayStatus.Reconnecting -> GhostSpec(
        shell = OverlayColors.Warn, core = OverlayColors.Warn,
        periodSec = 1.5f, spreadUnits = 40f, burstDeg = 90f, idleDegPerSec = 14f,
        glow = 0.25f, corePulse = 0.25f,
    )

    // Говорит: оболочка ровно плывёт с мелкой рябью, а ядро бьётся своим ритмом —
    // два удара, кулдаун, снова два удара, как речь с паузами.
    OverlayStatus.Answering, OverlayStatus.Speaking -> GhostSpec(
        shell = OverlayColors.Accent, core = OverlayColors.Accent,
        periodSec = 0.2f, spreadUnits = 16f, burstDeg = 0f, idleDegPerSec = 70f,
        glow = 0.65f, flareBoost = 2.1f, corePulse = 0.9f, coreBeatPeriodSec = 1.2f,
    )

    // Линия мертва: единственные статусы без движения вообще, ядро гаснет до кольца.
    OverlayStatus.Disconnected -> GhostSpec(
        shell = OverlayColors.TextDim, core = OverlayColors.TextDim,
        periodSec = 0f, spreadUnits = 0f, burstDeg = 0f, idleDegPerSec = 0f, coreHollow = true,
    )

    is OverlayStatus.Failed -> GhostSpec(
        shell = OverlayColors.Error, core = OverlayColors.Error,
        periodSec = 0f, spreadUnits = 0f, burstDeg = 0f, idleDegPerSec = 0f, coreHollow = true,
    )
}

/** Накопленное состояние движения: живёт между кадрами и между сменами статуса. */
private class GhostMotion {
    var rotation = 0f
        private set
    var envelope = 0f
        private set
    var coreEnvelope = 0f
        private set

    private var clock = 0f
    private var curCycle = 0L
    private var prevSpinP = 0f
    private var randDir = 1f

    fun advance(spec: GhostSpec, dt: Float) {
        if (spec.periodSec <= 0f) {
            envelope = 0f
            coreEnvelope = 0f
            rotation = (rotation + spec.idleDegPerSec * dt).mod(360f)
            prevSpinP = 0f
            return
        }
        clock += dt
        val cycles = (clock / spec.periodSec).toLong()
        val t = clock / spec.periodSec - cycles
        val env: Float
        val spinP: Float
        when {
            t < OPEN_END -> {
                env = easeOut(t / OPEN_END)
                spinP = 0f
            }
            t < SPIN_END -> {
                env = 1f
                spinP = easeInOut((t - OPEN_END) / (SPIN_END - OPEN_END))
            }
            t < CLOSE_END -> {
                env = 1f - easeInOut((t - SPIN_END) / (CLOSE_END - SPIN_END))
                spinP = 1f
            }
            else -> {
                env = 0f
                spinP = 1f
            }
        }

        val flare = if (spec.flareEveryN > 0 && cycles % spec.flareEveryN == spec.flareEveryN - 1L) {
            spec.flareBoost
        } else {
            1f
        }
        envelope = env * flare

        coreEnvelope = if (spec.coreBeatPeriodSec > 0f) {
            // Сердцебиение: sin² на активной части периода даёт ровно два удара, дальше кулдаун.
            val beat = (clock / spec.coreBeatPeriodSec).mod(1f)
            if (beat < BEAT_ACTIVE) sin(beat / BEAT_ACTIVE * 2f * PI.toFloat()).pow(2) else 0f
        } else {
            envelope
        }

        if (cycles != curCycle) {
            // Хвост прошлого цикла докручивается его же направлением, и только потом смена.
            rotation += spec.burstDeg * direction(spec, curCycle) * (1f - prevSpinP)
            curCycle = cycles
            prevSpinP = 0f
            if (spec.direction == SpinDirection.Random) randDir = if (Random.nextBoolean()) 1f else -1f
        }
        val dir = direction(spec, cycles)
        rotation = (rotation + spec.burstDeg * dir * (spinP - prevSpinP) + spec.idleDegPerSec * dir * dt).mod(360f)
        prevSpinP = spinP
    }

    private fun direction(spec: GhostSpec, cycle: Long): Float = when (spec.direction) {
        SpinDirection.Clockwise -> 1f
        SpinDirection.CounterClockwise -> -1f
        SpinDirection.Alternate -> if (cycle % 2 == 0L) 1f else -1f
        SpinDirection.Random -> randDir
    }
}

private fun easeOut(x: Float) = 1f - (1f - x).pow(3)

private fun easeInOut(x: Float) =
    if (x < 0.5f) 4f * x * x * x else 1f - ((-2f * x + 2f).pow(3)) / 2f

private fun DrawScope.drawGhost(
    spec: GhostSpec,
    rotation: Float,
    envelope: Float,
    coreEnvelope: Float,
    /** Цвета крыльев по одному, если они разошлись (отсчёт); null — все цветом [GhostSpec.shell]. */
    shellColors: List<Color>? = null,
    coreColor: Color = spec.core,
) {
    val s = size.minDimension / VIEWBOX
    val spread = spec.spreadUnits * envelope
    // Оболочка светится с раскрытием, ядро — со своим ритмом (при сердцебиении они разные).
    val shellGlow = spec.glow * envelope
    val coreGlow = spec.glow * coreEnvelope
    // Blur у Skia — в пикселях устройства, поэтому радиус приводится к размеру холста.
    val shellSigma = shellGlow * GLOW_SIGMA_UNITS * s
    val coreSigma = coreGlow * GLOW_SIGMA_UNITS * s

    withTransform({
        translate((size.width - VIEWBOX * s) / 2f, (size.height - VIEWBOX * s) / 2f)
        scale(s, s, Offset.Zero)
    }) {
        rotate(rotation, pivot = Offset(CENTRE, CENTRE)) {
            shellSegments.forEachIndexed { index, (path, direction) ->
                val color = shellColors?.get(index) ?: spec.shell
                translate(direction.x * spread, direction.y * spread) {
                    if (shellGlow > 0.03f) drawGlowPath(path, color, shellGlow, shellSigma)
                    drawPath(path, color)
                }
            }
        }

        if (spec.coreHollow) {
            // Погасшее ядро: пустое кольцо — призрак «выключен».
            drawCircle(
                color = coreColor,
                radius = CORE_RADIUS,
                center = Offset(CENTRE, CENTRE),
                style = Stroke(width = HOLLOW_STROKE),
            )
        } else {
            val radius = CORE_RADIUS + CORE_PULSE * spec.corePulse * (coreEnvelope * 2f - 1f)
            if (coreGlow > 0.03f) drawGlowCircle(radius, coreColor, coreGlow, coreSigma)
            drawCircle(color = coreColor, radius = radius, center = Offset(CENTRE, CENTRE))
        }
    }
}

/** Размытая копия сегмента позади заливки — свечение цветом оболочки. */
private fun DrawScope.drawGlowPath(path: Path, color: Color, glow: Float, sigma: Float) {
    if (sigma <= 0f) return
    val paint = glowPaint(color, glow, sigma)
    drawIntoCanvas { it.drawPath(path, paint) }
}

private fun DrawScope.drawGlowCircle(radius: Float, color: Color, glow: Float, sigma: Float) {
    if (sigma <= 0f) return
    val paint = glowPaint(color, glow, sigma)
    drawIntoCanvas { it.drawCircle(Offset(CENTRE, CENTRE), radius, paint) }
}

private fun glowPaint(color: Color, glow: Float, sigma: Float): Paint {
    val paint = Paint()
    paint.color = color.copy(alpha = min(1f, glow * 1.1f))
    paint.asFrameworkPaint().maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, sigma)
    return paint
}

/** Контуры сегментов — как в icon_to_animate.svg, вьюбокс 965×965. */
private const val VIEWBOX = 965f
private const val CENTRE = VIEWBOX / 2f

/** Ядро и размах его пульса — подобраны на стенде. */
private const val CORE_RADIUS = 144f
private const val CORE_PULSE = 60f
private const val HOLLOW_STROKE = 38f

/** Доля периода сердцебиения, занятая двумя ударами; остаток — кулдаун. */
private const val BEAT_ACTIVE = 0.6f

/** Доли периода на фазы цикла: раскрытие держится всю «крутку» и складывается после. */
private const val OPEN_END = 0.22f
private const val SPIN_END = 0.58f
private const val CLOSE_END = 0.82f

/** Радиус размытия свечения на полной силе, в единицах вьюбокса. */
private const val GLOW_SIGMA_UNITS = 42f

private const val SEGMENT_COUNT = 4

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
