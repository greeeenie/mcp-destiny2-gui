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
import org.example.overlay.app.Settings
import org.example.overlay.backend.VoiceModelOption
import org.example.overlay.backend.VoiceModels
import org.example.overlay.input.VirtualKeys

/** Захват речи: push-to-talk, модель STT и язык распознавания. */
@Composable
fun VoiceScreen(state: AppState) {
    val settings by state.settings.collectAsState()
    val message by state.voiceMessage.collectAsState()
    val models by state.chatModels.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionColumns {
            ConsoleSection("Push-to-talk", Modifier.weight(1f)) {
                Text("Клавиша разговора", color = OverlayColors.TextMuted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VirtualKeys.NAMES.entries.chunked(4).forEach { entries ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            entries.forEach { (code, name) ->
                                ConsoleChoice(
                                    text = name,
                                    selected = settings.pttKeyCode == code,
                                    onClick = { state.updateSettings { it.copy(pttKeyCode = code) } },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                ConsoleHint("Нажмите и удерживайте выбранную клавишу, чтобы говорить.")
            }

            androidx.compose.material3.VerticalDivider(
                modifier = Modifier.height(300.dp),
                color = OverlayColors.Divider,
            )

            ConsoleSection("Распознавание голоса", Modifier.weight(1f)) {
                val available = models
                if (available != null && available.sttOptions.isNotEmpty()) {
                    Text("Модель", color = OverlayColors.TextMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    ModelSelector(
                        options = available.sttOptions,
                        default = Settings.DEFAULT_STT_MODEL,
                        current = settings.sttModel,
                        onSelect = { choice -> state.updateSettings { it.copy(sttModel = choice) } },
                    )
                    Spacer(Modifier.height(24.dp))
                }

                Text("Язык", color = OverlayColors.TextMuted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                ModelSelector(
                    options = STT_LANGUAGES,
                    default = STT_LANGUAGE_AUTO,
                    current = settings.sttLanguage,
                    onSelect = { choice -> state.updateSettings { it.copy(sttLanguage = choice) } },
                )
                Spacer(Modifier.height(10.dp))
                ConsoleHint("Авто позволяет смешивать русский и английский в одной фразе; явный язык — если авто ошибается.")
            }
        }

        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = OverlayColors.Warn, fontSize = 13.sp)
        }
    }
}

/** Ряд моделей на выбор. Выбор хранится только для не-дефолта: null означает «как решил сервер»,
 * и смена серверного дефолта тогда подхватывается сама. */
@Composable
internal fun ModelSelector(
    options: List<VoiceModelOption>,
    default: String,
    current: String?,
    onSelect: (String?) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val selected = (current ?: default) == option.id
            ConsoleChoice(
                text = option.label,
                selected = selected,
                onClick = { onSelect(option.id.takeIf { it != default }) },
            )
        }
    }
}

/**
 * Есть ли у выбранной модели веб-поиск. Игрок должен видеть, откуда берутся ответы про мету:
 * без поиска модель отвечает из головы и может отстать от патчей.
 */
@Composable
internal fun WebSearchIndicator(models: VoiceModels, chatModel: String?) {
    val selected = models.options.firstOrNull { it.id == (chatModel ?: models.default) }
    if (selected?.webSearch == true) {
        Text("●  Веб-поиск включён", color = OverlayColors.Ok, fontSize = 11.sp)
    } else {
        Text("○  Веб-поиск выключен", color = OverlayColors.TextDim, fontSize = 11.sp)
    }
}

/** Локальный список: серверу уходит код ISO 639-1, «auto» означает «язык не передавать». */
private const val STT_LANGUAGE_AUTO = "auto"

private val STT_LANGUAGES = listOf(
    VoiceModelOption(id = STT_LANGUAGE_AUTO, label = "Авто"),
    VoiceModelOption(id = "ru", label = "Русский"),
    VoiceModelOption(id = "en", label = "English"),
)
