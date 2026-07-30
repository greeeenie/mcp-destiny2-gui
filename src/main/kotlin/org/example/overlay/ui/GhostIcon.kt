package org.example.overlay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
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
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.cos

/**
 * Живой призрак вместо набора статусных значков — силуэт «Пружина» из фирменного логотипа
 * (destiny-2-assistant-logo-vector-color): четыре крыла вокруг ядра-глаза из двух скобок
 * и круга. Крылья делают полуоборот с пружинным перелётом (кривая снята с CSS-анимации
 * исходного SVG), ядро контрит его полуоборотом навстречу — как в оригинале.
 *
 * Статус читается по самому ядру: круг дышит настоящим голосом и скобки приоткрываются,
 * скобки-радар кружат при обдумывании, круг бьётся сердцем и расталкивает скобки при
 * речи, подмигивает при подключении, тлеет при обрыве и уступает место «!» при ошибке.
 * В покое призрак полностью статичен в авторской позе.
 *
 * Часы цикла живут в едином [sharedMotion] на всё приложение: `Crossfade` в HUD
 * пересоздаёт композабл на каждую смену статуса, и локальное состояние сбрасывало бы
 * фазу. Новый статус вступает только на границе пружинного цикла — поза в этот момент
 * собрана, скачка нет.
 *
 * [micLevel] — живой уровень микрофона 0..1: в статусе «Слушает» им дышит ядро.
 */
@Composable
fun GhostIcon(status: OverlayStatus, micLevel: Float = 0f, modifier: Modifier = Modifier) {
    val spec = ghostSpec(status)
    val levelInput by rememberUpdatedState(micLevel)

    var prof by remember { mutableFloatStateOf(sharedMotion.prof) }
    var clock by remember { mutableFloatStateOf(sharedMotion.clock) }
    var level by remember { mutableFloatStateOf(0f) }

    // Цикл живёт и в статичных статусах: мотор дожидается границы цикла предыдущего.
    LaunchedEffect(spec) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                sharedMotion.advance(spec, now)
                prof = sharedMotion.prof
                clock = sharedMotion.clock

                // Сглаживание голоса: замеры приходят раз в ~100 мс, ядро не должно дёргаться.
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
                    level += (levelInput - level) * min(1f, dt * 14f)
                }
                last = now
            }
        }
    }

    Canvas(modifier) {
        drawGhost(spec, prof, clock, level)
    }
}

/**
 * Обратный отсчёт до сворачивания HUD: ответ готов — все крылья вспыхивают акцентным
 * цветом, а дальше гаснут по очереди (верхнее, правое, нижнее, левое). Каждое крыло
 * в свою четверть времени мигает «синее/серое» и к концу четверти гаснет насовсем:
 * мигание заметно боковым зрением лучше плавного остывания. Ядро остывает с последней
 * четвертью; сам призрак статичен, так что к нулю он приходит ровно к виду «Готов».
 * [fraction] — сколько времени осталось, от 1 до 0.
 */
