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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import org.example.overlay.app.OverlayStatus
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Живой призрак вместо набора статусных значков: четыре шеврона оболочки (по два сегмента
 * на сторону, контуры — из ghost_icon_*.svg) движутся вокруг смыслового центра. Когда
 * ассистент слушает или говорит, в центре эквалайзер с бегущей волной; в остальное время —
 * «глаз» из двух стрелок, дышащий масштабом.
 *
 * Движение импульсное: цикл «раскрылся → крутанулся → собрался → отдых» с периодом
 * [GhostSpec.periodSec]. Статус читается по цвету, темпу цикла и характеру вращения.
 *
 * Угол и часы цикла живут в едином [sharedMotion] на всё приложение: `Crossfade` в HUD
 * пересоздаёт композабл на каждую смену статуса, и локальное состояние сбрасывало бы поворот.
 * Общий мотор накапливает угол приращениями, поэтому любой следующий статус (и отсчёт
 * сворачивания) продолжает вращение ровно с того места, где остановился предыдущий.
 */
@Composable
fun GhostIcon(status: OverlayStatus, modifier: Modifier = Modifier) {
    val spec = ghostSpec(status)
    val voice = status == OverlayStatus.Listening ||
        status == OverlayStatus.Answering ||
        status == OverlayStatus.Speaking

    var rotation by remember { mutableFloatStateOf(sharedMotion.rotation) }
    var envelope by remember { mutableFloatStateOf(0f) }
    var coreEnvelope by remember { mutableFloatStateOf(0f) }
    var time by remember { mutableFloatStateOf(sharedMotion.time) }

    // Цикл живёт и в статичных статусах: там мотор доводит поворот до собранной позы
    // и дожидается границы цикла предыдущего статуса.
    LaunchedEffect(spec) {
        while (true) {
            withFrameNanos { now ->
                sharedMotion.advance(spec, now)
                rotation = sharedMotion.rotation
                envelope = sharedMotion.envelope
                coreEnvelope = sharedMotion.coreEnvelope
                time = sharedMotion.time
            }
        }
    }

    Canvas(modifier) {
        drawGhost(spec, rotation, envelope, coreEnvelope, time, voice)
    }
}

/**
 * Обратный отсчёт до сворачивания HUD. Идёт сразу после «печатает», поэтому стартует в его
 * акцентном цвете и гасит шевроны по очереди — верхний, правый, нижний, левый: каждый
 * погасший — минус четверть времени. Глаз гаснет вместе с последним, движение — как у
 * «Готов», и вращение продолжается с угла предыдущего статуса ([sharedMotion]), так что
 * переход бесшовный. [fraction] — сколько времени осталось, от 1 до 0.
 */
@Composable
fun GhostCountdownIcon(fraction: Float, modifier: Modifier = Modifier) {
    var rotation by remember { mutableFloatStateOf(sharedMotion.rotation) }
    var envelope by remember { mutableFloatStateOf(0f) }
    var coreEnvelope by remember { mutableFloatStateOf(0f) }
    var time by remember { mutableFloatStateOf(sharedMotion.time) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now ->
                sharedMotion.advance(COUNTDOWN_SPEC, now)
                rotation = sharedMotion.rotation
                envelope = sharedMotion.envelope
                coreEnvelope = sharedMotion.coreEnvelope
                time = sharedMotion.time
            }
        }
    }

    Canvas(modifier) {
        val f = fraction.coerceIn(0f, 1f)
        // Шеврон i живёт в своей четверти времени и плавно остывает внутри неё.
        val chevronColors = List(CHEVRON_COUNT) { i ->
            val warmth = (f * CHEVRON_COUNT - (CHEVRON_COUNT - 1 - i)).coerceIn(0f, 1f)
            lerp(OverlayColors.TextDim, OverlayColors.Accent, warmth)
        }
        val eyeColor = lerp(OverlayColors.TextDim, OverlayColors.Accent, (f * CHEVRON_COUNT).coerceIn(0f, 1f))
        drawGhost(COUNTDOWN_SPEC, rotation, envelope, coreEnvelope, time, voice = false, chevronColors, eyeColor)
    }
}

