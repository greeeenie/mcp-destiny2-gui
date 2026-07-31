package org.example.overlay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import org.example.overlay.app.AppState
import org.example.overlay.app.HudSettings
import org.example.overlay.update.AppVersion
import org.example.overlay.update.UpdateState
import kotlin.math.roundToInt

/** Обычное фокусируемое окно настроек: профиль, ассистент, голос, аудио и HUD. */
@Composable
fun ConsoleWindow(state: AppState, onClose: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val updateState by state.updateState.collectAsState()

    val windowState = rememberWindowState(width = 1080.dp, height = 720.dp)

    Window(
        onCloseRequest = onClose,
        title = "Destiny 2 Assistant — консоль",
        icon = painterResource("icons/app-icon.png"),
        state = windowState,
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
                Column(modifier = Modifier.fillMaxSize()) {
                    UpdateBanner(updateState, onInstall = state::installUpdate)
                    ConsoleTabs(selectedIndex = tab, onSelect = { tab = it })
                    ConsolePage(TABS[tab].pageTitle, TABS[tab].mark) {
                        when (TABS[tab]) {
                            ConsoleTab.Profile -> AccountScreen(state)
                            ConsoleTab.Assistant -> AssistantScreen(state)
                            ConsoleTab.Voice -> VoiceScreen(state)
                            ConsoleTab.Audio -> AudioScreen(state)
                            ConsoleTab.Hud -> HudSettingsScreen(state)
                        }
                    }
                }
            }
        }
    }
}

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
                    "Доступно обновление ${state.update.version} — у тебя ${AppVersion.current}",
                    color = OverlayColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onInstall) {
                    Text("Установить и перезапустить", color = OverlayColors.Accent, fontSize = 13.sp)
                }
            }

            is UpdateState.Downloading -> {
                Text("Скачиваю ${state.update.version}…", color = OverlayColors.Text, fontSize = 13.sp)
                Spacer(Modifier.width(12.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text("${(state.progress * 100).roundToInt()} %", color = OverlayColors.TextDim, fontSize = 13.sp)
            }

            is UpdateState.Installing -> Text(
                "Установщик запущен — приложение закроется и перезапустится само после обновления.",
                color = OverlayColors.Ok,
                fontSize = 13.sp,
            )

            is UpdateState.Failed -> {
                Text(
                    "Обновление не установилось: ${state.message}",
                    color = OverlayColors.Error,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onInstall) {
                    Text("Повторить", color = OverlayColors.Accent, fontSize = 13.sp)
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

    ConsoleSection("Внешний вид HUD") {
        HudSliderRow(
            label = "Прозрачность",
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
            label = "Свернуть после ответа",
            valueText = "${settings.hud.collapseSeconds} с",
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

private enum class ConsoleTab(val label: String, val pageTitle: String, val mark: ConsolePageMark) {
    Profile("Профиль", "Профиль", ConsolePageMark.Profile),
    Assistant("Ассистент", "Ассистент", ConsolePageMark.Assistant),
    Voice("Голос", "Голос", ConsolePageMark.Voice),
    Audio("Аудио", "Аудио", ConsolePageMark.Audio),
    Hud("HUD", "HUD", ConsolePageMark.Hud),
}

private val TABS = ConsoleTab.entries
