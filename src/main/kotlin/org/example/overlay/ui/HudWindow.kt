package org.example.overlay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import org.example.overlay.app.AppState
import org.example.overlay.app.OverlayStatus
import org.example.overlay.app.ToolLogEntry
import org.example.overlay.app.nextHudHistoryIndex
import org.example.overlay.markdown.AnswerContent
import org.example.overlay.markdown.MdBlock
import org.example.overlay.platform.ScreenPlacement
import org.example.overlay.update.UpdateState
import java.awt.geom.Rectangle2D
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private const val POSITION_SAVE_DEBOUNCE_MS = 400L

/** Выдержка на уход курсора: за это время край панели успевает догнать указатель. */
private const val HOVER_EXIT_DEBOUNCE_MS = 250L

/** Короткие несовпадения независимых StateFlow не должны запускать реальное схлопывание. */
private const val COLLAPSE_REQUEST_DEBOUNCE_MS = 60L

/** Размер окна меняется дискретно: потоковые дельты не должны перезапускать геометрию на каждом символе. */
private const val WIDTH_STEP_DP = 48f

/** Высота растёт небольшими ступенями, но не дёргается от субпиксельных повторных замеров. */
private const val HEIGHT_STEP_DP = 8f


/** Тик пересчёта «х назад»: свежая синхронизация показывается с точностью до секунды. */
private const val SYNC_TICK_MS = 1_000L

/** «1 с. назад» → «1 мин. назад» → «1 ч. назад»: давность последней синхронизации. */
private fun formatAgo(then: Instant, now: Instant): String {
    val seconds = Duration.between(then, now).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "$seconds s ago"
        seconds < 60 * 60 -> "${seconds / 60} min ago"
        seconds < 24 * 60 * 60 -> "${seconds / 3600} h ago"
        else -> "${seconds / (24 * 3600)} d ago"
    }
}

internal fun hudActivityText(status: OverlayStatus): String? = when (status) {
    OverlayStatus.Ready -> null
    OverlayStatus.Listening -> "Listening"
    OverlayStatus.Thinking -> "Thinking"
    OverlayStatus.SearchingWeb -> "Searching web"
    OverlayStatus.Answering -> "Answering"
    OverlayStatus.Speaking -> "Speaking"
    OverlayStatus.Connecting -> "Connecting"
    is OverlayStatus.Reconnecting -> status.label
    OverlayStatus.Disconnected -> "Disconnected"
    is OverlayStatus.Failed -> "Error"
}

internal fun isAnimatedHudActivity(status: OverlayStatus): Boolean = when (status) {
    OverlayStatus.Listening,
    OverlayStatus.Thinking,
    OverlayStatus.SearchingWeb,
    OverlayStatus.Answering,
    OverlayStatus.Speaking,
    OverlayStatus.Connecting,
    is OverlayStatus.Reconnecting,
    -> true

    OverlayStatus.Ready,
    OverlayStatus.Disconnected,
    is OverlayStatus.Failed,
    -> false
}

internal fun shouldShowHudWindow(
    prepared: Boolean,
    expansionRequested: Boolean,
    expanded: Boolean,
    countingDown: Boolean,
    dismissed: Boolean,
): Boolean = prepared && (expansionRequested || expanded || countingDown || dismissed)

@Composable
private fun AnimatedHudActivityText(label: String) {
    var dotCount by remember(label) { mutableStateOf(1) }
    var textWidth by remember(label) { mutableStateOf(1) }
    LaunchedEffect(label) {
        while (true) {
            delay(350)
            dotCount = dotCount % 3 + 1
        }
    }

    val transition = rememberInfiniteTransition(label = "HUD activity shimmer")
    val shimmerProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "HUD activity shimmer progress",
    )
    val width = textWidth.toFloat()
    val shimmerCenter = (-0.4f + shimmerProgress * 1.8f) * width
    val shimmerRadius = width * 0.35f
    val brush = Brush.linearGradient(
        colors = listOf(OverlayColors.TextDim, OverlayColors.Text, OverlayColors.TextDim),
        start = Offset(shimmerCenter - shimmerRadius, 0f),
        end = Offset(shimmerCenter + shimmerRadius, 0f),
    )

    Text(
        text = label + ".".repeat(dotCount),
        style = TextStyle(brush = brush, fontSize = 11.sp),
        maxLines = 1,
        modifier = Modifier.onSizeChanged { textWidth = it.width.coerceAtLeast(1) },
    )
}

private fun quantizeUp(value: Float, step: Float, minimum: Float, maximum: Float): Float =
    (ceil(value / step) * step).coerceIn(minimum, maximum)

/**
 * Нативная область окна в локальных координатах. Координаты округляются наружу, чтобы
 * Windows-регион не отрезал полупикселя у анимируемого края панели.
 */
private fun panelShape(x: Float, y: Float, width: Float, height: Float): Rectangle2D.Double {
    val left = floor(x.toDouble())
    val top = floor(y.toDouble())
    val right = ceil((x + width).toDouble())
    val bottom = ceil((y + height).toDouble())
    return Rectangle2D.Double(left, top, right - left, bottom - top)
}

