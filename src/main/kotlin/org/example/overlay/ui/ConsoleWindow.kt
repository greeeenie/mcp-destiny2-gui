package org.example.overlay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import org.example.overlay.app.AppPaths
import org.example.overlay.app.AppState
import kotlin.math.roundToInt

/** Обычное фокусируемое окно: аккаунт, инструменты, настройки (§5.3). */
@Composable
fun ConsoleWindow(state: AppState, paths: AppPaths, onClose: () -> Unit) {
    var tab by remember { mutableStateOf(0) }

    Window(
        onCloseRequest = onClose,
        title = "mcp-destiny2 — консоль",
        state = rememberWindowState(width = 900.dp, height = 620.dp),
    ) {
        OverlayTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
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
                            2 -> ToolsScreen(state)
                            3 -> AudioScreen(state)
                            else -> OverlaySettings(state, paths)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlaySettings(state: AppState, paths: AppPaths) {
    val settings by state.settings.collectAsState()
    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }

    Column {
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
            Checkbox(
                checked = settings.hud.autoSize,
                onCheckedChange = { value -> state.updateSettings { it.copy(hud = it.hud.copy(autoSize = value)) } },
            )
            Column {
                Text("Подгонять размер окна под ответ", color = OverlayColors.Text, fontSize = 13.sp)
                Text(
                    "Узкая полоска под короткую фразу, широкое окно под таблицу. " +
                        "Выключи, чтобы тянуть размер руками.",
                    color = OverlayColors.TextDim,
                    fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Адрес бэкенда") },
            singleLine = true,
            modifier = Modifier.width(520.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { state.updateSettings { it.copy(baseUrl = baseUrl.trim()) } },
                enabled = baseUrl.trim() != settings.baseUrl,
            ) {
                Text("Сохранить адрес")
            }
            OutlinedButton(onClick = { state.updateSettings { it.copy(hud = it.hud.copy(x = null, y = null)) } }) {
                Text("Забыть положение HUD")
            }
        }
        Text(
            "Положение HUD применится при следующем запуске.",
            color = OverlayColors.TextDim,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (!settings.firstRunCompleted) {
            Spacer(Modifier.height(20.dp))
            Text(
                "Первый запуск: проверь адрес бэкенда, выбери устройства на вкладке «Звук» и клавишу " +
                    "на вкладке «Голос». Destiny 2 должна идти в режиме «оконный без рамки» — " +
                    "в exclusive fullscreen оверлей не виден, это ограничение Windows.",
                color = OverlayColors.Warn,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { state.updateSettings { it.copy(firstRunCompleted = true) } }) {
                Text("Настройка закончена")
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Файл настроек", modifier = Modifier.width(180.dp), color = OverlayColors.TextDim, fontSize = 13.sp)
            Text(paths.settingsFile.toString(), color = OverlayColors.Text, fontSize = 13.sp)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("Сессия (DPAPI)", modifier = Modifier.width(180.dp), color = OverlayColors.TextDim, fontSize = 13.sp)
            Text(paths.sessionFile.toString(), color = OverlayColors.Text, fontSize = 13.sp)
        }
    }
}

private val TABS = listOf("Аккаунт", "Голос", "Инструменты", "Звук", "Оверлей")