/** Движение отсчёта — как у «Готов»: призрак статичен, время показывают гаснущие шевроны. */
private val COUNTDOWN_SPEC = GhostSpec(
    shell = OverlayColors.Accent, core = OverlayColors.Accent,
    periodSec = 0f, spreadUnits = 0f, burstDeg = 0f, idleDegPerSec = 0f,
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
    /** Насколько шевроны отходят от центра на пике раскрытия, в единицах вьюбокса сцены (965). */
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
    /** Амплитуда жизни центра, 0..1: волна эквалайзера или дыхание глаза. */
    val corePulse: Float = 0f,
    /**
     * Свой ритм ядра вместо дыхания в такт оболочке: «два удара → кулдаун», как сердцебиение.
     * Значение — период всего ритма в секундах; 0 — ядро дышит вместе с циклом оболочки.
     */
    val coreBeatPeriodSec: Float = 0f,
    /** Мёртвый статус: центр замирает. */
    val coreHollow: Boolean = false,
)

/**
 * Логика соответствия «действие → поведение»: энергия движения = объём работы, оболочка — про
 * процесс, центр — про голос, редкая вспышка — «я живой», полный замер — «я умер».
 */
private fun ghostSpec(status: OverlayStatus): GhostSpec = when (status) {
    // Дежурит: полностью статичен в собранной позе — жив, но ничего не происходит.
    // Движение в покое только отвлекает; «жив» читается по смене поз при событиях.
    OverlayStatus.Ready -> GhostSpec(
        shell = OverlayColors.TextDim, core = OverlayColors.TextDim,
        periodSec = 0f, spreadUnits = 0f, burstDeg = 0f, idleDegPerSec = 0f,
    )

    // Слушает: проснулся, неглубоко дышит и «вертит головой», эквалайзер ходит волной.
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

    // Говорит: оболочка ровно плывёт с мелкой рябью, в центре — живой эквалайзер.
    OverlayStatus.Answering, OverlayStatus.Speaking -> GhostSpec(
        shell = OverlayColors.Accent, core = OverlayColors.Accent,
        periodSec = 0.2f, spreadUnits = 16f, burstDeg = 0f, idleDegPerSec = 70f,
        glow = 0.65f, flareBoost = 2.1f, corePulse = 0.9f, coreBeatPeriodSec = 1.2f,
    )

    // Линия мертва: единственные статусы без движения вообще, глаз замирает.
    OverlayStatus.Disconnected -> GhostSpec(
        shell = OverlayColors.TextDim, core = OverlayColors.TextDim,
        periodSec = 0f, spreadUnits = 0f, burstDeg = 0f, idleDegPerSec = 0f, coreHollow = true,
    )

    is OverlayStatus.Failed -> GhostSpec(
        shell = OverlayColors.Error, core = OverlayColors.Error,
        periodSec = 0f, spreadUnits = 0f, burstDeg = 0f, idleDegPerSec = 0f, coreHollow = true,
    )
}

/**
 * Единый мотор призрака на всё приложение: смены статусов и отсчёт продолжают движение
 * с того же угла. Во время `Crossfade` два экземпляра иконки живут одновременно и оба
 * зовут [advance] — защита по времени кадра гасит второй вызов (dt = 0).
 */
private val sharedMotion = GhostMotion()

/** Накопленное состояние движения: живёт между кадрами, статусами и композаблами. */
private class GhostMotion {
    var rotation = 0f
        private set
    var envelope = 0f
        private set
    var coreEnvelope = 0f
        private set

    /** Общие часы: на них едет волна эквалайзера. */
    var time = 0f
        private set

    private var lastNanos = 0L
    private var clock = 0f
    private var curCycle = 0L
    private var prevSpinP = 0f
    private var randDir = 1f

    /** Спека, которая реально анимируется; новая ждёт границы цикла (см. [advance]). */
    private var active: GhostSpec? = null

