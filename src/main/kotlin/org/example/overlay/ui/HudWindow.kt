package org.example.overlay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.example.overlay.app.AppState
import org.example.overlay.app.OverlayStatus
import org.example.overlay.app.ToolLogEntry
import org.example.overlay.markdown.AnswerContent
import org.example.overlay.markdown.MdBlock
import org.example.overlay.platform.ScreenPlacement
import org.example.overlay.update.UpdateState
import java.awt.geom.Rectangle2D
import kotlin.math.max
import kotlin.math.min

private const val POSITION_SAVE_DEBOUNCE_MS = 400L

/** Не больше трёх последних строк инструментов (§5.3). */
private const val MAX_HUD_TOOL_LINES = 3

/** Высота шапки: она же область захвата шестерёнки, поэтому не меньше пальца на трекпаде. */
private val HEADER_HEIGHT = 22.dp

/** Поля панели. Вынесены в константу: на них же считается высота окна. */
private val WINDOW_PADDING = 14.dp

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

/** Шестерёнка и дорожка не двигаются никогда — только быстро гаснут и проявляются. */
private const val CHROME_FADE_MS = 420

/** Пауза перед гашением хрома при сворачивании: проявление задержки не имеет. */
private const val CHROME_FADE_OUT_DELAY_MS = 250

/** Дорожка уровня: сглаживание между стомиллисекундными замерами микрофона. */
private const val LEVEL_ANIM_MS = 100

