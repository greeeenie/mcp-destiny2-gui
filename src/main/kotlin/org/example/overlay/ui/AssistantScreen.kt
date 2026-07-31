package org.example.overlay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.overlay.app.AppState

/** Модель ответа и её контекст отделены от распознавания речи. */
@Composable
fun AssistantScreen(state: AppState) {
    val settings by state.settings.collectAsState()
    val models by state.chatModels.collectAsState()
    val message by state.voiceMessage.collectAsState()

    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ConsoleUtilityButton(
                text = "Сбросить историю",
                icon = { HistoryActionIcon() },
                onClick = state::clearConversation,
                emphasized = true,
                modifier = Modifier.width(232.dp),
            )
        }

        Spacer(Modifier.height(30.dp))
        ConsoleSection("Модель ответа") {
            val available = models
            if (available == null) {
                Text("Получаю список моделей…", color = OverlayColors.TextDim, fontSize = 12.sp)
            } else {
                ModelSelector(
                    options = available.options,
                    default = available.default,
                    current = settings.chatModel,
                    onSelect = { choice -> state.updateSettings { it.copy(chatModel = choice) } },
                )
                Spacer(Modifier.height(10.dp))
                WebSearchIndicator(available, settings.chatModel)
                Spacer(Modifier.height(6.dp))
                ConsoleHint("Модель генерирует ответы ассистента и выбирает инструменты Destiny 2.")
            }
        }

        message?.let {
            Spacer(Modifier.height(20.dp))
            Text(it, color = OverlayColors.Warn, fontSize = 13.sp)
        }
    }
}