@Composable
fun GhostCountdownIcon(fraction: Float, modifier: Modifier = Modifier) {
    var prof by remember { mutableFloatStateOf(sharedMotion.prof) }
    val fractionNow by rememberUpdatedState(fraction)

    // Сколько времени оставалось в момент, когда пружина реально остановилась:
    // мотор докручивает цикл «печатает» до границы, и запускать волну поверх
    // ещё крутящихся крыльев нельзя — выглядит как два несогласованных слоя.
    var igniteStart by remember { mutableFloatStateOf(Float.NaN) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now ->
                sharedMotion.advance(COUNTDOWN_SPEC, now)
                prof = sharedMotion.prof
                if (igniteStart.isNaN() && sharedMotion.settled) {
                    igniteStart = fractionNow.coerceIn(0.01f, 1f)
                }
            }
        }
    }

    Canvas(modifier) {
        val f = fraction.coerceIn(0f, 1f)
        val start = igniteStart
        val wingColors: List<Color>
        val coreColor: Color
        if (start.isNaN()) {
            // Пружина ещё докручивает цикл: крылья серые, как в «печатает», ядро горит.
            wingColors = List(WING_COUNT) { OverlayColors.TextDim }
            coreColor = OverlayColors.Accent
        } else {
            // Вся цветовая партитура укладывается в оставшееся с момента остановки
            // время: ef идёт от 1 к 0, как обычный fraction, только без хвоста мотора.
            val ef = (f / start).coerceIn(0f, 1f)
            // Каскадное зажигание: крылья загораются волной по кругу (верх → право →
            // низ → лево) за первую десятую отсчёта — вместо резкой общей вспышки.
            // Волна и дальнейшее гашение идут в одном порядке.
            val ignite = ((1f - ef) / IGNITE_FRACTION).coerceIn(0f, 1f)
            wingColors = List(WING_COUNT) { i ->
                if (ignite < 1f) {
                    lerp(
                        OverlayColors.TextDim, OverlayColors.Accent,
                        (ignite * WING_COUNT - i).coerceIn(0f, 1f),
                    )
                } else {
                    // Четверть крыла: > 1 — очередь не дошла, 0..1 — мигает, <= 0 — погасло.
                    val q = ef * WING_COUNT - (WING_COUNT - 1 - i)
                    when {
                        q >= 1f -> OverlayColors.Accent
                        q <= 0f -> OverlayColors.TextDim
                        // Чётные отрезки — синий, нечётные — серый; последний отрезок нечётный,
                        // поэтому крыло всегда догорает в сером и гаснет без скачка.
                        else -> if (((1f - q) * BLINK_SEGMENTS).toInt() % 2 == 0) {
                            OverlayColors.Accent
                        } else {
                            OverlayColors.TextDim
                        }
                    }
                }
            }
            coreColor = lerp(OverlayColors.TextDim, OverlayColors.Accent, (ef * WING_COUNT).coerceIn(0f, 1f))
        }
        drawGhost(
            COUNTDOWN_SPEC, prof, clock = 0f, level = 0f,
            wingColors = wingColors,
            coreColor = coreColor,
        )
    }
}

/** Отрезков мигания на четверть отсчёта: три вспышки «синее/серое» на крыло. */
private const val BLINK_SEGMENTS = 6

/** Доля отсчёта на волну зажигания крыльев — гасит резкий стык с «печатает». */
private const val IGNITE_FRACTION = 0.1f

/** Отсчёт статичен и сер, как «Готов»: время показывают остывающие крылья. */
private val COUNTDOWN_SPEC = GhostSpec(
    shell = OverlayColors.TextDim, core = OverlayColors.TextDim, periodSec = 0f,
)

/** Как ядро-глаз рассказывает о происходящем: скобки и круг живут раздельно. */
private enum class CenterStyle {
    /** Авторская поза: скобки и круг на месте. */
    Steady,

    /** Круг дышит настоящим уровнем микрофона, скобки приоткрываются на громком. */
    Voice,

    /** Скобки-радар безостановочно кружат вокруг круга — «сканирую». */
    Radar,

    /** Сердцебиение круга (два удара → кулдаун); скобки толкаются наружу на ударах. */
    Beat,

    /** Двойное подмигивание круга, как индикатор на роутере: «стучусь». */
    Blink,

    /** Потухший глаз: скобки на месте, круг едва тлеет. */
    Dim,

    /** Вместо круга — «!»: ошибка, в отличие от тихого обрыва. */
    Bang,
}

/** Поведение призрака в одном статусе. */
private data class GhostSpec(
    val shell: Color,
    val core: Color,
    /** Базовый период статуса, сек; сам пружинный цикл длиннее в [SPRING_CYCLE] раз. 0 — замер. */
    val periodSec: Float,
    /** Сила свечения на пике рывка, 0..1. */
    val glow: Float = 0f,
    /** Амплитуда сердцебиения круга, 0..1 (для [CenterStyle.Beat]). */
    val pulse: Float = 0f,
    val center: CenterStyle = CenterStyle.Steady,
)