/**
 * Оверлей поверх игры (§5.3).
 *
 * В покое HUD — пилюля с одной иконкой микрофона. Разворачивается в полосу, когда игрок навёл
 * курсор или идёт ход, и остаётся развёрнутым, пока на экране висит прошлый ответ. После ответа
 * запускается отсчёт (см. `AppState.collapseFraction`): кольцо тает, наведение возвращает его
 * на старт, по нулю HUD спадает обратно в пилюлю — сначала высотой, потом шириной.
 *
 * Переход пилюля ↔ полоса устроен так: дорожка уровня, шестерёнка и лента ответа стоят
 * на своих конечных местах у якорного края и только меняют прозрачность вслед за шириной
 * панели. Двигается одна иконка статуса — она прибита к раскрывающемуся краю панели,
 * при развороте уезжает от шестерёнки к началу полосы, при сворачивании возвращается,
 * наезжая на гаснущий хром. Ничто больше не перемещается ни на пиксель.
 *
 * Анимируется не окно, а панель внутри него. Менять размер AWT-окна по кадрам нельзя:
 * на Windows слоистое окно в этот момент показывает растянутый старый буфер — углы теряют
 * скругление, содержимое двоится. Поэтому окно в покое всегда не меньше полосы
 * ([HudSizing.MIN_WIDTH] × [HudSizing.MIN_HEIGHT]): пилюля — маленькая панель в его якорном
 * углу, и самый частый переход «пилюля ↔ полоса» не трогает границы окна вовсе. Когда ответу
 * нужно больше места, окно одним скачком становится «кожухом», покрывающим панель в начале
 * и в конце пути, панель плывёт внутри композной анимацией, и вторым скачком кожух ужимается.
 * Оба скачка не двигают видимых пикселей — панель в эти моменты совпадает с краем кожуха.
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

    // Окно сразу размером с полосу: пилюля живёт в его углу, а не в отдельном мелком окне.
    val placement = remember {
        ScreenPlacement.resolve(settings.hud.x, settings.hud.y, HudSizing.MIN_WIDTH, HudSizing.MIN_HEIGHT)
    }
    val windowState = rememberWindowState(
        width = HudSizing.MIN_WIDTH.dp,
        height = HudSizing.MIN_HEIGHT.dp,
        position = WindowPosition(placement.x.dp, placement.y.dp),
    )

    val answer = assistantTranscript
    val blocks = remember(answer) { AnswerContent.parse(answer) }

    var hovered by remember { mutableStateOf(false) }

    // Ход держит HUD развёрнутым сам по себе: игрок в этот момент смотрит на игру, а не на курсор.
    val turnActive = when (status) {
        OverlayStatus.Listening, OverlayStatus.Thinking, OverlayStatus.Answering,
        OverlayStatus.Speaking, OverlayStatus.Connecting, is OverlayStatus.Reconnecting,
        -> true

        else -> false
    }
    val hasFeed = blocks.isNotEmpty() || userTranscript.isNotBlank() || toolLog.isNotEmpty() ||
        voiceMessage != null || authUrl != null

    // `dismissed` силой сворачивает HUD с ещё живой лентой; новый ход снимает запрет сразу.
    val expanded = turnActive || (!dismissed && (hovered || hasFeed))

    Window(
        onCloseRequest = { /* HUD не закрывается: выход — через трей */ },
        state = windowState,
        title = "mcp-destiny2 overlay",
        undecorated = true,
        transparent = true,
        // Размер задаёт только сам HUD: ручное растягивание за края ломало бы механику
        // кожуха, где границы окна обязаны совпадать с расчётными.
        resizable = false,
        focusable = false,
        alwaysOnTop = true,
    ) {
        LaunchedEffect(Unit) {
            // Страховка на случай, если параметр окна будет переопределён платформой.
            window.focusableWindowState = false
        }

        LaunchedEffect(windowState) {
            snapshotFlow { windowState.position }
                .filterIsInstance<WindowPosition.Absolute>()
                .debounce(POSITION_SAVE_DEBOUNCE_MS)
                .collect { position ->
                    state.updateSettings { current ->
                        current.copy(hud = current.hud.copy(x = position.x.value, y = position.y.value))
                    }
                }
        }

        // Ширину считаем из разметки: содержимое подстроится под любую, поэтому спросить его
        // «сколько надо» нельзя — таблица одинаково согласится и на узкое окно, и на широкое.
        val contentWidth = remember(blocks, settings.hud.fontSize) { HudSizing.width(blocks, settings.hud.fontSize) }

        // Высоту, наоборот, меряем по-настоящему. Внутри `verticalScroll` максимальная высота
        // не ограничена окном, поэтому содержимое честно сообщает, сколько ему нужно.
        var contentHeight by remember { mutableStateOf(HudSizing.MIN_HEIGHT) }

        // Замер прошлого ответа протухает вместе с лентой: без сброса следующее наведение
        // на миг развернуло бы панель во всю высоту старой таблицы.
        LaunchedEffect(hasFeed) {
            if (!hasFeed) contentHeight = 0f
        }

        val targetWidth = if (expanded) contentWidth else HudSizing.COLLAPSED
        val targetHeight = if (expanded) {
            (contentHeight + WINDOW_PADDING.value * 2).coerceIn(HudSizing.MIN_HEIGHT, HudSizing.MAX_HEIGHT)
        } else {
            HudSizing.COLLAPSED
        }

        // Панель в экранных координатах. В покое выводится из окна и якоря; во время анимации
        // окно стоит кожухом, а панель движется внутри по этим значениям.
        val panelX = remember { Animatable(placement.x) }
        val panelY = remember { Animatable(placement.y) }
        val panelW = remember { Animatable(HudSizing.COLLAPSED) }
        val panelH = remember { Animatable(HudSizing.COLLAPSED) }
        var animating by remember { mutableStateOf(false) }
        var showPill by remember { mutableStateOf(true) }
        var anchors by remember {
            mutableStateOf(ScreenPlacement.anchors(placement.x, placement.y, HudSizing.MIN_WIDTH, HudSizing.MIN_HEIGHT))
        }

        LaunchedEffect(expanded, targetWidth, targetHeight) {
            val position = windowState.position as? WindowPosition.Absolute ?: return@LaunchedEffect
            val size = windowState.size

            // Откуда стартуем. В покое пилюля стоит в якорном углу окна, развёрнутая панель
            // совпадает с окном; посреди прерванной анимации — текущие значения панели.
            val startX: Float
            val startY: Float
            val startW: Float
            val startH: Float
            when {
                animating -> {
                    startX = panelX.value
                    startY = panelY.value
                    startW = panelW.value
                    startH = panelH.value
                }

                showPill -> {
                    startW = HudSizing.COLLAPSED
                    startH = HudSizing.COLLAPSED
                    startX = if (anchors.end) position.x.value + size.width.value - startW else position.x.value
                    startY = if (anchors.bottom) position.y.value + size.height.value - startH else position.y.value
                }

                else -> {
                    startX = position.x.value
                    startY = position.y.value
                    startW = size.width.value
                    startH = size.height.value
                }
            }
            if (!animating && startW == targetWidth && startH == targetHeight) return@LaunchedEffect

            // Якорь считается по панели до пересчёта цели: так старт и цель прижаты к одному краю.
            anchors = ScreenPlacement.anchors(startX, startY, startW, startH)
            val target = ScreenPlacement.resize(startX, startY, startW, startH, targetWidth, targetHeight)

            // Кожух: прямоугольник, покрывающий панель в начале и в конце пути.
            val hullX = min(startX, target.x)
            val hullY = min(startY, target.y)
            val hullW = max(startX + startW, target.x + targetWidth) - hullX
            val hullH = max(startY + startH, target.y + targetHeight) - hullY

            panelX.snapTo(startX)
            panelY.snapTo(startY)
            panelW.snapTo(startW)
            panelH.snapTo(startH)
            animating = true

            val boundsChange = hullX != position.x.value || hullY != position.y.value ||
                hullW != size.width.value || hullH != size.height.value
            if (boundsChange) {
                windowState.position = WindowPosition(hullX.dp, hullY.dp)
                windowState.size = DpSize(hullW.dp, hullH.dp)
                // Пара кадров на то, чтобы нативное окно применило границы и перерисовалось:
                // если начать двигать панель сразу, первый кадр придётся на старый буфер.
                withFrameNanos { }
                withFrameNanos { }
            }

            if (expanded) {
                showPill = false
                val spec = tween<Float>(EXPAND_ANIM_MS, easing = FastOutSlowInEasing)
                coroutineScope {
                    launch { panelX.animateTo(target.x, spec) }
                    launch { panelY.animateTo(target.y, spec) }
                    launch { panelW.animateTo(targetWidth, spec) }
                    launch { panelH.animateTo(targetHeight, spec) }
                }
            } else {
                // Сворачивание двумя фазами: сначала высота (снизу вверх при верхнем якоре),
                // затем ширина (слева направо при правом). Ось Y едет вместе с высотой,
                // ось X — вместе с шириной, чтобы якорный край не дрожал.
                val spec = tween<Float>(COLLAPSE_PHASE_MS, easing = FastOutSlowInEasing)
                coroutineScope {
                    launch { panelH.animateTo(targetHeight, spec) }
                    launch { panelY.animateTo(target.y, spec) }
                }
                coroutineScope {
                    launch { panelW.animateTo(targetWidth, spec) }
                    launch { panelX.animateTo(target.x, spec) }
                }
                showPill = true
            }

            // Окно покоя: сама панель, но не меньше полосы — чтобы следующий разворот пилюли
            // в полосу снова обошёлся без единого изменения границ. Панель уже стоит на целевом
            // месте у якорного края, поэтому этот скачок ничего не двигает на экране.
            val restW = max(targetWidth, HudSizing.MIN_WIDTH)
            val restH = max(targetHeight, HudSizing.MIN_HEIGHT)
            val restX = if (anchors.end) target.x + targetWidth - restW else target.x
            val restY = if (anchors.bottom) target.y + targetHeight - restH else target.y
            windowState.position = WindowPosition(restX.dp, restY.dp)
            windowState.size = DpSize(restW.dp, restH.dp)
            animating = false
        }

        // Хит-бокс окна повторяет видимую панель. Окно в покое всегда размером с полосу,
        // и без формы его прозрачная часть перехватывала бы клики по игре и по чужим окнам
        // рядом с пилюлей. Вне формы Windows пропускает клики насквозь, поэтому в пилюльном
        // покое кликабелен только квадрат пилюли в якорном углу. Форма прямоугольная,
        // а не скруглённая: регион режется без сглаживания и грубые углы уже обжигали.
        LaunchedEffect(animating, showPill, anchors, windowState.size) {
            window.shape = if (showPill && !animating) {
                val pill = HudSizing.COLLAPSED.toDouble()
                Rectangle2D.Double(
                    if (anchors.end) windowState.size.width.value - pill else 0.0,
                    if (anchors.bottom) windowState.size.height.value - pill else 0.0,
                    pill,
                    pill,
                )
            } else {
                null
            }
        }

        OverlayTheme {
            val windowPosition = windowState.position
            val anchorAlignment = when {
                anchors.end && anchors.bottom -> Alignment.BottomEnd
                anchors.end -> Alignment.TopEnd
                anchors.bottom -> Alignment.BottomStart
                else -> Alignment.TopStart
            }

            // Шестерёнка и дорожка не перемещаются ни на пиксель: они стоят на конечных
            // местах и быстро гаснут в самом начале сворачивания — панель схлопывается уже
            // по пустому месту. При развороте так же быстро проявляются из нуля.
            // Гашение стартует с задержкой: хром ещё висит, пока схлопывается высота,
            // и тает ближе к тому моменту, когда на него поедет иконка.
            val chromeAlpha by animateFloatAsState(
                targetValue = if (expanded) 1f else 0f,
                animationSpec = if (expanded) {
                    tween(CHROME_FADE_MS)
                } else {
                    tween(CHROME_FADE_MS, delayMillis = CHROME_FADE_OUT_DELAY_MS)
                },
                label = "chrome-alpha",
            )

            // Корень окна не рисует ничего: всё видимое — панель со скруглением и клипом.
            Box(modifier = Modifier.fillMaxSize()) {
                val panelModifier = when {
                    animating && windowPosition is WindowPosition.Absolute -> Modifier
                        .offset(
                            (panelX.value - windowPosition.x.value).dp,
                            (panelY.value - windowPosition.y.value).dp,
                        )
                        .size(panelW.value.dp, panelH.value.dp)

                    showPill -> Modifier
                        .align(anchorAlignment)
                        .size(HudSizing.COLLAPSED.dp)

                    else -> Modifier.fillMaxSize()
                }

                Box(
                    modifier = panelModifier
                        .clip(RoundedCornerShape(PANEL_CORNER))
                        .background(OverlayColors.Background.copy(alpha = settings.hud.opacity.coerceIn(0.3f, 1f)))
                        .onPointerEvent(PointerEventType.Enter) {
                            hovered = true
                            state.setHudHovered(true)
                        }
                        .onPointerEvent(PointerEventType.Exit) {
                            hovered = false
                            state.setHudHovered(false)
                        },
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
                            Box(modifier = Modifier.requiredWidth(contentWidth.dp).fillMaxHeight()) {
                                ExpandedHud(
                                    state = state,
                                    micLevel = micLevel,
                                    listening = status == OverlayStatus.Listening,
                                    chromeAlpha = chromeAlpha,
                                    blocks = blocks,
                                    userTranscript = userTranscript,
                                    toolLog = toolLog,
                                    voiceMessage = voiceMessage,
                                    authUrl = authUrl,
                                    fontSize = settings.hud.fontSize,
                                    onMeasured = { measured -> contentHeight = measured },
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
                        WindowDraggableArea(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Crossfade(
                                    targetState = (collapseFraction != null) to status,
                                    animationSpec = tween(ICON_FADE_MS),
                                ) { (counting, current) ->
                                    if (counting) {
                                        CountdownIcon(OverlayColors.Accent, collapseFraction ?: 1f, Modifier.size(18.dp))
                                    } else {
                                        HudStatusIcon(current, Modifier.size(18.dp))
                                    }
                                }
                            }
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
                }
            }
        }
    }
}

/** Развёрнутый HUD: шапка, лента хода и ответ. [onMeasured] отдаёт честную высоту содержимого. */
@Composable
private fun WindowScope.ExpandedHud(
    state: AppState,
    micLevel: Float,
    listening: Boolean,
    chromeAlpha: Float,
    blocks: List<MdBlock>,
    userTranscript: String,
    toolLog: List<ToolLogEntry>,
    voiceMessage: String?,
    authUrl: String?,
    fontSize: Float,
    onMeasured: (Float) -> Unit,
) {
    val density = LocalDensity.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WINDOW_PADDING)
            // Прокрутка нужна не столько игроку, сколько замеру: она снимает с
            // содержимого потолок высоты. Заодно длинный ответ можно домотать.
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    onMeasured(with(density) { size.height.toDp().value })
                },
        ) {
            HudHeader(
                micLevel = micLevel,
                listening = listening,
                chromeAlpha = chromeAlpha,
                onOpenConsole = { state.setConsoleVisible(true) },
            )

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
                exit = fadeOut(tween(ANSWER_FADE_MS / 2)),
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

/**
 * Шапка развёрнутого HUD: слот под плавающую иконку статуса, дорожка уровня микрофона
 * и шестерёнка в консоль. Сама иконка живёт вне шапки — на краю панели (см. [HudWindow]) —
 * и в развёрнутом состоянии встаёт ровно в свой слот. Тянуть окно можно за дорожку —
 * ниже живут текст и ссылки, которые нужно выделять и нажимать.
 */
@Composable
private fun WindowScope.HudHeader(
    micLevel: Float,
    listening: Boolean,
    chromeAlpha: Float,
    onOpenConsole: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        WindowDraggableArea(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(STATUS_ICON_SLOT))

                // Дорожка видна всегда — так полоса держит форму, — а заполняется только
                // во время записи: «микрофон не тот» видно в момент нажатия клавиши (§5.3).
                LevelTrack(
                    level = micLevel,
                    active = listening,
                    modifier = Modifier.weight(1f).graphicsLayer { alpha = chromeAlpha },
                )
                Spacer(Modifier.width(10.dp))
            }
        }
        Box(
            modifier = Modifier
                .size(HEADER_HEIGHT)
                .graphicsLayer { alpha = chromeAlpha }
                .clickable(onClick = onOpenConsole),
            contentAlignment = Alignment.Center,
        ) {
            GearIcon(OverlayColors.TextDim, Modifier.size(16.dp))
        }
    }
}

