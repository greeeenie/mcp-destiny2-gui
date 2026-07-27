package org.example.overlay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import org.example.overlay.app.AppState
import kotlin.math.roundToInt

/** Обычное фокусируемое окно: аккаунт, голос, звук, оверлей (§5.3). */
@Composable
fun ConsoleWindow(state: AppState, onClose: () -> Unit) {
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
 * Размер и положение HUD настраиваются самим окном: размер считается из ответа, положение
 * запоминается при перетаскивании. Крутить руками остаётся только прозрачность.
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
}

private val TABS = listOf("Аккаунт", "Голос", "Звук", "Оверлей")