/** Четыре координаты меняются одним snapshot — между ними не бывает промежуточного кадра. */
internal data class PanelGeometry(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/** Сворачивание всегда идёт через горизонтальную полосу исходной ширины. */
internal fun collapseWaypoints(
    start: PanelGeometry,
    target: PanelGeometry,
): Pair<PanelGeometry, PanelGeometry> =
    PanelGeometry(start.x, target.y, start.width, target.height) to target

private val PanelGeometryVectorConverter = TwoWayConverter<PanelGeometry, AnimationVector4D>(
    convertToVector = { geometry ->
        AnimationVector4D(geometry.x, geometry.y, geometry.width, geometry.height)
    },
    convertFromVector = { vector ->
        PanelGeometry(vector.v1, vector.v2, vector.v3, vector.v4)
    },
)

/** Не больше трёх последних строк инструментов (§5.3). */
private const val MAX_HUD_TOOL_LINES = 3

/**
 * Высота шапки, она же область захвата кнопок. Вместе с полями панели даёт ровно
 * [HudSizing.COLLAPSED]: пустая полоса совпадает по высоте с пилюлей, и наведение курсора
 * раскрывает HUD только вширь.
 */
private val HEADER_HEIGHT = 20.dp

/** Отбивка под шапкой: блок давности синхронизации свисает ниже неё, лента начинается за ним. */
private val HEADER_BOTTOM_GAP = 10.dp

/** Зазор между кнопками шапки: история и шестерёнка не должны читаться одной группой. */
private val HEADER_BUTTON_GAP = 8.dp

/** Поля панели. Вынесены в константу: на них же считается высота окна. */
private val WINDOW_PADDING = 14.dp

/** Полоса относится к ответу и не заходит в шапку с запросом пользователя. */
private val VERTICAL_SCROLLBAR_TOP_INSET = 88.dp

/** Не показываем временное переполнение, пока окно подстраивается под потоковый ответ. */
private const val SCROLLBAR_SHOW_DELAY_MS = 150L

/** Скругление панели. Живёт на панели, а не на окне: окно вообще ничего не рисует. */
private val PANEL_CORNER = 12.dp

/** Слот статусной иконки в шапке: её место занимает плавающая иконка на краю панели. */
private val STATUS_ICON_SLOT = 28.dp

/** Появление ответа. Дольше — HUD кажется медленным: текст уже пришёл, а его ещё прячут. */
private const val ANSWER_FADE_MS = 220

/** Разворот панели: обе оси одним твином. */
private const val EXPAND_ANIM_MS = 180

/** Сворот двухфазный — сначала высота, потом ширина. Нарочно медленнее разворота. */
private const val COLLAPSE_PHASE_MS = 260

/** Смена самой иконки состояния: микрофон → спиннер → точки → кольцо. */
private const val ICON_FADE_MS = 150

/** Хром шапки не двигается никогда — только быстро гаснет и проявляется. */
private const val CHROME_FADE_MS = 300

/** Пауза перед гашением хрома при сворачивании: проявление задержки не имеет. */
private const val CHROME_FADE_OUT_DELAY_MS = 250

/** Давность синхронизации гаснет отдельно от прочего хрома: её нагоняет иконка статуса. */
private const val SYNC_FADE_OUT_MS = 350

/** Пауза перед гашением давности синхронизации: отсчитывается от начала сворачивания. */
private const val SYNC_FADE_OUT_DELAY_MS = 200

/** Финальная пилюля мягко растворяется перед тем, как нативное окно перестаёт перехватывать мышь. */
private const val HUD_HIDE_FADE_MS = 220

/**
 * Оверлей поверх игры (§5.3).
 *
 * Во время хода HUD разворачивается в полосу и остаётся видимым, пока на экране висит ответ.
 * После ответа запускается отсчёт (см. `AppState.collapseFraction`): кольцо тает, наведение
 * возвращает его на старт, по нулю HUD сворачивается и полностью скрывается.
 *
 * Переход пилюля ↔ полоса устроен так: дорожка уровня, шестерёнка и лента ответа стоят
 * на своих конечных местах у якорного края и только меняют прозрачность вслед за шириной
 * панели. Двигается одна иконка статуса — она прибита к раскрывающемуся краю панели,
 * при развороте уезжает от шестерёнки к началу полосы, при сворачивании возвращается,
 * наезжая на гаснущий хром. Ничто больше не перемещается ни на пиксель.
 *
 * Анимируется не окно, а панель внутри него. Менять размер AWT-окна по кадрам нельзя:
 * на Windows слоистое окно в этот момент показывает растянутый старый буфер — углы теряют
 * скругление, содержимое двоится. Поэтому нативное окно с запуска имеет максимальный размер
 * и больше не меняет bounds. Видимая панель движется только внутри него, а `Window.shape`
 * ограничивает отрисовку и hit-test самой панелью. Даже атомарный `setBounds` у layered-window
 * может на один кадр перенести старый Skia-буфер раньше, чем Compose нарисует новый.
 *
 * `focusable = false` — ключевое свойство: без него клик по HUD отбирает фокус у Destiny 2
 * и персонаж перестаёт слушаться WASD. Мышиные события в нефокусируемое окно AWT доставляет
 * по-прежнему, поэтому кнопки и ссылки работают; клавиатурного ввода в HUD нет — для него есть
 * консоль.
 */
@OptIn(FlowPreview::class, ExperimentalComposeUiApi::class)
@Composable
fun HudWindow(state: AppState) {
    val settings by state.settings.collectAsState()
    val status by state.status.collectAsState()
    val micLevel by state.micLevel.collectAsState()
    val userTranscript by state.userTranscript.collectAsState()
    val assistantTranscript by state.assistantTranscript.collectAsState()
    val toolLog by state.toolLog.collectAsState()
    val authUrl by state.authUrl.collectAsState()
    val voiceMessage by state.voiceMessage.collectAsState()
    val collapseFraction by state.collapseFraction.collectAsState()
    val dismissed by state.hudDismissed.collectAsState()
    val updateState by state.updateState.collectAsState()
    val profile by state.profile.collectAsState()
    val turnHistory by state.turnHistory.collectAsState()
    val turnHistoryIndex by state.turnHistoryIndex.collectAsState()

    // Сохранённая позиция описывает минимальную полосу. Нативный кожух даёт панели полный
    // запас в обе стороны: после drag якорь может смениться без перемещения самого AWT-окна.
    val initialPlacement = remember {
        val savedX = settings.hud.x
        val savedY = settings.hud.y
        val savedEnd = settings.hud.anchorEnd
        val savedBottom = settings.hud.anchorBottom
        if (savedX != null && savedY != null && savedEnd != null && savedBottom != null) {
            val savedPillX = savedX + if (savedEnd) HudSizing.MIN_WIDTH - HudSizing.COLLAPSED else 0f
            val savedPillY = savedY + if (savedBottom) HudSizing.MIN_HEIGHT - HudSizing.COLLAPSED else 0f
            val resolvedPill = ScreenPlacement.resolve(
                savedPillX,
                savedPillY,
                HudSizing.COLLAPSED,
                HudSizing.COLLAPSED,
            )
            val clampedPill = ScreenPlacement.clampToScreen(
                resolvedPill.x,
                resolvedPill.y,
                HudSizing.COLLAPSED,
                HudSizing.COLLAPSED,
            )
            val savedScreenStillExists = abs(resolvedPill.x - savedPillX) <= 0.5f &&
                abs(resolvedPill.y - savedPillY) <= 0.5f
            val restoredAnchors = if (savedScreenStillExists) {
                ScreenPlacement.Anchors(savedEnd, savedBottom)
            } else {
                ScreenPlacement.anchors(
                    clampedPill.x,
                    clampedPill.y,
                    HudSizing.COLLAPSED,
                    HudSizing.COLLAPSED,
                )
            }
            clampedPill to restoredAnchors
        } else {
            // Старые settings.json не содержат anchor: один раз восстанавливаем его по полосе.
            val resolvedMin = ScreenPlacement.resolve(
                savedX,
                savedY,
                HudSizing.MIN_WIDTH,
                HudSizing.MIN_HEIGHT,
            )
            val minPlacement = ScreenPlacement.clampToScreen(
                resolvedMin.x,
                resolvedMin.y,
                HudSizing.MIN_WIDTH,
                HudSizing.MIN_HEIGHT,
            )
            val inferred = ScreenPlacement.anchors(
                minPlacement.x,
                minPlacement.y,
                HudSizing.MIN_WIDTH,
                HudSizing.MIN_HEIGHT,
            )
            ScreenPlacement.Placement(
                x = minPlacement.x + if (inferred.end) {
                    HudSizing.MIN_WIDTH - HudSizing.COLLAPSED
                } else {
                    0f
                },
                y = minPlacement.y + if (inferred.bottom) {
                    HudSizing.MIN_HEIGHT - HudSizing.COLLAPSED
                } else {
                    0f
                },
            ) to inferred
        }
    }
    val initialPillX = initialPlacement.first.x
    val initialPillY = initialPlacement.first.y
    val initialAnchors = initialPlacement.second
    val stableShellWidth = HudSizing.MAX_WIDTH * 2 - HudSizing.COLLAPSED
    val stableShellHeight = HudSizing.MAX_HEIGHT * 2 - HudSizing.COLLAPSED
    val collapsedLocalX = HudSizing.MAX_WIDTH - HudSizing.COLLAPSED
    val collapsedLocalY = HudSizing.MAX_HEIGHT - HudSizing.COLLAPSED
    val stableShell = remember {
        ScreenPlacement.Placement(
            x = initialPillX - collapsedLocalX,
            y = initialPillY - collapsedLocalY,
        )
    }
    val windowState = rememberWindowState(
        width = stableShellWidth.dp,
        height = stableShellHeight.dp,
        position = WindowPosition(stableShell.x.dp, stableShell.y.dp),
    )
    var anchors by remember { mutableStateOf(initialAnchors) }
    var panelCapacity by remember {
        mutableStateOf(
            ScreenPlacement.anchoredCapacity(
                x = initialPillX,
                y = initialPillY,
                width = HudSizing.COLLAPSED,
                height = HudSizing.COLLAPSED,
                anchors = initialAnchors,
                minimumWidth = HudSizing.MIN_WIDTH,
                minimumHeight = HudSizing.MIN_HEIGHT,
                maximumWidth = HudSizing.MAX_WIDTH,
                maximumHeight = HudSizing.MAX_HEIGHT,
            ),
        )
    }
    var windowPrepared by remember { mutableStateOf(false) }

    // Листание истории: пока игрок смотрит прошлый ход, лента показывает его вместо живого.
    val viewedTurn = turnHistoryIndex?.let { turnHistory.getOrNull(it) }
    val answer = viewedTurn?.answer ?: assistantTranscript
    val shownUserText = viewedTurn?.question ?: userTranscript
    // Журнал инструментов относится только к живому ходу — к прошлым ответам он не про то.
    val shownToolLog = if (viewedTurn == null) toolLog else emptyList()
    val blocks = remember(answer) { AnswerContent.parse(answer) }

    // Листать можно всегда, пока в истории что-то есть: на живой ленте показан самый свежий
    // ход, поэтому позиция в счётчике — последняя.
    val historyPosition = turnHistoryIndex?.plus(1) ?: turnHistory.size
    val historyLabel = if (turnHistory.isEmpty()) null else "$historyPosition/${turnHistory.size}"
    val canGoBack = historyPosition > 1
    val canGoForward = nextHudHistoryIndex(turnHistoryIndex, turnHistory.size) != turnHistoryIndex

    // «х назад» пересчитывается по тику: без него надпись застыла бы между ответами.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(SYNC_TICK_MS)
            now = Instant.now()
        }
    }
    val syncedAgo = remember(profile, now) {
        profile?.bungieProfile?.inventorySyncedAt
            ?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() }
            ?.let { syncedAt -> formatAgo(syncedAt, now) }
    }
    val activityText = hudActivityText(status)

    // Наведение с выдержкой на уход. Панель меняет размер прямо под курсором — листание истории,
    // растущий ответ, — и её край проскакивает мимо указателя: Exit тут же сменяется Enter.
    // Без выдержки этот дребезг закрывал бы только что открытую страницу истории.
    var pointerOver by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { pointerOver }
            .debounce { over -> if (over) 0L else HOVER_EXIT_DEBOUNCE_MS }
            .collect { over ->
                if (over) {
                    // AppState сначала выбирает последний ход истории. Даём этому снимку
                    // дойти до UI и только следующим кадром начинаем разворачивать панель —
                    // иначе первый проход всегда строился под пустой ответ и тут же отменялся.
                    state.setHudHovered(true)
                    withFrameNanos { }
                    hovered = true
                } else {
                    hovered = false
                    state.setHudHovered(false)
                }
            }
    }

    // Ход держит HUD развёрнутым сам по себе: игрок в этот момент смотрит на игру, а не на курсор.
    val turnActive = when (status) {
        OverlayStatus.Listening, OverlayStatus.Thinking, OverlayStatus.Answering,
        OverlayStatus.Speaking, OverlayStatus.Connecting, is OverlayStatus.Reconnecting,
        -> true

        else -> false
    }
    val hasFeed = blocks.isNotEmpty() || shownUserText.isNotBlank() || shownToolLog.isNotEmpty() ||
        voiceMessage != null || authUrl != null

    // `dismissed` силой сворачивает HUD с ещё живой лентой; новый ход снимает запрет сразу.
    // Раскрываемся без задержки, а сворачивание подтверждаем короткой выдержкой. Статус, feed
    // и dismiss приходят отдельными StateFlow, и без неё их законная перестановка на один кадр
    // запускала обратную анимацию между Answering → Listening и при очистке истории.
    val expansionRequested = turnActive || (!dismissed && (hovered || hasFeed))
    var expanded by remember { mutableStateOf(expansionRequested) }
    LaunchedEffect(expansionRequested) {
        if (expansionRequested) {
            expanded = true
        } else {
            delay(COLLAPSE_REQUEST_DEBOUNCE_MS)
            expanded = false
        }
    }

    val windowRequested = shouldShowHudWindow(
        prepared = windowPrepared,
        expansionRequested = expansionRequested,
        expanded = expanded,
        countingDown = collapseFraction != null,
        dismissed = dismissed,
    )
    var windowVisible by remember { mutableStateOf(false) }
    var windowOpacityTarget by remember { mutableStateOf(0f) }
    LaunchedEffect(windowRequested) {
        if (windowRequested) {
            windowVisible = true
            windowOpacityTarget = 1f
        } else {
            windowOpacityTarget = 0f
            delay(HUD_HIDE_FADE_MS.toLong())
            windowVisible = false
        }
    }
    val windowOpacity by animateFloatAsState(
        targetValue = windowOpacityTarget,
        animationSpec = tween(if (windowOpacityTarget == 0f) HUD_HIDE_FADE_MS else 0),
        label = "HUD window opacity",
    )

    Window(
        onCloseRequest = { /* HUD не закрывается: выход — через трей */ },
        state = windowState,
        visible = windowVisible,
        title = "Destiny 2 Assistant",
        icon = painterResource("icons/app-icon.png"),
        undecorated = true,
        transparent = true,
        // Размер задаёт только HUD: ручное растягивание сломало бы локальные координаты панели
        // и рассчитанный shape стабильного кожуха.
        resizable = false,
        focusable = false,
        alwaysOnTop = true,
    ) {
        LaunchedEffect(Unit) {
            // Страховка на случай, если параметр окна будет переопределён платформой.
            window.focusableWindowState = false
            // Пока shape не задан, максимальный прозрачный кожух перехватывал бы мышь и мог
            // мелькнуть пустым прямоугольником. Window остаётся невидимым до этой операции.
            window.shape = panelShape(
                collapsedLocalX,
                collapsedLocalY,
                HudSizing.COLLAPSED,
                HudSizing.COLLAPSED,
            )
            windowPrepared = true
        }

        // Ширину считаем из разметки: содержимое подстроится под любую, поэтому спросить его
        // «сколько надо» нельзя — таблица одинаково согласится и на узкое окно, и на широкое.
        val measuredWidth = remember(blocks, settings.hud.fontSize) {
            HudSizing.width(blocks, settings.hud.fontSize)
        }

        // Сначала верстаем новый контент в его дискретной ширине, затем одним onMeasured
        // публикуем обе координаты геометрии. Так переключение истории не проходит через
        // промежуточные 300×48 со старой высотой и не разворачивается дважды.
        val contentKey = Triple(turnHistoryIndex, shownUserText, panelCapacity)
        val layoutWidth = quantizeUp(
            measuredWidth,
            WIDTH_STEP_DP,
            HudSizing.MIN_WIDTH,
            panelCapacity.width,
        )
        var geometryContentKey by remember { mutableStateOf(contentKey) }
        var contentWidth by remember { mutableStateOf(HudSizing.MIN_WIDTH) }
        var contentHeight by remember { mutableStateOf(0f) }

        LaunchedEffect(contentKey, hasFeed) {
            if (!hasFeed) {
                // В collapsed-состоянии ExpandedHud не композится и onMeasured не придёт.
                // Сбрасываем пустую геометрию заранее, чтобы следующий PTT не стартовал
                // к старой ширине/высоте и не делал overshoot перед первым замером.
                geometryContentKey = contentKey
                contentWidth = HudSizing.MIN_WIDTH
                contentHeight = 0f
            }
        }

        // Высоту, наоборот, меряем по-настоящему. Внутри `verticalScroll` максимальная высота
        // не ограничена окном, поэтому содержимое честно сообщает, сколько ему нужно.

        val targetWidth = if (expanded) minOf(contentWidth, panelCapacity.width) else HudSizing.COLLAPSED
        val targetHeight = if (expanded) {
            (contentHeight + WINDOW_PADDING.value * 2).coerceIn(HudSizing.MIN_HEIGHT, panelCapacity.height)
        } else {
            HudSizing.COLLAPSED
        }

        // Панель живёт в локальных координатах стабильного кожуха. Перетаскивание нативного
        // окна автоматически переносит её вместе с ним и не требует пересчёта Animatable.
        val panel = remember {
            Animatable(
                initialValue = PanelGeometry(
                    x = collapsedLocalX,
                    y = collapsedLocalY,
                    width = HudSizing.COLLAPSED,
                    height = HudSizing.COLLAPSED,
                ),
                typeConverter = PanelGeometryVectorConverter,
            )
        }
        var animating by remember { mutableStateOf(false) }
        var showPill by remember { mutableStateOf(true) }
        var feedRevealed by remember { mutableStateOf(false) }

        // Пока видна пилюля, drag может перенести её на другую половину или другой монитор.
        // Симметричный кожух позволяет сменить направление раскрытия без native setLocation.
        LaunchedEffect(windowState) {
            var settledWindowX: Float? = null
            var settledWindowY: Float? = null
            snapshotFlow { Triple(windowState.position, animating, showPill) }
                .collect { (position, isAnimating, isPill) ->
                    val absolute = position as? WindowPosition.Absolute ?: return@collect
                    if (settledWindowX == null || settledWindowY == null) {
                        // Первая emission — восстановленная позиция. Явно сохранённый anchor
                        // нельзя тут же переинферить по pill: около центра результат неоднозначен.
                        settledWindowX = absolute.x.value
                        settledWindowY = absolute.y.value
                        return@collect
                    }
                    if (isAnimating || !isPill) return@collect
                    if (abs(absolute.x.value - settledWindowX!!) <= 0.5f &&
                        abs(absolute.y.value - settledWindowY!!) <= 0.5f
                    ) {
                        return@collect
                    }
                    settledWindowX = absolute.x.value
                    settledWindowY = absolute.y.value
                    val pillX = absolute.x.value + collapsedLocalX
                    val pillY = absolute.y.value + collapsedLocalY
                    val nextAnchors = ScreenPlacement.anchors(
                        pillX,
                        pillY,
                        HudSizing.COLLAPSED,
                        HudSizing.COLLAPSED,
                    )
                    anchors = nextAnchors
                    panelCapacity = ScreenPlacement.anchoredCapacity(
                        x = pillX,
                        y = pillY,
                        width = HudSizing.COLLAPSED,
                        height = HudSizing.COLLAPSED,
                        anchors = nextAnchors,
                        minimumWidth = HudSizing.MIN_WIDTH,
                        minimumHeight = HudSizing.MIN_HEIGHT,
                        maximumWidth = HudSizing.MAX_WIDTH,
                        maximumHeight = HudSizing.MAX_HEIGHT,
                    )
                }
        }

        // Сохраняем положение видимой панели, а не top-left симметричного прозрачного кожуха.
        // Якорь пишется явно: около центра экрана одних x/y недостаточно, чтобы восстановить его.
        LaunchedEffect(windowState) {
            snapshotFlow { Triple(windowState.position, animating, anchors to panel.value) }
                .debounce(POSITION_SAVE_DEBOUNCE_MS)
                .collect { (position, isAnimating, anchoredPanel) ->
                    if (isAnimating) return@collect
                    val absolute = position as? WindowPosition.Absolute ?: return@collect
                    val currentAnchors = anchoredPanel.first
                    val currentPanel = anchoredPanel.second
                    val panelX = absolute.x.value + currentPanel.x
                    val panelY = absolute.y.value + currentPanel.y
                    val savedX = panelX +
                        if (currentAnchors.end) currentPanel.width - HudSizing.MIN_WIDTH else 0f
                    val savedY = panelY +
                        if (currentAnchors.bottom) currentPanel.height - HudSizing.MIN_HEIGHT else 0f
                    state.updateSettings { current ->
                        current.copy(
                            hud = current.hud.copy(
                                x = savedX,
                                y = savedY,
                                anchorEnd = currentAnchors.end,
                                anchorBottom = currentAnchors.bottom,
                            ),
                        )
                    }
                }
        }

        LaunchedEffect(expanded, expansionRequested, targetWidth, targetHeight, anchors) {
            // Во время debounce содержимое уже может исчезнуть, а expanded ещё остаётся true.
            // Не запускаем обычный resize обеих осей: подтверждённое сворачивание само пройдёт
            // через горизонтальную полосу и затем квадрат.
            if (expanded && !expansionRequested) return@LaunchedEffect

            // Если уже развёрнутую панель перетащили к другому краю, следующий реальный рост
            // сначала уточняет доступное место. Drag не прерывается анимацией посреди жеста,
            // но новая строка ответа уже не уходит за границу экрана.
            if (expanded && !showPill && !animating) {
                val absolute = windowState.position as? WindowPosition.Absolute
                val current = panel.value
                if (absolute != null) {
                    val currentCapacity = ScreenPlacement.anchoredCapacity(
                        x = absolute.x.value + current.x,
                        y = absolute.y.value + current.y,
                        width = current.width,
                        height = current.height,
                        anchors = anchors,
                        minimumWidth = HudSizing.MIN_WIDTH,
                        minimumHeight = HudSizing.MIN_HEIGHT,
                        maximumWidth = HudSizing.MAX_WIDTH,
                        maximumHeight = HudSizing.MAX_HEIGHT,
                    )
                    if (currentCapacity != panelCapacity) {
                        panelCapacity = currentCapacity
                        return@LaunchedEffect
                    }
                }
            }

            val start = panel.value
            val targetX = if (anchors.end) {
                collapsedLocalX + HudSizing.COLLAPSED - targetWidth
            } else {
                collapsedLocalX
            }
            val targetY = if (anchors.bottom) {
                collapsedLocalY + HudSizing.COLLAPSED - targetHeight
            } else {
                collapsedLocalY
            }
            val target = PanelGeometry(targetX, targetY, targetWidth, targetHeight)

            if (!animating &&
                abs(start.x - target.x) <= 0.5f && abs(start.y - target.y) <= 0.5f &&
                abs(start.width - target.width) <= 0.5f && abs(start.height - target.height) <= 0.5f
            ) {
                if (expanded) feedRevealed = true
                return@LaunchedEffect
            }

            val wasAnimating = animating
            val openingFromPill = expanded && !wasAnimating && showPill &&
                start.width <= HudSizing.COLLAPSED + 0.5f

            panel.snapTo(start)
            animating = true

            // Один Animatable публикует x/y/width/height атомарно. Shape обновляется в том же
            // animation frame, поэтому hit-region следует текущей панели, а не будущему hull.
            suspend fun animatePanel(targetGeometry: PanelGeometry, durationMillis: Int) {
                panel.animateTo(
                    targetValue = targetGeometry,
                    animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
                ) {
                    val current = value
                    window.shape = panelShape(current.x, current.y, current.width, current.height)
                }
            }
            window.shape = panelShape(start.x, start.y, start.width, start.height)

            // Открытие из покоя — зеркало сворачивания: сначала ширина, потом высота. Считаем
            // по стартовой высоте, а не по «сейчас пилюля»: содержимое приходит кадром позже
            // наведения, и разворот успевает разбиться на два прохода — сперва панель тянется
            // вширь пустой полосой, а высоту набирает уже следующим. Оба прохода стартуют
            // с высоты покоя, поэтому оба идут фазами и вместе читаются одним движением.
            if (openingFromPill) {
                feedRevealed = false
                showPill = false
                animatePanel(
                    targetGeometry = PanelGeometry(target.x, start.y, target.width, start.height),
                    durationMillis = COLLAPSE_PHASE_MS,
                )
                feedRevealed = true
                if (abs(start.height - target.height) > 0.5f) {
                    animatePanel(target, COLLAPSE_PHASE_MS)
                }
            } else if (expanded) {
                // Панель уже развёрнута и лишь меняет размер под ответ: обе оси одним твином,
                // иначе текст, который дописывается на ходу, догонял бы рамку рывками.
                showPill = false
                animatePanel(target, EXPAND_ANIM_MS)
                feedRevealed = true
            } else {
                // Сворачивание двумя фазами: сначала высота (снизу вверх при верхнем якоре),
                // затем ширина (слева направо при правом). Ось Y едет вместе с высотой,
                // ось X — вместе с шириной, чтобы якорный край не дрожал.
                feedRevealed = false
                val (stripTarget, pillTarget) = collapseWaypoints(start, target)
                animatePanel(stripTarget, COLLAPSE_PHASE_MS)
                animatePanel(pillTarget, COLLAPSE_PHASE_MS)
                showPill = true
            }

            // После перехода hit-region снова точно совпадает с панелью. Нативный кожух не
            // меняется, поэтому потоковый рост текста не переносит старый буфер между кадрами.
            window.shape = panelShape(targetX, targetY, targetWidth, targetHeight)
            animating = false
        }

        OverlayTheme {
            // Шестерёнка и дорожка не перемещаются ни на пиксель: они стоят на конечных
            // местах и быстро гаснут в самом начале сворачивания — панель схлопывается уже
            // по пустому месту. При развороте так же быстро проявляются из нуля.
            // Гашение стартует с задержкой: хром ещё висит, пока схлопывается высота,
            // и тает ближе к тому моменту, когда на него поедет иконка.
            // Лента показывается, только когда панель набрала свою ширину. Содержимое всегда
            // свёрстано в целевую ширину и прижато к якорному краю, поэтому у более узкой панели
            // оно обрезано с другого края — читался кусок фразы. Высоту это не касается: она
            // открывает ленту сверху вниз, как штора, и дописываемый ответ не мигает.
            val feedAlpha by animateFloatAsState(
                targetValue = if (feedRevealed) 1f else 0f,
                animationSpec = tween(ANSWER_FADE_MS),
                label = "feed-alpha",
            )

            val chromeAlpha by animateFloatAsState(
                targetValue = if (expanded) 1f else 0f,
                animationSpec = if (expanded) {
                    tween(CHROME_FADE_MS)
                } else {
                    tween(CHROME_FADE_MS, delayMillis = CHROME_FADE_OUT_DELAY_MS)
                },
                label = "chrome-alpha",
            )

            // Давность синхронизации гаснет раньше остального хрома и без задержки: она стоит
            // вплотную к иконке статуса, и при сворачивании иконка наезжала бы на ещё живой текст.
            val syncAlpha by animateFloatAsState(
                targetValue = if (expanded) 1f else 0f,
                animationSpec = if (expanded) {
                    tween(CHROME_FADE_MS)
                } else {
                    tween(SYNC_FADE_OUT_MS, delayMillis = SYNC_FADE_OUT_DELAY_MS)
                },
                label = "sync-alpha",
            )

            // Корень окна не рисует ничего: всё видимое — панель со скруглением и клипом.
            Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = windowOpacity }) {
                // Во время анимации используем реальные экранные координаты панели относительно
                // уже применённого AWT-кожуха. Одного align недостаточно: clamp у края экрана
                // способен сдвинуть сам якорный край, и в конце получался боковой скачок.
                val panelGeometry = panel.value
                val panelModifier = Modifier
                    .offset(panelGeometry.x.dp, panelGeometry.y.dp)
                    .size(panelGeometry.width.dp, panelGeometry.height.dp)

                Box(
                    modifier = panelModifier
                        .clip(RoundedCornerShape(PANEL_CORNER))
                        .background(OverlayColors.Background.copy(alpha = settings.hud.opacity.coerceIn(0.3f, 1f)))
                        .onPointerEvent(PointerEventType.Enter) { pointerOver = true }
                        .onPointerEvent(PointerEventType.Exit) { pointerOver = false },
                ) {
                    if (expanded || animating) {
                        // Контент верстается сразу в целевую ширину и прижат к якорному краю:
                        // панель лишь приоткрывает или обрезает его, не перевёрстывая таблицу
                        // каждый кадр. `wrapContentWidth(unbounded)` обязателен: при обычном
                        // переполнении Compose игнорирует выравнивание и центрирует содержимое —
                        // из-за этого хром раньше «ехал» вслед за сжимающейся панелью.
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .wrapContentWidth(
                                    align = if (anchors.end) Alignment.End else Alignment.Start,
                                    unbounded = true,
                                ),
                        ) {
                            Box(modifier = Modifier.requiredWidth(layoutWidth.dp).fillMaxHeight()) {
                                ExpandedHud(
                                    state = state,
                                    chromeAlpha = chromeAlpha,
                                    blocks = blocks,
                                    userTranscript = shownUserText,
                                    toolLog = shownToolLog,
                                    voiceMessage = voiceMessage,
                                    authUrl = authUrl,
                                    fontSize = settings.hud.fontSize,
                                    feedAlpha = feedAlpha,
                                    canGoBack = canGoBack,
                                    canGoForward = canGoForward,
                                    historyLabel = historyLabel,
                                    draggable = !animating,
                                    onMeasured = { measured ->
                                        val total = quantizeUp(
                                            measured + WINDOW_PADDING.value * 2,
                                            HEIGHT_STEP_DP,
                                            HudSizing.MIN_HEIGHT,
                                            panelCapacity.height,
                                        )
                                        val next = total - WINDOW_PADDING.value * 2
                                        when {
                                            geometryContentKey != contentKey -> {
                                                geometryContentKey = contentKey
                                                contentWidth = layoutWidth
                                                contentHeight = next
                                            }

                                            layoutWidth > contentWidth -> {
                                                // Ширина изменила переносы: публикуем новую
                                                // честную высоту вместе с ней, даже если она меньше.
                                                contentWidth = layoutWidth
                                                contentHeight = next
                                            }

                                            next > contentHeight -> contentHeight = next
                                        }
                                    },
                                )
                            }
                        }
                    }

                    // Единственное, что двигается при развороте: иконка статуса прибита
                    // к раскрывающемуся краю панели. В пилюле она — сама пилюля, в полосе
                    // встаёт точно в свой слот в шапке. За неё же HUD таскают по экрану.
                    // TopStart при любом якоре: шапка всегда сверху, а при нижнем якоре
                    // подвижен как раз верхний край — иконка едет вместе с ним.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .size(HudSizing.COLLAPSED.dp),
                    ) {
                        val iconContent: @Composable () -> Unit = {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Crossfade(
                                    targetState = collapseFraction != null || dismissed,
                                    animationSpec = tween(ICON_FADE_MS),
                                ) { counting ->
                                    if (counting) {
                                        // Пока уходящая иконка отсчёта дотаивает в Crossfade,
                                        // fraction уже null: держим её полностью потухшей, иначе
                                        // ядро вспыхивает на пару кадров перед сворачиванием.
                                        GhostCountdownIcon(collapseFraction ?: 0f, Modifier.size(26.dp))
                                    } else {
                                        // Статус не входит в identity Crossfade: иначе каждый
                                        // Answering → Ready создавал второй motor иконки поверх первого.
                                        HudStatusIcon(status, micLevel, Modifier.size(26.dp))
                                    }
                                }
                            }
                        }
                        if (animating) {
                            iconContent()
                        } else {
                            WindowDraggableArea(modifier = Modifier.fillMaxSize()) { iconContent() }
                        }

                        // Бейдж обновления: пока консоль закрыта, пилюля — единственное место,
                        // где игрок вообще может узнать про новую версию. Ставится/качается —
                        // само обновление живёт в консоли, точка лишь зовёт её открыть.
                        if (showPill && updateState !is UpdateState.Hidden) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(7.dp)
                                    .size(7.dp)
                                    .background(OverlayColors.Accent, CircleShape),
                            )
                        }
                    }

                    // Давность синхронизации — рядом с иконкой и, как и она, на уровне панели:
                    // внутри ленты блок обрезал бы контейнер прокрутки по высоте шапки.
                    // По вертикали центрируется на ту же ось, что и иконка-призрак.
                    (activityText ?: syncedAgo)?.let { headerText ->
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                // Отступ слева тот же, что был у блока внутри шапки:
                                // поля панели, слот иконки и зазор от призрака.
                                .padding(start = WINDOW_PADDING + STATUS_ICON_SLOT + 12.dp)
                                .height(HudSizing.COLLAPSED.dp)
                                .graphicsLayer { alpha = syncAlpha },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (activityText == null) {
                                SyncIcon(OverlayColors.TextDim, Modifier.size(width = 11.dp, height = 13.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            if (activityText != null && isAnimatedHudActivity(status)) {
                                AnimatedHudActivityText(headerText)
                            } else {
                                Text(
                                    text = headerText,
                                    color = OverlayColors.TextDim,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Развёрнутый HUD: шапка, лента хода и ответ. [onMeasured] отдаёт честную высоту содержимого. */
@Composable
private fun WindowScope.ExpandedHud(
    state: AppState,
    chromeAlpha: Float,
    blocks: List<MdBlock>,
    userTranscript: String,
    toolLog: List<ToolLogEntry>,
    voiceMessage: String?,
    authUrl: String?,
    fontSize: Float,
    feedAlpha: Float,
    canGoBack: Boolean,
    canGoForward: Boolean,
    historyLabel: String?,
    draggable: Boolean,
    onMeasured: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    var showScrollbar by remember(scrollState) { mutableStateOf(false) }

    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.maxValue > 0 }.collectLatest { hasOverflow ->
            if (hasOverflow) delay(SCROLLBAR_SHOW_DELAY_MS)
            showScrollbar = hasOverflow
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(WINDOW_PADDING)
            // Прокрутка нужна не столько игроку, сколько замеру: она снимает с
            // содержимого потолок высоты. Заодно длинный ответ можно домотать.
                .verticalScroll(scrollState),
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    onMeasured(with(density) { size.height.toDp().value })
                },
        ) {
            HudHeader(
                chromeAlpha = chromeAlpha,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                historyLabel = historyLabel,
                draggable = draggable,
                onPreviousTurn = { state.showPreviousTurn() },
                onNextTurn = { state.showNextTurn() },
                onOpenConsole = { state.openConsole() },
            )

            // Лента проявляется отдельно от шапки: пока панель добирает ширину, показывать её
            // нечестно — она свёрстана в целевую ширину и обрезана краем панели. Замер высоты
            // прозрачность не трогает, поэтому панель по-прежнему знает, сколько ей открыться.
            Column(modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = feedAlpha }) {
                // Запас под давностью синхронизации: она нарисована на уровне панели и заходит
                // ниже шапки, поэтому лента начинается не вплотную к её нижней строке.
                // Только когда ленте есть что показать: иначе пустой HUD станет выше пилюли,
                // и наведение курсора растило бы его не только по ширине, но и по высоте.
                val hasFeedBelow = voiceMessage != null || authUrl != null ||
                    userTranscript.isNotBlank() || toolLog.isNotEmpty() || blocks.isNotEmpty()
                if (hasFeedBelow) {
                    Spacer(Modifier.height(HEADER_BOTTOM_GAP))
                }

                voiceMessage?.let { message ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = message,
                        color = OverlayColors.Error,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                authUrl?.let { url ->
                    Spacer(Modifier.height(6.dp))
                    AuthBanner(
                        url = url,
                        onOpen = { state.openAuthorizationLink() },
                        onDismiss = { state.dismissAuthorizationPrompt() },
                    )
                }

                if (userTranscript.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = userTranscript,
                        color = OverlayColors.TextDim,
                        fontSize = (fontSize - 1).sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Инструменты — над ответом: снизу они отжимали его и обрезали таблицу.
                // Хронология при этом сохраняется: спросил → сходил в инструмент → ответил.
                if (toolLog.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    toolLog.takeLast(MAX_HUD_TOOL_LINES).forEach { entry -> ToolLine(entry) }
                }

                // Появление через fade: в начале хода блоки пустеют, с первым куском
                // ответ мягко проявляется вместо рывка. Дальше текст дописывается уже
                // внутри видимого блока, так что анимация не мигает на каждой дельте.
                AnimatedVisibility(
                    visible = blocks.isNotEmpty(),
                    enter = fadeIn(tween(ANSWER_FADE_MS)),
                    // Старый Markdown не должен ещё 110 мс участвовать в onSizeChanged после
                    // начала нового хода: внешний feedAlpha уже отвечает за плавное исчезновение.
                    exit = ExitTransition.None,
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(8.dp))
                        MarkdownView(
                            blocks = blocks,
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = fontSize.sp,
                        )
                    }
                }
            }
        }
        }

        if (showScrollbar) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(
                        start = 0.dp,
                        top = VERTICAL_SCROLLBAR_TOP_INSET,
                        end = 4.dp,
                        bottom = WINDOW_PADDING,
                    ),
                style = ScrollbarStyle(
                    minimalHeight = 24.dp,
                    thickness = 3.dp,
                    shape = RoundedCornerShape(2.dp),
                    hoverDurationMillis = 120,
                    unhoverColor = OverlayColors.TextDim.copy(alpha = 0.35f),
                    hoverColor = OverlayColors.Accent.copy(alpha = 0.85f),
                ),
            )
        }
    }
}

