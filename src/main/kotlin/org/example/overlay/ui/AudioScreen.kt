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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
    val level by state.micLevel.collectAsState()
    val message by state.audioMessage.collectAsState()
    val busy by state.audioBusy.collectAsState()

    var refreshKey by remember { mutableStateOf(0) }
    val inputs = remember(refreshKey) { AudioDevices.inputs() }
    val outputs = remember(refreshKey) { AudioDevices.outputs() }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            DeviceList(
                title = "Микрофон",
                devices = inputs,
                selectedName = settings.audio.inputMixer,
                // Системное устройство хранится как null, а не как его имя: имя «по умолчанию»
                // ничего не значит на другой машине.
                onSelect = { device ->
                    val name = device.mixer?.let { device.name }
                    state.updateSettings { it.copy(audio = it.audio.copy(inputMixer = name)) }
                },
            )
            DeviceList(
                title = "Вывод",
                devices = outputs,
                selectedName = settings.audio.outputMixer,
                onSelect = { device ->
                    val name = device.mixer?.let { device.name }
                    state.updateSettings { it.copy(audio = it.audio.copy(outputMixer = name)) }
                },
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { refreshKey++ }) { Text("Обновить список") }
            Button(onClick = { state.runAudioSelfTest() }, enabled = !busy) {
                Text("Эхо-тест: 5 секунд")
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Уровень входа", color = OverlayColors.TextDim, fontSize = 12.sp)
        LevelBar(level)

        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = OverlayColors.Text, fontSize = 13.sp)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Новое устройство, воткнутое после запуска, появится в списке только после перезапуска — " +
                "так работает JavaSound, а не наш код.",
            color = OverlayColors.TextDim,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun DeviceList(
    title: String,
    devices: List<AudioDevice>,
    selectedName: String?,
    onSelect: (AudioDevice) -> Unit,
) {
    Column(modifier = Modifier.width(380.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = OverlayColors.Text)
        Spacer(Modifier.height(6.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth().height(220.dp)) {
            items(devices) { device ->
                val isSelected = device.name == (selectedName ?: AudioDevices.SYSTEM_DEFAULT)
                Text(
                    text = device.name,
                    color = if (isSelected) OverlayColors.Accent else OverlayColors.Text,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp)
                        .background(
                            color = if (isSelected) OverlayColors.Surface else OverlayColors.Background,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .clickable { onSelect(device) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun LevelBar(level: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(OverlayColors.Surface, RoundedCornerShape(4.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .height(8.dp)
                .background(OverlayColors.Ok, RoundedCornerShape(4.dp)),
        )
    }
}
