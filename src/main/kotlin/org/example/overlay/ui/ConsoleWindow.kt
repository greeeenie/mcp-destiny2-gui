package org.example.overlay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import org.example.overlay.app.AppState
import org.example.overlay.app.HudSettings
import org.example.overlay.update.AppVersion
import org.example.overlay.update.UpdateState
import kotlin.math.roundToInt

/** Обычное фокусируемое окно: аккаунт, голос, звук, оверлей (§5.3). */
@Composable
fun ConsoleWindow(state: AppState, onClose: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val updateState by state.updateState.collectAsState()

    Window(
        onCloseRequest = onClose,
        title = "mcp-destiny2 — консоль",
        state = rememberWindowState(width = 900.dp, height = 620.dp),
    ) {
        OverlayTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    UpdateBanner(updateState, onInstall = state::installUpdate)
                    PrimaryTabRow(selectedTabIndex = tab) {
                        TABS.forEachIndexed { index, title ->
                            Tab(
                                selected = tab == index,
                                onClick = { tab = index },
                                text = { Text(title) },
                            )
                        }
                    }
                    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                        when (tab) {
                            0 -> AccountScreen(state)
                            1 -> VoiceScreen(state)
                            2 -> AudioScreen(state)
                            else -> OverlaySettings(state)
                        }
                    }
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
private fun OverlaySettings(state: AppState) {
    val settings by state.settings.collectAsState()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Прозрачность HUD", modifier = Modifier.width(180.dp), color = OverlayColors.TextDim, fontSize = 13.sp)
        Slider(
            value = settings.hud.opacity,
            onValueChange = { value -> state.updateSettings { it.copy(hud = it.hud.copy(opacity = value)) } },
            valueRange = 0.3f..1f,
            modifier = Modifier.width(260.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text("${(settings.hud.opacity * 100).roundToInt()} %", color = OverlayColors.Text, fontSize = 13.sp)
    }

    Spacer(Modifier.height(12.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Свернуть после ответа", modifier = Modifier.width(180.dp), color = OverlayColors.TextDim, fontSize = 13.sp)
        Slider(
            value = settings.hud.collapseSeconds.toFloat(),
            onValueChange = { value ->
                state.updateSettings { it.copy(hud = it.hud.copy(collapseSeconds = value.roundToInt())) }
            },
            valueRange = HudSettings.MIN_COLLAPSE_SECONDS.toFloat()..HudSettings.MAX_COLLAPSE_SECONDS.toFloat(),
            steps = HudSettings.MAX_COLLAPSE_SECONDS - HudSettings.MIN_COLLAPSE_SECONDS - 1,
            modifier = Modifier.width(260.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text("${settings.hud.collapseSeconds} с", color = OverlayColors.Text, fontSize = 13.sp)
    }
}

private val TABS = listOf("Аккаунт", "Голос", "Звук", "Оверлей")