/**
 * Шапка развёрнутого HUD: слот под плавающую иконку статуса, дорожка уровня микрофона
 * и шестерёнка в консоль. Сама иконка живёт вне шапки — на краю панели (см. [HudWindow]) —
 * и в развёрнутом состоянии встаёт ровно в свой слот. Тянуть окно можно за дорожку —
 * ниже живут текст и ссылки, которые нужно выделять и нажимать.
 */
@Composable
private fun WindowScope.HudHeader(
    chromeAlpha: Float,
    canGoBack: Boolean,
    canGoForward: Boolean,
    historyLabel: String?,
    draggable: Boolean,
    onPreviousTurn: () -> Unit,
    onNextTurn: () -> Unit,
    onOpenConsole: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Уровень микрофона полосой здесь не рисуем: во время записи им дышит ядро
        // иконки-призрака. Пустая область осталась — за неё HUD таскают по экрану;
        // давность синхронизации рисуется поверх неё на уровне панели (см. [HudWindow]),
        // чтобы прокрутка ленты не обрезала её по высоте шапки.
        val dragContent: @Composable () -> Unit = {
            Row(
                modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(STATUS_ICON_SLOT))
                Spacer(Modifier.weight(1f))
            }
        }
        if (draggable) {
            WindowDraggableArea(modifier = Modifier.weight(1f)) { dragContent() }
        } else {
            Box(modifier = Modifier.weight(1f)) { dragContent() }
        }

        // Прошлые ответы: стрелки со счётчиком, пока в истории есть что листать.
        historyLabel?.let { label ->
            Box(modifier = Modifier.size(HEADER_HEIGHT), contentAlignment = Alignment.Center) {
                if (canGoBack) {
                    HeaderButton(chromeAlpha, onPreviousTurn) {
                        ChevronIcon(OverlayColors.TextDim, pointsRight = false, Modifier.size(14.dp))
                    }
                }
            }
            Text(
                text = label,
                color = OverlayColors.TextDim,
                fontSize = 10.sp,
                modifier = Modifier.graphicsLayer { alpha = chromeAlpha },
            )
            Box(modifier = Modifier.size(HEADER_HEIGHT), contentAlignment = Alignment.Center) {
                if (canGoForward) {
                    HeaderButton(chromeAlpha, onNextTurn) {
                        ChevronIcon(OverlayColors.TextDim, pointsRight = true, Modifier.size(14.dp))
                    }
                }
            }
        }

        Spacer(Modifier.width(HEADER_BUTTON_GAP))

        HeaderButton(chromeAlpha, onOpenConsole) {
            // Под стать иконке-призраку (26 dp): шестерёнка компактнее по силуэту,
            // поэтому визуально ровня ему чуть меньшим размером.
            GearIcon(OverlayColors.TextDim, Modifier.size(20.dp))
        }
    }
}

