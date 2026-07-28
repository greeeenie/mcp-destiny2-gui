package org.example.overlay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.overlay.app.AppState
import org.example.overlay.backend.VoiceModelOption
import org.example.overlay.input.VirtualKeys

/** Управление голосовым трактом: микрофон, клавиша push-to-talk и модель ответа (§5). */
@Composable
fun VoiceScreen(state: AppState) {
    val settings by state.settings.collectAsState()
    val enabled by state.voiceEnabled.collectAsState()
    val message by state.voiceMessage.collectAsState()
    val status by state.status.collectAsState()
    val models by state.chatModels.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Голос всегда наготове: кнопка нужна только чтобы переоткрыть линии после
            // смены устройства или обрыва.
            Button(onClick = { state.restartVoice() }) { Text("Переоткрыть микрофон") }
            // Сброс контекста: модель начнёт имитировать не свои прошлые ответы, а промпт.
            OutlinedButton(onClick = { state.clearConversation() }) { Text("Сбросить историю") }
            Text(
                text = if (enabled) "микрофон готов" else "микрофон не поднялся",
                color = if (enabled) OverlayColors.Ok else OverlayColors.Error,
                fontSize = 13.sp,
            )
            Text(status.label, color = status.dotColor(), fontSize = 13.sp)
        }

        Spacer(Modifier.height(16.dp))
        Text("Клавиша push-to-talk", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = OverlayColors.Text)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VirtualKeys.NAMES.forEach { (code, name) ->
                val selected = settings.pttKeyCode == code
                Text(
                    text = name,
                    color = if (selected) OverlayColors.Accent else OverlayColors.Text,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(
                            color = if (selected) OverlayColors.Surface else OverlayColors.Background,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .clickable { state.updateSettings { it.copy(pttKeyCode = code) } }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Клавиша меняется на лету. Right Alt в Destiny 2 по умолчанию не занят.",
            color = OverlayColors.TextDim,
            fontSize = 11.sp,
        )

        models?.let { available ->
            Spacer(Modifier.height(16.dp))
            ModelSelector(
                title = "Модель ответа",
                options = available.options,
                default = available.default,
                current = settings.chatModel,
                onSelect = { choice -> state.updateSettings { it.copy(chatModel = choice) } },
            )
            // Пустой список — сервер без селектора STT: блок тогда не показываем вовсе.
            if (available.sttOptions.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                ModelSelector(
                    title = "Модель распознавания речи",
                    options = available.sttOptions,
                    default = available.sttDefault,
                    current = settings.sttModel,
                    onSelect = { choice -> state.updateSettings { it.copy(sttModel = choice) } },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        ToggleRow(
            checked = settings.showAssistantText,
            label = "Показывать ответ в HUD",
            hint = "Выключи, если в бою нужен только индикатор статуса. Озвучки нет — с выключенным " +
                "текстом ассистент замолчит совсем.",
            onChange = { value -> state.updateSettings { it.copy(showAssistantText = value) } },
        )

        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = OverlayColors.Warn, fontSize = 13.sp)
        }
    }
}

/** Ряд моделей на выбор. Выбор хранится только для не-дефолта: null означает «как решил сервер»,
 * и смена серверного дефолта тогда подхватывается сама. */
@Composable
private fun ModelSelector(
    title: String,
    options: List<VoiceModelOption>,
    default: String,
    current: String?,
    onSelect: (String?) -> Unit,
) {
    Column {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = OverlayColors.Text)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val selected = (current ?: default) == option.id
                Text(
                    text = option.label,
                    color = if (selected) OverlayColors.Accent else OverlayColors.Text,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(
                            color = if (selected) OverlayColors.Surface else OverlayColors.Background,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .clickable { onSelect(option.id.takeIf { it != default }) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Действует со следующего вопроса. ${labelOf(options, default)} — серверная по умолчанию.",
            color = OverlayColors.TextDim,
            fontSize = 11.sp,
        )
    }
}

private fun labelOf(options: List<VoiceModelOption>, default: String): String =
    options.firstOrNull { it.id == default }?.label ?: default

@Composable
private fun ToggleRow(checked: Boolean, label: String, hint: String, onChange: (Boolean) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onChange)
            Text(label, color = OverlayColors.Text, fontSize = 13.sp)
        }
        Text(hint, color = OverlayColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(start = 48.dp))
    }
}
