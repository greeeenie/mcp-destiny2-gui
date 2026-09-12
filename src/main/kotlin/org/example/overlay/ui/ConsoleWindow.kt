package org.example.overlay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.rememberWindowState
import java.awt.Toolkit
import org.example.overlay.app.AppState
import org.example.overlay.app.HudSettings
import org.example.overlay.update.AppVersion
import org.example.overlay.update.UpdateState
import kotlin.math.roundToInt

/** Обычное фокусируемое окно настроек: профиль, ассистент, голос и HUD. */
@Composable
fun ConsoleWindow(state: AppState, onClose: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val updateState by state.updateState.collectAsState()

    // 850×720 на Full HD; на других разрешениях — та же доля экрана, чтобы масштаб совпадал.
    val windowState = rememberWindowState(
        size = remember {
            val screen = Toolkit.getDefaultToolkit().screenSize
            DpSize((screen.width * 850f / 1920f).dp, (screen.height * 720f / 1080f).dp)
        },
        position = WindowPosition(Alignment.Center),
    )

    Window(
        onCloseRequest = onClose,
        title = "Destiny 2 Assistant — Console",
        icon = painterResource("icons/app-icon.png"),
        state = windowState,
        undecorated = true,
        resizable = false,
    ) {
        // «Открыть консоль» из шестерёнки или трея: окно может быть свёрнуто в панель
        // задач — сама видимость его не развернёт. Разворачиваем и поднимаем наверх.
        LaunchedEffect(Unit) {
            state.consoleRaise.collect {
                windowState.isMinimized = false
                window.toFront()
            }
        }

        OverlayTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                // У окна без системной рамки нет и системной обводки — тонкая своя, чтобы
                // консоль не сливалась с тёмным фоном других окон.
                Column(modifier = Modifier.fillMaxSize().border(1.dp, OverlayColors.Divider)) {
                    ConsoleTitleBar(
                        onMinimize = { windowState.isMinimized = true },
                        onClose = onClose,
                    )
                    UpdateBanner(updateState, onInstall = state::installUpdate)
                    ConsoleTabs(selectedIndex = tab, onSelect = { tab = it })
                    ConsolePage(TABS[tab].pageTitle, TABS[tab].markAsset) {
                        when (TABS[tab]) {
                            ConsoleTab.Profile -> AccountScreen(state)
                            ConsoleTab.Assistant -> AssistantScreen(state)
                            ConsoleTab.Voice -> VoiceScreen(state)
                            ConsoleTab.Hud -> HudSettingsScreen(state)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Своя шапка окна вместо системной: тянуть можно за всю полосу, справа — свернуть и закрыть.
 * Развернуть на весь экран консоли незачем, поэтому кнопки всего две.
 */
@Composable
private fun WindowScope.ConsoleTitleBar(onMinimize: () -> Unit, onClose: () -> Unit) {
    WindowDraggableArea {
        Row(
            modifier = Modifier.fillMaxWidth().height(TITLE_BAR_HEIGHT).background(OverlayColors.Background),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(14.dp))
            Image(
                painter = painterResource("icons/app-icon.png"),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "DESTINY 2 ASSISTANT",
                color = OverlayColors.TextDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp,
            )
            Spacer(Modifier.weight(1f))
            TitleBarButton(hoverBackground = OverlayColors.ControlSelected, onClick = onMinimize) { color ->
                drawLine(
                    color,
                    Offset(0f, size.height / 2f),
                    Offset(size.width, size.height / 2f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            TitleBarButton(hoverBackground = OverlayColors.Error, onClick = onClose) { color ->
                drawLine(color, Offset.Zero, Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                drawLine(color, Offset(0f, size.height), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            }
        }
    }
}

@Composable
private fun TitleBarButton(
    hoverBackground: Color,
    onClick: () -> Unit,
    icon: DrawScope.(Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(TITLE_BAR_HEIGHT)
            .hoverable(interaction)
            .background(if (hovered) hoverBackground else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val color = if (hovered) OverlayColors.Text else OverlayColors.TextDim
        Canvas(Modifier.size(10.dp)) { icon(color) }
    }
}

private val TITLE_BAR_HEIGHT = 34.dp

@Composable
private fun ConsoleTabs(selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(62.dp).background(OverlayColors.Surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TABS.forEachIndexed { index, tab ->
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight().clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label.uppercase(),
                    color = if (index == selectedIndex) OverlayColors.Text else OverlayColors.TextDim,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.7.sp,
                    textAlign = TextAlign.Center,
                )
                if (index == selectedIndex) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).width(56.dp).height(2.dp)
                            .background(OverlayColors.Accent),
                    )
                }
            }
        }
    }
}

/**
 * Полоса обновления над вкладками. Пока обновления нет, её не существует вовсе: консоль
 * выглядит как раньше. Установка — только по клику, поэтому вся полоса и есть приглашение.
 */
@Composable
private fun UpdateBanner(state: UpdateState, onInstall: () -> Unit) {
    if (state is UpdateState.Hidden) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OverlayColors.Surface)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            is UpdateState.Available -> {
                Text(
                    "Update ${state.update.version} is available — you have ${AppVersion.current}",
                    color = OverlayColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onInstall) {
                    Text("Install and restart", color = OverlayColors.Accent, fontSize = 13.sp)
                }
            }

            is UpdateState.Downloading -> {
                Text("Downloading ${state.update.version}…", color = OverlayColors.Text, fontSize = 13.sp)
                Spacer(Modifier.width(12.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text("${(state.progress * 100).roundToInt()} %", color = OverlayColors.TextDim, fontSize = 13.sp)
            }

            is UpdateState.Installing -> Text(
                "Installer started — the app will close and restart after the update.",
                color = OverlayColors.Ok,
                fontSize = 13.sp,
            )

            is UpdateState.Failed -> {
                Text(
                    "Update failed: ${state.message}",
                    color = OverlayColors.Error,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onInstall) {
                    Text("Retry", color = OverlayColors.Accent, fontSize = 13.sp)
                }
            }

            UpdateState.Hidden -> Unit
        }
    }
}

/**
 * Размер и положение HUD настраиваются самим окном: размер считается из ответа, положение
 * запоминается при перетаскивании. Крутить руками остаётся прозрачность и время показа ответа.
 */
@Composable
private fun HudSettingsScreen(state: AppState) {
    val settings by state.settings.collectAsState()

    ConsoleSection("HUD appearance") {
        HudSliderRow(
            label = "Opacity",
            valueText = "${(settings.hud.opacity * 100).roundToInt()} %",
        ) {
            ConsoleSlider(
                value = settings.hud.opacity,
                onValueChange = { value -> state.updateSettings { it.copy(hud = it.hud.copy(opacity = value)) } },
                valueRange = 0.3f..1f,
                tickCount = 7,
                modifier = Modifier.width(310.dp),
            )
        }

        Spacer(Modifier.height(18.dp))

        HudSliderRow(
            label = "Collapse after response",
            valueText = "${settings.hud.collapseSeconds} s",
        ) {
            ConsoleSlider(
                value = settings.hud.collapseSeconds.toFloat(),
                onValueChange = { value ->
                    state.updateSettings { it.copy(hud = it.hud.copy(collapseSeconds = value.roundToInt())) }
                },
                valueRange = HudSettings.MIN_COLLAPSE_SECONDS.toFloat()..HudSettings.MAX_COLLAPSE_SECONDS.toFloat(),
                divisions = HudSettings.MAX_COLLAPSE_SECONDS - HudSettings.MIN_COLLAPSE_SECONDS,
                modifier = Modifier.width(310.dp),
            )
        }
    }
}

@Composable
private fun HudSliderRow(
    label: String,
    valueText: String,
    slider: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(190.dp), color = OverlayColors.TextMuted, fontSize = 13.sp)
        slider()
        Spacer(Modifier.width(16.dp))
        Box(
            modifier = Modifier.width(64.dp).height(32.dp)
                .background(OverlayColors.Control)
                .border(1.dp, OverlayColors.ControlBorder),
            contentAlignment = Alignment.Center,
        ) {
            Text(valueText, color = OverlayColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private enum class ConsoleTab(val label: String, val pageTitle: String, val markAsset: String) {
    Profile("Profile", "Profile", "icons/page-mark-profile.png"),
    Assistant("Assistant", "Assistant", "icons/page-mark-assistant.png"),
    Voice("Voice", "Voice", "icons/page-mark-voice.png"),
    Hud("HUD", "HUD", "icons/page-mark-hud.png"),
}

private val TABS = ConsoleTab.entries