    fun advance(target: GhostSpec, nowNanos: Long) {
        if (lastNanos == 0L) {
            lastNanos = nowNanos
            return
        }
        // Второй вызов в том же кадре (перекрытие Crossfade) — не двигаемся дважды.
        val dt = ((nowNanos - lastNanos) / 1_000_000_000f).coerceAtMost(0.1f)
        if (dt <= 0f) return
        lastNanos = nowNanos
        time += dt

        // Новый статус вступает только после фазы сборки текущего цикла: сегменты сложены,
        // рывок докручен — и лишь тогда призрак меняет поведение.
        val current = active
        val spec = if (current == null || current == target) {
            active = target
            target
        } else {
            val t = if (current.periodSec > 0f) (clock / current.periodSec).mod(1f) else 1f
            if (current.periodSec <= 0f || t >= CLOSE_END) {
                active = target
                clock = 0f
                curCycle = 0L
                prevSpinP = 0f
                target
            } else {
                current
            }
        }

        if (spec.periodSec <= 0f) {
            envelope = 0f
            coreEnvelope = 0f
            prevSpinP = 0f
            if (spec.idleDegPerSec != 0f) {
                rotation = (rotation + spec.idleDegPerSec * dt).mod(360f)
            } else if (spec.burstDeg == 0f) {
                // Статичный покой: поворот доезжает до ближайшего кратного 90° и замирает —
                // у оболочки четырёхлучевая симметрия, это та же собранная поза.
                val rest = (rotation / 90f).roundToInt() * 90f
                rotation += (rest - rotation) * min(1f, dt * 10f)
                if (abs(rest - rotation) < 0.05f) rotation = rest
            }
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
    time: Float,
    /** Центр — эквалайзер (слушает/говорит) или глаз-стрелки (всё остальное). */
    voice: Boolean,
    /** Цвета шевронов по одному, если они разошлись (отсчёт); null — все цветом [GhostSpec.shell]. */
    chevronColors: List<Color>? = null,
    coreColor: Color = spec.core,
) {
    val s = size.minDimension / VIEW_W
    // Разлёт задан в единицах сцены стенда (965) — приводим к вьюбоксу иконки.
    val spread = spec.spreadUnits * envelope / SPREAD_SCALE
    val shellGlow = spec.glow * envelope
    val coreGlow = spec.glow * (if (voice) envelope else coreEnvelope)
    // Blur у Skia — в пикселях устройства, поэтому радиус приводится к размеру холста.
    val shellSigma = shellGlow * GLOW_SIGMA_UNITS * s
    val coreSigma = coreGlow * GLOW_SIGMA_UNITS * s

    withTransform({
        translate((size.width - VIEW_W * s) / 2f, (size.height - VIEW_H * s) / 2f)
        scale(s, s, Offset.Zero)
    }) {
        // Вся оболочка крутится как одно целое — сегменты никогда не идут врозь.
        rotate(rotation, pivot = Offset(CX, CY)) {
            shellSegments.forEachIndexed { index, segment ->
                val color = chevronColors?.get(index / 2) ?: spec.shell
                translate(segment.dir.x * spread, segment.dir.y * spread) {
                    if (shellGlow > 0.03f) drawGlowPath(segment.path, color, shellGlow, shellSigma)
                    drawPath(segment.path, color)
                }
            }
        }

        if (voice) {
            // Эквалайзер: столбики ходят волной, амплитуда — от «пульса ядра».
            for ((index, bar) in EQ_BARS.withIndex()) {
                val wave = (sin(2f * PI.toFloat() * (time * EQ_WAVE_HZ + index * EQ_WAVE_SHIFT)) + 1f) / 2f
                val k = 1f - (1f - wave) * EQ_WAVE_DEPTH * spec.corePulse
                withTransform({ scale(1f, k, pivot = Offset(bar.x + EQ_BAR_W / 2f, EQ_CY)) }) {
                    if (coreGlow > 0.03f) drawGlowBar(bar, coreColor, coreGlow, coreSigma)
                    drawRoundRect(
                        color = coreColor,
                        topLeft = Offset(bar.x, bar.y),
                        size = Size(EQ_BAR_W, bar.h),
                        cornerRadius = CornerRadius(EQ_BAR_R, EQ_BAR_R),
                    )
                }
            }
        } else {
            // Глаз дышит масштабом; в мёртвых статусах замирает.
            val k = if (spec.coreHollow) 1f else 1f + EYE_PULSE * spec.corePulse * (coreEnvelope * 2f - 1f)
            withTransform({ scale(k, k, pivot = Offset(CX, EQ_CY)) }) {
                for (path in eyePaths) {
                    if (coreGlow > 0.03f) drawGlowPath(path, coreColor, coreGlow, coreSigma)
                    drawPath(path, coreColor)
                }
            }
        }
    }
}

/** Размытая копия позади заливки — свечение цветом элемента. */
private fun DrawScope.drawGlowPath(path: Path, color: Color, glow: Float, sigma: Float) {
    if (sigma <= 0f) return
    val paint = glowPaint(color, glow, sigma)
    drawIntoCanvas { it.drawPath(path, paint) }
}

private fun DrawScope.drawGlowBar(bar: EqBar, color: Color, glow: Float, sigma: Float) {
    if (sigma <= 0f) return
    val paint = glowPaint(color, glow, sigma)
    drawIntoCanvas {
        it.drawRoundRect(bar.x, bar.y, bar.x + EQ_BAR_W, bar.y + bar.h, EQ_BAR_R, EQ_BAR_R, paint)
    }
}

private fun glowPaint(color: Color, glow: Float, sigma: Float): Paint {
    val paint = Paint()
    paint.color = color.copy(alpha = min(1f, glow * 1.1f))
    paint.asFrameworkPaint().maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, sigma)
    return paint
}

/** Вьюбокс иконки ghost_icon_*.svg. */
private const val VIEW_W = 294f
private const val VIEW_H = 269f
private const val CX = 147f
private const val CY = 134.5f

/** Спеки разлёта подобраны на стенде в единицах сцены 965 — коэффициент приведения. */
private const val SPREAD_SCALE = 3.2823f

/** Доли периода на фазы цикла: раскрытие держится всю «крутку» и складывается после. */
private const val OPEN_END = 0.22f
private const val SPIN_END = 0.58f
private const val CLOSE_END = 0.82f

/** Доля периода сердцебиения, занятая двумя ударами; остаток — кулдаун. */
private const val BEAT_ACTIVE = 0.6f

/** Радиус размытия свечения на полной силе, в единицах вьюбокса иконки. */
private const val GLOW_SIGMA_UNITS = 12.8f

private const val CHEVRON_COUNT = 4

/** Эквалайзер: геометрия из ghost_icon_listening.svg. */
private class EqBar(val x: Float, val y: Float, val h: Float)

private val EQ_BARS = listOf(
    EqBar(98.8027f, 119.39f, 31.1768f),
    EqBar(120.152f, 106.174f, 57.6093f),
    EqBar(141.501f, 92.958f, 84.0419f),
    EqBar(162.851f, 106.174f, 57.6093f),
    EqBar(184.2f, 119.39f, 31.1768f),
)

private const val EQ_BAR_W = 11.183f
private const val EQ_BAR_R = 5.5915f

/** Вертикальный центр столбиков и глаза — вокруг него масштабируется высота. */
private const val EQ_CY = 134.98f

/** Волна эквалайзера: частота, сдвиг между столбиками (в циклах) и глубина проседания. */
private const val EQ_WAVE_HZ = 1.6f
private const val EQ_WAVE_SHIFT = 0.16f
private const val EQ_WAVE_DEPTH = 0.7f

/** Дыхание глаза: размах масштаба на полном пульсе. */
private const val EYE_PULSE = 0.3f

/** Сегмент оболочки: контур и направление «выдоха» от центра. */
private class ShellSegment(val path: Path, val dir: Offset)

/**
 * Сегменты оболочки: четыре шеврона по два куска, порядок — верх, право, низ, лево
 * (тем же порядком гаснут при отсчёте).
 */
private val shellSegments: List<ShellSegment> by lazy {
    fun seg(data: String, dir: Offset) =
        ShellSegment(PathParser().parsePathString(data).toPath(), dir)
    listOf(
        seg("M141.5 75L114.5 98L69.5 54L117.5 0H147V75H141.5Z", Offset(0f, -1f)),
        seg("M152.5 75L179.5 98L224.5 54L176.5 0H147V75H152.5Z", Offset(0f, -1f)),
        seg("M293.5 117.5V129H204L182 101L227 56.5L293.5 117.5Z", Offset(1f, 0f)),
        seg("M293.5 152V140.5H204L182 168.5L227 213L293.5 152Z", Offset(1f, 0f)),
        seg("M141.5 193.5L114.5 170.5L69.5 214.5L117.5 268.5H147V193.5H141.5Z", Offset(0f, 1f)),
        seg("M152.5 193.5L179.5 170.5L224.5 214.5L176.5 268.5H147V193.5H152.5Z", Offset(0f, 1f)),
        seg("M0 151.5V140H89.5L111.5 168L66.5 212.5L0 151.5Z", Offset(-1f, 0f)),
        seg("M0 117.5V129H89.5L111.5 101L66.5 56.5L0 117.5Z", Offset(-1f, 0f)),
    )
}

/** Глаз-стрелки из ghost_icon_afk.svg. */
private val eyePaths: List<Path> by lazy {
    listOf(
        "M144.19 176.5L111 142.965V126.197L145 93.5V119.49L130.429 134.581L144.19 148.833V176.5Z",
        "M149.81 176.5L183 142.965V126.197L149 93.5V119.49L163.571 134.581L149.81 148.833V176.5Z",
    ).map { PathParser().parsePathString(it).toPath() }
}