/** Кнопка шапки: квадрат высоты шапки, гаснет и проявляется вместе с остальным хромом. */
@Composable
private fun HeaderButton(chromeAlpha: Float, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(HEADER_HEIGHT)
            .graphicsLayer { alpha = chromeAlpha }
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun AuthBanner(url: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Column {
        Text(
            text = "Open the link and return to the game — the connection will update automatically.",
            color = OverlayColors.Warn,
            fontSize = 12.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpen) {
                Text("Open in browser", color = OverlayColors.Accent, fontSize = 12.sp)
            }
            TextButton(onClick = onDismiss) {
                Text("Hide", color = OverlayColors.TextDim, fontSize = 12.sp)
            }
        }
        // Ссылка мелким шрифтом — на случай, если браузер не открылся (§5.3).
        Text(url, color = OverlayColors.TextDim, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ToolLine(entry: ToolLogEntry) {
    // Поиск — особая строка: после ответа игрок видит, что данные пришли из интернета.
    if (entry.isWebSearch) {
        Text(
            text = "Searching web…",
            color = OverlayColors.TextDim,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    Text(
        text = "${entry.name} → ${entry.status} (${entry.durationMs} ms)",
        color = if (entry.isFailure) OverlayColors.Error else OverlayColors.TextDim,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
