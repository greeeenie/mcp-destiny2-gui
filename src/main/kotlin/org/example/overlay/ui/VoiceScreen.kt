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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.overlay.app.AppState
import org.example.overlay.input.VirtualKeys
import org.example.overlay.markdown.AnswerContent

/** Управление голосовым трактом: клавиша, режим, запасной путь закрытия хода (§5). */
@Composable
fun VoiceScreen(state: AppState) {
    val settings by state.settings.collectAsState()
    val enabled by state.voiceEnabled.collectAsState()
    val message by state.voiceMessage.collectAsState()
    val status by state.status.collectAsState()
    val userText by state.userTranscript.collectAsState()
    val assistantText by state.assistantTranscript.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { state.toggleVoice() }) {
                Text(if (enabled) "Выключить голос" else "Включить голос")
            }
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

        Spacer(Modifier.height(16.dp))
        ToggleRow(
            checked = settings.handsFree,
            label = "Hands-free (микрофон открыт после ответа)",
            hint = "На колонках работает хуже: включи наушники или подавление эха в свойствах устройства Windows.",
            onChange = { value -> state.updateSettings { it.copy(handsFree = value) } },
        )
        ToggleRow(
            checked = settings.speakResponses,
            label = "Озвучивать ответы",
            hint = "Выключено: оверлей просит у Inworld только текст, синтез речи не тарифицируется. " +
                "Включение вернёт и звук, и плату за него.",
            onChange = { value -> state.updateSettings { it.copy(speakResponses = value) } },
        )
        if (!settings.speakResponses) {
            ToggleRow(
                checked = settings.requestTextOnly,
                label = "Просить у Inworld только текст",
                hint = "Так речь не синтезируется и не тарифицируется. Выключи, если сессия падает " +
                    "с ошибкой протокола сразу после подключения — значит деплой Inworld не принял патч.",
                onChange = { value -> state.updateSettings { it.copy(requestTextOnly = value) } },
            )
        }
        ToggleRow(
            checked = settings.showAssistantText,
            label = "Показывать ответ в HUD",
            hint = "Выключи, если в бою нужен только индикатор статуса.",
            onChange = { value -> state.updateSettings { it.copy(showAssistantText = value) } },
        )
        ToggleRow(
            checked = settings.commitOnRelease,
            label = "Закрывать ход явным commit",
            hint = "Запасной путь: включай, если хвоста тишины в 400 мс не хватает и ассистент не отвечает.",
            onChange = { value -> state.updateSettings { it.copy(commitOnRelease = value) } },
        )

        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = OverlayColors.Warn, fontSize = 13.sp)
        }

        Spacer(Modifier.height(16.dp))
        Text("Последний ход", color = OverlayColors.TextDim, fontSize = 12.sp)
        Text(userText.ifBlank { "—" }, color = OverlayColors.Text, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        if (assistantText.isBlank()) {
            Text("—", color = OverlayColors.TextDim, fontSize = 13.sp)
        } else {
            // Тот же рендер, что в HUD: в консоли места больше, таблицы видно целиком.
            val blocks = remember(assistantText) { AnswerContent.parse(assistantText) }
            MarkdownView(
                blocks = blocks,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

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