/**
 * Логика соответствия «действие → поведение»: крылья рассказывают про процесс только
 * движением (темп пружины = объём работы) и всегда серые — весь цвет несёт ядро,
 * поэтому статус читается по одной точке. Полный замер — «я умер».
 */
private fun ghostSpec(status: OverlayStatus): GhostSpec = when (status) {
    // Дежурит: полностью статичен в авторской позе — жив, но не отвлекает.
    OverlayStatus.Ready -> GhostSpec(
        shell = OverlayColors.TextDim, core = OverlayColors.TextDim, periodSec = 0f,
    )

    // Слушает: неспешные пружинные обороты, ядро дышит настоящим голосом.
    OverlayStatus.Listening -> GhostSpec(
        shell = OverlayColors.TextDim, core = OverlayColors.Ok,
        periodSec = 1.1f, glow = 0.55f, center = CenterStyle.Voice,
    )

    // Думает: бодрые обороты, скобки-радар кружат — «высматривает».
    OverlayStatus.Thinking -> GhostSpec(
        shell = OverlayColors.TextDim, core = OverlayColors.Accent,
        periodSec = 0.8f, glow = 0.65f, center = CenterStyle.Radar,
    )

    // Стучится: медленные обороты, круг подмигивает.
    OverlayStatus.Connecting, is OverlayStatus.Reconnecting -> GhostSpec(
        shell = OverlayColors.TextDim, core = OverlayColors.Warn,
        periodSec = 1.5f, glow = 0.25f, center = CenterStyle.Blink,
    )

    // Говорит: ровный ход, круг бьётся сердцем и расталкивает скобки.
    OverlayStatus.Answering, OverlayStatus.Speaking -> GhostSpec(
        shell = OverlayColors.TextDim, core = OverlayColors.Accent,
        periodSec = 0.9f, glow = 0.65f, pulse = 0.9f, center = CenterStyle.Beat,
    )

    // Линия мертва: без движения, круг едва тлеет.
    OverlayStatus.Disconnected -> GhostSpec(
        shell = OverlayColors.TextDim, core = OverlayColors.TextDim,
        periodSec = 0f, center = CenterStyle.Dim,
    )

    // Ошибка: вместо круга «!» в красных скобках — отличается от обрыва не только цветом.
    is OverlayStatus.Failed -> GhostSpec(
        shell = OverlayColors.TextDim, core = OverlayColors.Error,
        periodSec = 0f, center = CenterStyle.Bang,
    )
}

/**
 * Единый мотор призрака на всё приложение: смены статусов и отсчёт продолжают ход с той же
 * фазы. Во время `Crossfade` два экземпляра иконки живут одновременно и оба зовут
 * [GhostMotion.advance] — защита по времени кадра гасит второй вызов (dt = 0).
 */
private val sharedMotion = GhostMotion()

/** Накопленное состояние движения: живёт между кадрами, статусами и композаблами. */
private class GhostMotion {
    /** Профиль пружины 0..1 (с перелётом): 0 — собранная поза, 1 — полуоборот. */
    var prof = 0f
        private set

    /** Часы текущего статуса: на них едут радар, сердцебиение и мигание ядра. */
    var clock = 0f
        private set

    private var lastNanos = 0L

    /** Спека, которая реально анимируется; новая ждёт границы цикла (см. [advance]). */
    private var active: GhostSpec? = null

    /** Движения нет: активная спека статична, пружина в собранной позе. */
    val settled: Boolean
        get() = active?.let { it.periodSec <= 0f } ?: false