@Composable
private fun LevelTrack(level: Float, active: Boolean, modifier: Modifier = Modifier) {
    // Уровень приходит раз в ~100 мс: без сглаживания зелёная полоска дёргается ступеньками,
    // со сглаживанием — тянется за голосом. Спад в ноль после отпускания клавиши — той же анимацией.
    val fill by animateFloatAsState(
        targetValue = if (active) level.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(LEVEL_ANIM_MS),
        label = "mic-level",
    )

    Box(
        modifier = modifier
            .height(4.dp)
            .background(OverlayColors.Surface, RoundedCornerShape(2.dp)),
    ) {
        if (fill > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fill)
                    .height(4.dp)
                    .background(OverlayColors.Ok, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun AuthBanner(url: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Column {
        Text(
            text = "Открой ссылку и вернись в игру — привязка подтянется сама.",
            color = OverlayColors.Warn,
            fontSize = 12.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpen) {
                Text("Открыть в браузере", color = OverlayColors.Accent, fontSize = 12.sp)
            }
            TextButton(onClick = onDismiss) {
                Text("Скрыть", color = OverlayColors.TextDim, fontSize = 12.sp)
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
            text = "🌐 Ответ найден в интернете",
            color = OverlayColors.TextDim,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    Text(
        text = "${entry.name} → ${entry.status} (${entry.durationMs} мс)",
        color = if (entry.isFailure) OverlayColors.Error else OverlayColors.TextDim,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
