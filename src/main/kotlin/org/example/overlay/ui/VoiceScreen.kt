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
import org.example.overlay.backend.VoiceModels
import org.example.overlay.input.VirtualKeys

/** Управление голосовым трактом: микрофон, клавиша push-to-talk и модель ответа (§5). */
@Composable
fun VoiceScreen(state: AppState) {
    val settings by state.settings.collectAsState()
    val message by state.voiceMessage.collectAsState()
    val models by state.chatModels.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Голос всегда наготове: кнопка нужна только чтобы переоткрыть линии после
            // смены устройства или обрыва.
            Button(onClick = { state.restartVoice() }) { Text("Переоткрыть микрофон") }
            // Сброс контекста: модель начнёт имитировать не свои прошлые ответы, а промпт.
            OutlinedButton(onClick = { state.clearConversation() }) { Text("Сбросить историю") }
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
        models?.let { available ->
            Spacer(Modifier.height(16.dp))
            ModelSelector(
                title = "Модель ответа",
                options = available.options,
                default = available.default,
                current = settings.chatModel,
                onSelect = { choice -> state.updateSettings { it.copy(chatModel = choice) } },
            )
            WebSearchIndicator(available, settings.chatModel)
        }

        Spacer(Modifier.height(16.dp))
        ModelSelector(
            title = "Язык распознавания",
            options = STT_LANGUAGES,
            default = STT_LANGUAGE_AUTO,
            current = settings.sttLanguage,
            hint = "Авто позволяет мешать русский и английский в одной фразе; явный язык — если авто ошибается.",
            onSelect = { choice -> state.updateSettings { it.copy(sttLanguage = choice) } },
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
    hint: String? = null,
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
        hint?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = OverlayColors.TextDim, fontSize = 11.sp)
        }
    }
}

/**
 * Есть ли у выбранной модели веб-поиск. Игрок должен видеть, откуда берутся ответы про мету:
 * без поиска модель отвечает из головы и может отстать от патчей.
 */
@Composable
private fun WebSearchIndicator(models: VoiceModels, chatModel: String?) {
    val selected = models.options.firstOrNull { it.id == (chatModel ?: models.default) }
    Spacer(Modifier.height(4.dp))
    if (selected?.webSearch == true) {
        Text("Веб-поиск включён", color = OverlayColors.Ok, fontSize = 11.sp)
    } else {
        Text("Веб-поиск выключен", color = OverlayColors.TextDim, fontSize = 11.sp)
    }
}

/** Локальный список: серверу уходит код ISO 639-1, «auto» означает «язык не передавать». */
private const val STT_LANGUAGE_AUTO = "auto"

private val STT_LANGUAGES = listOf(
    VoiceModelOption(id = STT_LANGUAGE_AUTO, label = "Авто"),
    VoiceModelOption(id = "ru", label = "Русский"),
    VoiceModelOption(id = "en", label = "English"),
)