    fun advance(target: GhostSpec, nowNanos: Long) {
        if (lastNanos == 0L) {
            lastNanos = nowNanos
            return
        }
        // Второй вызов в том же кадре (перекрытие Crossfade) — не двигаемся дважды.
        val dt = ((nowNanos - lastNanos) / 1_000_000_000f).coerceAtMost(0.1f)
        if (dt <= 0f) return
        lastNanos = nowNanos

        // Новый статус вступает только на финальной паузе цикла: пружина в собранной
        // позе (prof = 0), поэтому смена поведения не дёргает картинку.
        val current = active
        val spec = if (current == null || current == target) {
            active = target
            target
        } else {
            val dur = current.periodSec * SPRING_CYCLE
            val t = if (dur > 0f) (clock / dur).mod(1f) else 1f
            if (dur <= 0f || t >= SWITCH_GATE) {
                active = target
                clock = 0f
                target
            } else {
                current
            }
        }

        if (spec.periodSec <= 0f) {
            prof = 0f
            return
        }
        clock += dt
        prof = springProfile((clock / (spec.periodSec * SPRING_CYCLE)).mod(1f))
    }
}

/** Пружинная ступень 0→1 с перелётом ~6% — подгон под кривую из исходного SVG. */
private fun springStep(x: Float): Float {
    val s = 7.4f
    val w = 8.27f
    return 1f - exp(-s * x) * (cos(w * x) + s / w * sin(w * x))
}

/** Раскладка цикла из исходника: полуоборот → пауза → полуоборот обратно → пауза. */
private fun springProfile(t: Float): Float = when {
    t < 0.39f -> springStep(t / 0.39f)
    t < 0.51f -> 1f
    t < 0.9f -> 1f - springStep((t - 0.51f) / 0.39f)
    else -> 0f
}

private fun DrawScope.drawGhost(
    spec: GhostSpec,
    prof: Float,
    clock: Float,
    level: Float,
    /** Цвета крыльев по одному, если они разошлись (отсчёт); null — все цветом [GhostSpec.shell]. */
    wingColors: List<Color>? = null,
    coreColor: Color = spec.core,
) {
    val s = size.minDimension / VIEWBOX
    // Свечение вспыхивает в момент рывка и гаснет на паузах; перелёт профиля обрезается.
    val glowNow = spec.glow * sin(PI.toFloat() * prof.coerceIn(0f, 1f))
    // Blur у Skia — в пикселях устройства, поэтому радиус приводится к размеру холста.
    val sigma = glowNow * GLOW_SIGMA_UNITS * s

    withTransform({
        translate(size.width / 2f - WING_CX * s, size.height / 2f - WING_CY * s)
        scale(s, s, Offset.Zero)
    }) {
        // Крылья — полуоборот против часовой с пружиной, как в исходном логотипе.
        rotate(-180f * prof, pivot = Offset(WING_CX, WING_CY)) {
            wings.forEachIndexed { index, wing ->
                val color = wingColors?.get(index) ?: spec.shell
                if (glowNow > 0.03f) drawGlowPath(wing, color, glowNow, sigma)
                drawPath(wing, color)
            }
        }

        // Ядро: скобки и круг живут раздельно — каждый статус играет ими по-своему.
        var dotK = 1f
        var dotAlpha = 1f
        var brSpread = 0f
        var brSpin = 0f
        var bang = false
        when (spec.center) {
            CenterStyle.Steady -> Unit

            CenterStyle.Voice -> {
                dotK = 0.75f + 1.3f * level
                brSpread = 26f * level
            }

            CenterStyle.Radar -> brSpin = (clock * RADAR_DEG_PER_SEC).mod(360f)

            CenterStyle.Beat -> {
                val b = (clock / BEAT_PERIOD).mod(1f)
                val w = if (b < BEAT_ACTIVE) sin(b / BEAT_ACTIVE * 2f * PI.toFloat()).pow(2) else 0f
                dotK = 1.1f + 0.75f * spec.pulse * (w * 2f - 1f)
                brSpread = 34f * w
            }

            CenterStyle.Blink -> {
                val p = clock.mod(1f)
                dotAlpha = if (p < 0.09f || (p > 0.2f && p < 0.29f)) 1f else 0.25f
            }

            CenterStyle.Dim -> dotAlpha = 0.25f

            CenterStyle.Bang -> bang = true
        }

        // Скобки контрят пружину полуоборотом навстречу; радар добавляет свой ход.
        rotate(CORE_BASE_DEG + 180f * prof + brSpin, pivot = Offset(CORE_CX, CORE_CY)) {
            translate(-brSpread, 0f) { drawPath(bracketLeft, coreColor) }
            translate(brSpread, 0f) { drawPath(bracketRight, coreColor) }
        }
        if (bang) {
            drawRoundRect(
                color = coreColor,
                topLeft = Offset(BANG_X, BANG_Y),
                size = Size(BANG_W, BANG_H),
                cornerRadius = CornerRadius(BANG_W / 2f, BANG_W / 2f),
            )
            drawCircle(coreColor, radius = BANG_DOT_R, center = Offset(CORE_CX, BANG_DOT_Y))
        } else {
            drawCircle(
                color = coreColor.copy(alpha = coreColor.alpha * dotAlpha),
                radius = DOT_R * dotK,
                center = Offset(DOT_CX, DOT_CY),
            )
        }
    }
}

