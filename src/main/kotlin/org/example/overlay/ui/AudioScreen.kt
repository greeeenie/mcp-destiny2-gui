package org.example.overlay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.overlay.app.AppState
import org.example.overlay.audio.AudioDevice
import org.example.overlay.audio.AudioDevices

/**
 * Выбор устройств и эхо-тест (фаза 2). Список микшеров JVM кэширует при старте, поэтому
 * кнопка «Обновить» честно перечитывает то, что доступно, но новые устройства могут
 * появиться только после перезапуска — об этом сказано прямо в UI (риск 3).
 */
@Composable
fun AudioScreen(state: AppState) {
    val settings by state.settings.collectAsState()
    val message by state.audioMessage.collectAsState()
    val busy by state.audioBusy.collectAsState()

    var refreshKey by remember { mutableStateOf(0) }
    val inputs = remember(refreshKey) { AudioDevices.inputs() }
    val outputs = remember(refreshKey) { AudioDevices.outputs() }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ConsoleUtilityButton(
                text = "Обновить устройства",
                icon = { RefreshActionIcon() },
                onClick = { refreshKey++ },
                emphasized = true,
                modifier = Modifier.width(226.dp),
            )
            ConsoleUtilityButton(
                text = "Эхо-тест: 5 секунд",
                icon = { SpeakerActionIcon() },
                onClick = state::runAudioSelfTest,
                enabled = !busy,
                modifier = Modifier.width(218.dp),
            )
        }

        Spacer(Modifier.height(30.dp))
        SectionColumns {
            ConsoleSection("Микрофон", Modifier.weight(1f)) {
                DeviceList(
                    devices = inputs,
                    selectedName = settings.audio.inputMixer,
                    // Системное устройство хранится как null, а не как его имя: имя «по умолчанию»
                    // ничего не значит на другой машине.
                    onSelect = { device ->
                        val name = device.mixer?.let { device.name }
                        state.updateSettings { it.copy(audio = it.audio.copy(inputMixer = name)) }
                    },
                )
            }

            androidx.compose.material3.VerticalDivider(
                modifier = Modifier.height(275.dp),
                color = OverlayColors.Divider,
            )

            ConsoleSection("Вывод", Modifier.weight(1f)) {
                DeviceList(
                    devices = outputs,
                    selectedName = settings.audio.outputMixer,
                    onSelect = { device ->
                        val name = device.mixer?.let { device.name }
                        state.updateSettings { it.copy(audio = it.audio.copy(outputMixer = name)) }
                    },
                )
            }
        }

        message?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = OverlayColors.Text, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DeviceList(
    devices: List<AudioDevice>,
    selectedName: String?,
    onSelect: (AudioDevice) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(modifier = Modifier.fillMaxWidth().height(205.dp)) {
            items(devices) { device ->
                val isSelected = device.name == (selectedName ?: AudioDevices.SYSTEM_DEFAULT)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)
                        .height(38.dp)
                        .background(if (isSelected) OverlayColors.ControlSelected else OverlayColors.Control)
                        .clickable { onSelect(device) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.width(3.dp).height(38.dp)
                            .background(if (isSelected) OverlayColors.Accent else OverlayColors.Control),
                    )
                    Text(
                        text = device.name,
                        color = if (isSelected) OverlayColors.Text else OverlayColors.TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            }
        }
    }
}
