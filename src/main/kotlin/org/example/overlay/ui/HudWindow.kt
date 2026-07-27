package org.example.overlay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance
import org.example.overlay.app.AppState
import org.example.overlay.app.OverlayStatus
import org.example.overlay.app.ToolLogEntry
import org.example.overlay.markdown.AnswerContent
import org.example.overlay.platform.ScreenPlacement

private const val POSITION_SAVE_DEBOUNCE_MS = 400L

/** Не больше трёх последних строк инструментов (§5.3). */
private const val MAX_HUD_TOOL_LINES = 3

/** Высота шапки: она же область захвата шестерёнки, поэтому не меньше пальца на трекпаде. */
private val HEADER_HEIGHT = 22.dp

/** Поля панели. Вынесены в константу: на них же считается высота окна. */
private val WINDOW_PADDING = 14.dp

/** Появление ответа. Дольше — HUD кажется медленным: текст уже пришёл, а его ещё прячут. */
private const val ANSWER_FADE_MS = 220

/** Полоска уровня должна успевать за клавишей, поэтому быстрее ответа. */
private const val LEVEL_BAR_FADE_MS = 120

/**
 * Оверлей поверх игры (§5.3).
 *
 * `focusable = false` — ключевое свойство: без него клик по HUD отбирает фокус у Destiny 2
 * и персонаж перестаёт слушаться WASD. Мышиные события в нефокусируемое окно AWT доставляет
 * по-прежнему, поэтому кнопки и ссылки работают; клавиатурного ввода в HUD нет — для него есть
 * консоль.
 *
 * Размер по умолчанию подгоняется под ответ: и ширина, и высота считаются из разметки
 * (см. [HudSizing]). Тянуть окно руками можно всегда — следующий ответ переставит размер снова.
 */
@OptIn(FlowPreview::class)
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

    // Стартуем узкой полоской: первый же ответ пересчитает размер под себя.
    val placement = remember {
        ScreenPlacement.resolve(settings.hud.x, settings.hud.y, HudSizing.MIN_WIDTH, HudSizing.MIN_HEIGHT)
    }
    val windowState = rememberWindowState(
        width = HudSizing.MIN_WIDTH.dp,
        height = HudSizing.MIN_HEIGHT.dp,
        position = WindowPosition(placement.x.dp, placement.y.dp),
    )

    val answer = assistantTranscript.takeIf { settings.showAssistantText }.orEmpty()
    val blocks = remember(answer) { AnswerContent.parse(answer) }

    Window(
        onCloseRequest = { /* HUD не закрывается: выход — через трей */ },
        state = windowState,
        title = "mcp-destiny2 overlay",
        undecorated = true,
        transparent = true,
        // Тянуть окно можно всегда: автоподбор лишь переставит размер на следующем ответе.
        resizable = true,
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
        val width = remember(blocks, settings.hud.fontSize) { HudSizing.width(blocks, settings.hud.fontSize) }

        // Высоту, наоборот, меряем по-настоящему. Внутри `verticalScroll` максимальная высота
        // не ограничена окном, поэтому содержимое честно сообщает, сколько ему нужно.
        var contentHeight by remember { mutableStateOf(HudSizing.MIN_HEIGHT) }
        val desired = DpSize(
            width = width.dp,
            height = (contentHeight + WINDOW_PADDING.value * 2)
                .coerceIn(HudSizing.MIN_HEIGHT, HudSizing.MAX_HEIGHT).dp,
        )

        LaunchedEffect(desired) {
            val position = windowState.position
            val previous = windowState.size
            windowState.size = desired

            // Окно держится за ближайший край экрана: HUD в правом углу растёт и сжимается
            // влево, не отползая от края с каждым ходом. Заодно не уезжает за границу.
            if (position is WindowPosition.Absolute) {
                val moved = ScreenPlacement.resize(
                    x = position.x.value,
                    y = position.y.value,
                    oldWidth = previous.width.value,
                    oldHeight = previous.height.value,
                    newWidth = desired.width.value,
                    newHeight = desired.height.value,
                )
                if (moved.x != position.x.value || moved.y != position.y.value) {
                    windowState.position = WindowPosition(moved.x.dp, moved.y.dp)
                }
            }
        }

        val density = LocalDensity.current

        OverlayTheme {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = OverlayColors.Background.copy(alpha = settings.hud.opacity.coerceIn(0.3f, 1f)),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(WINDOW_PADDING),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // Прокрутка нужна не столько игроку, сколько замеру: она снимает с
                        // содержимого потолок высоты. Заодно длинный ответ можно домотать.
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { size ->
                                contentHeight = with(density) { size.height.toDp().value }
                            },
                    ) {
                        HudHeader(
                            status = status,
                            micLevel = micLevel,
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
                                fontSize = (settings.hud.fontSize - 1).sp,
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
                                    fontSize = settings.hud.fontSize.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Шапка: слева иконка состояния, справа шестерёнка в консоль. Тянуть окно можно за всё
 * между ними — ниже живут текст и ссылки, которые нужно выделять и нажимать.
 */
@Composable
private fun WindowScope.HudHeader(status: OverlayStatus, micLevel: Float, onOpenConsole: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WindowDraggableArea(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HudStatusIcon(status, Modifier.size(18.dp))
                }
            }
            Box(
                modifier = Modifier.size(HEADER_HEIGHT).clickable(onClick = onOpenConsole),
                contentAlignment = Alignment.Center,
            ) {
                GearIcon(OverlayColors.TextDim, Modifier.size(16.dp))
            }
        }

        // Полоска уровня живёт только пока клавиша зажата: «микрофон не тот» видно в момент
        // записи (§5.3), а в остальное время серая линия на всю ширину — просто шум.
        // Появление анимируем, чтобы шапка не прыгала ступенькой.
        AnimatedVisibility(
            visible = status == OverlayStatus.Listening,
            enter = fadeIn(tween(LEVEL_BAR_FADE_MS)) + expandVertically(tween(LEVEL_BAR_FADE_MS)),
            exit = fadeOut(tween(LEVEL_BAR_FADE_MS)) + shrinkVertically(tween(LEVEL_BAR_FADE_MS)),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(OverlayColors.Surface, RoundedCornerShape(2.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(micLevel.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(OverlayColors.Ok, RoundedCornerShape(2.dp)),
                    )
                }
            }
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
    Text(
        text = "${entry.name} → ${entry.status} (${entry.durationMs} мс)",
        color = if (entry.isFailure) OverlayColors.Error else OverlayColors.TextDim,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
