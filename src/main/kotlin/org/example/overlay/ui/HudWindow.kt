package org.example.overlay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance
import org.example.overlay.app.AppState
import org.example.overlay.app.ToolLogEntry
import org.example.overlay.markdown.AnswerContent
import org.example.overlay.markdown.MdBlock
import org.example.overlay.platform.ScreenPlacement

private const val POSITION_SAVE_DEBOUNCE_MS = 400L

/** Не больше трёх последних строк инструментов (§5.3). */
private const val MAX_HUD_TOOL_LINES = 3

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
    val voiceEnabled by state.voiceEnabled.collectAsState()
    val authUrl by state.authUrl.collectAsState()
    val voiceMessage by state.voiceMessage.collectAsState()

    val autoSize = settings.hud.autoSize
    val startWidth = if (autoSize) HudSizing.MIN_WIDTH else settings.hud.width
    val startHeight = if (autoSize) HudSizing.MIN_HEIGHT else settings.hud.height

    // Положение вычисляем один раз при старте: дальше окном управляет пользователь.
    val placement = remember { ScreenPlacement.resolve(settings.hud.x, settings.hud.y, startWidth, startHeight) }
    val windowState = rememberWindowState(
        width = startWidth.dp,
        height = startHeight.dp,
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

        // Размер, выставленный руками, запоминаем только когда автоподбор выключен: иначе
        // сохранялся бы наш же расчёт.
        if (!autoSize) {
            LaunchedEffect(windowState) {
                snapshotFlow { windowState.size }
                    .debounce(POSITION_SAVE_DEBOUNCE_MS)
                    .collect { size ->
                        state.updateSettings { current ->
                            current.copy(hud = current.hud.copy(width = size.width.value, height = size.height.value))
                        }
                    }
            }
        }

        // Размер считается из самой разметки. Замерять содержимое нельзя: оно живёт внутри окна
        // и не может померить себя больше окна — высота упиралась бы в текущую.
        val desired = remember(blocks, settings.hud.fontSize, userTranscript, toolLog.size, authUrl, voiceMessage) {
            val width = HudSizing.width(blocks, settings.hud.fontSize)
            val height = HudSizing.height(
                blocks = blocks,
                fontSize = settings.hud.fontSize,
                width = width,
                extras = HudSizing.Extras(
                    userTranscript = userTranscript.isNotBlank(),
                    toolLines = minOf(toolLog.size, MAX_HUD_TOOL_LINES),
                    authBanner = authUrl != null,
                    message = voiceMessage != null,
                ),
            )
            DpSize(width.dp, height.dp)
        }

        LaunchedEffect(autoSize, desired) {
            if (!autoSize) return@LaunchedEffect
            windowState.size = desired

            // Выросшее окно не должно уезжать за край: HUD по умолчанию стоит в правом углу.
            val position = windowState.position
            if (position is WindowPosition.Absolute) {
                val clamped = ScreenPlacement.clampToScreen(
                    x = position.x.value,
                    y = position.y.value,
                    width = desired.width.value,
                    height = desired.height.value,
                )
                if (clamped.x != position.x.value || clamped.y != position.y.value) {
                    windowState.position = WindowPosition(clamped.x.dp, clamped.y.dp)
                }
            }
        }

        OverlayTheme {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = OverlayColors.Background.copy(alpha = settings.hud.opacity.coerceIn(0.3f, 1f)),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(14.dp),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    WindowDraggableArea {
                        // Тянуть окно можно только за верхнюю полосу: ниже живут текст и ссылки,
                        // которые нужно выделять и нажимать.
                        HudHeader(status.label, status.dotColor(), micLevel)
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
                            fontSize = (settings.hud.fontSize - 1).sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Ответ забирает всё свободное место; если расчёт промахнулся — прокрутка.
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                        AnswerPanel(blocks, settings.hud.fontSize)
                    }

                    if (toolLog.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        toolLog.takeLast(MAX_HUD_TOOL_LINES).forEach { entry -> ToolLine(entry) }
                    }

                    HudFooter(
                        voiceEnabled = voiceEnabled,
                        onToggleVoice = { state.toggleVoice() },
                        onOpenConsole = { state.setConsoleVisible(true) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HudHeader(statusLabel: String, statusColor: Color, micLevel: Float) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(10.dp).background(statusColor, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                text = statusLabel,
                color = OverlayColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text("тяни за эту полосу", color = OverlayColors.TextDim, fontSize = 9.sp)
        }

        Spacer(Modifier.height(8.dp))
        // Узкая полоска уровня: «микрофон не тот» должно быть видно сразу (§5.3).
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

@Composable
private fun AnswerPanel(blocks: List<MdBlock>, fontSize: Float) {
    if (blocks.isEmpty()) return
    MarkdownView(
        blocks = blocks,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        fontSize = fontSize.sp,
    )
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

@Composable
private fun HudFooter(voiceEnabled: Boolean, onToggleVoice: () -> Unit, onOpenConsole: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onToggleVoice) {
            Text(
                text = if (voiceEnabled) "Выключить голос" else "Включить голос",
                color = if (voiceEnabled) OverlayColors.Warn else OverlayColors.Ok,
                fontSize = 12.sp,
            )
        }
        TextButton(onClick = onOpenConsole) {
            Text("Консоль", color = OverlayColors.Accent, fontSize = 12.sp)
        }
    }
}