/** Размытая копия позади заливки — свечение цветом элемента. */
private fun DrawScope.drawGlowPath(path: Path, color: Color, glow: Float, sigma: Float) {
    if (sigma <= 0f) return
    val paint = Paint()
    paint.color = color.copy(alpha = min(1f, glow * 1.1f))
    paint.asFrameworkPaint().maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, sigma)
    drawIntoCanvas { it.drawPath(path, paint) }
}

/**
 * Геометрия — из destiny-2-assistant-logo-vector-color 4.svg (вьюбокс 992×938). Трансформы
 * вложенных групп запечены в контуры при загрузке, рисование идёт в координатах исходника;
 * холст масштабируется как прежний квадратный вьюбокс, чтобы размер иконки не поменялся.
 */
private const val VIEWBOX = 965f

/** Центр вращения крыльев и ядра в координатах исходника. */
private const val WING_CX = 495.8f
private const val WING_CY = 455.49f
private const val CORE_CX = 496f
private const val CORE_CY = 455f

/** Круг ядра. */
private const val DOT_CX = 496.5f
private const val DOT_CY = 455.5f
private const val DOT_R = 46.5f

/** «!» на месте круга при ошибке. */
private const val BANG_X = 484f
private const val BANG_Y = 370f
private const val BANG_W = 24f
private const val BANG_H = 110f
private const val BANG_DOT_Y = 522f
private const val BANG_DOT_R = 17f

private const val WING_COUNT = 4

/** Базовый поворот ядра в покое: скобки по бокам круга (исходник повёрнут на 90°). */
private const val CORE_BASE_DEG = 0f

/** Пружинный цикл длиннее базового периода статуса (в исходнике полный цикл 2.55 с). */
private const val SPRING_CYCLE = 1.8f

/** Смена статуса — на финальной паузе цикла, когда пружина в собранной позе. */
private const val SWITCH_GATE = 0.9f

/** Скобки-радар у «Думает», градусов в секунду. */
private const val RADAR_DEG_PER_SEC = 160f

/** Период сердцебиения «два удара → кулдаун» и доля периода, занятая ударами. */
private const val BEAT_PERIOD = 1.2f
private const val BEAT_ACTIVE = 0.6f

/** Радиус размытия свечения на полной силе, в единицах вьюбокса. */
private const val GLOW_SIGMA_UNITS = 42f

private fun bakedPath(data: String, setup: Matrix.() -> Unit): Path {
    val matrix = Matrix()
    matrix.setup()
    val path = PathParser().parsePathString(data).toPath()
    path.transform(matrix)
    return path
}

/** Крылья: верхнее, правое, нижнее, левое — порядок гашения при отсчёте. */
private val wings: List<Path> by lazy {
    listOf(
        bakedPath(WING_VERTICAL) { translate(284.108f, 23.4405f) },
        bakedPath(WING_HORIZONTAL) {
            translate(977.85f, 243.801f)
            rotateZ(90f)
        },
        bakedPath(WING_VERTICAL) {
            translate(707.49f, 937.5417f)
            rotateZ(-180f)
            translate(0f, 49.9998f)
        },
        bakedPath(WING_HORIZONTAL) {
            translate(13.749f, 667.183f)
            rotateZ(-90f)
        },
    )
}

private val bracketLeft: Path by lazy { bakedPath(BRACKET_LEFT) { translate(293f, 221f) } }
private val bracketRight: Path by lazy { bakedPath(BRACKET_RIGHT) { translate(293f, 221f) } }

/** Контур верхнего/нижнего крыла (в исходнике — с вырезом-дугой у ядра). */
private const val WING_VERTICAL =
    "M329.094 213.285C336.866 217.111 346.073 217.428 353.672 213.27L395.805 190.215C409.574 182.68 412.524 " +
        "164.176 401.782 152.733L265.497 7.5728C260.96 2.74084 254.627 3.38311e-06 248 9.34735e-06L234.161 " +
        "2.18009e-05H211.191H189.22H174.41C167.766 2.18009e-05 161.42 2.75377 156.882 7.60564L21.0652 " +
        "152.812C10.3784 164.238 13.3047 182.67 27.0048 190.223L68.6855 213.205C76.2952 217.401 85.5364 217.098 " +
        "93.3336 213.262C117.352 201.446 161.216 185.875 211.191 185.618C261.197 185.875 305.087 201.466 " +
        "329.094 213.285Z"

/** Контур правого/левого крыла (с раздвоенным «хвостиком»-плавником к центру). */
private const val WING_HORIZONTAL =
    "M328.012 259.182C335.99 265.669 347.116 266.143 355.653 260.41L401.808 229.414C413.295 221.7 415.897 " +
        "205.897 407.49 194.907L265.591 9.41776C261.05 3.48174 254.003 4.28264e-06 246.529 1.15841e-05L240.832 " +
        "1.71502e-05C235.375 2.24813e-05 230.926 4.37463 230.833 9.83066L227.786 189.781C227.627 199.146 220.007 " +
        "206.664 210.641 206.696C201.164 206.729 193.444 199.092 193.376 189.615L192.072 9.92748C192.032 4.43308 " +
        "187.567 2.67556e-05 182.072 2.67556e-05H175.88C168.391 2.67556e-05 161.331 3.49594 156.792 " +
        "9.45233L15.3908 194.988C7.03454 205.952 9.6114 221.686 21.0287 229.412L66.7097 260.324C75.2521 266.104 " +
        "86.4177 265.647 94.4209 259.14C118.661 239.433 161.961 206.591 211.191 206.28C260.455 206.591 303.782 " +
        "239.479 328.012 259.182Z"

/** Левая скобка ядра-глаза. */
private const val BRACKET_LEFT =
    "M187.157 443.681C187.157 452.615 176.335 457.066 170.05 450.716L2.89269 281.832C1.03951 279.959 0 277.431 " +
        "0 274.797V188.621C0 185.901 1.10801 183.298 3.06861 181.413L174.791 16.2815C181.145 10.1715 191.722 " +
        "14.6746 191.722 23.4896V142.505C191.722 145.097 190.716 147.587 188.916 149.452L116.263 224.69C112.521 " +
        "228.565 112.521 234.708 116.263 238.583L184.351 309.094C186.151 310.958 187.157 313.448 187.157 " +
        "316.04V443.681Z"

/** Правая скобка ядра-глаза. */
private const val BRACKET_RIGHT =
    "M218.843 443.681C218.843 452.615 229.665 457.066 235.95 450.716L403.107 281.832C404.96 279.959 406 277.431 " +
        "406 274.797V188.621C406 185.901 404.892 183.298 402.931 181.413L231.209 16.2815C224.855 10.1715 214.278 " +
        "14.6746 214.278 23.4896V142.505C214.278 145.097 215.284 147.587 217.084 149.452L289.737 224.69C293.479 " +
        "228.565 293.479 234.708 289.737 238.583L221.649 309.094C219.849 310.958 218.843 313.448 218.843 " +
        "316.04V443.681Z"
