package org.example.overlay.ui

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.overlay.app.AppState
import org.example.overlay.app.Settings
import org.example.overlay.audio.AudioDevice
import org.example.overlay.audio.AudioDevices
import org.example.overlay.backend.VoiceModelOption
import org.example.overlay.backend.VoiceModels
import org.example.overlay.input.VirtualKeys

/** Захват речи: микрофон, push-to-talk, модель STT и язык распознавания. */
@Composable
fun VoiceScreen(state: AppState) {
    val settings by state.settings.collectAsState()
    val message by state.voiceMessage.collectAsState()
    val models by state.chatModels.collectAsState()
    val inputs = remember { AudioDevices.inputs() }

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
                modifier = Modifier.height(260.dp),
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

        Spacer(Modifier.height(24.dp))

        ConsoleSection("Микрофон") {
            MicrophoneDeviceList(
                devices = inputs,
                selectedName = settings.audio.inputMixer,
                onSelect = { device ->
                    val name = device.mixer?.let { device.name }
                    state.updateSettings { it.copy(audio = it.audio.copy(inputMixer = name)) }
                },
            )
        }

        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = OverlayColors.Warn, fontSize = 13.sp)
        }
    }
}

@Composable
private fun MicrophoneDeviceList(
    devices: List<AudioDevice>,
    selectedName: String?,
    onSelect: (AudioDevice) -> Unit,
) {
    val listState = rememberLazyListState()
    // Высота — ровно три строки: постоянный скроллбар справа подсказывает, что ниже есть ещё.
    Box(modifier = Modifier.width(340.dp).height(123.dp)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 14.dp)) {
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = ScrollbarStyle(
                minimalHeight = 16.dp,
                thickness = 6.dp,
                shape = RectangleShape,
                hoverDurationMillis = 300,
                unhoverColor = OverlayColors.ControlBorder,
                hoverColor = OverlayColors.ControlBorderStrong,
            ),
        )
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
                supportingText = option.priceLabel(),
            )
        }
    }
}

private fun VoiceModelOption.priceLabel(): String? {
    val input = inputPricePerMillion ?: return null
    val output = outputPricePerMillion ?: return null
    return "IN \$${"%.2f".format(java.util.Locale.US, input)} · " +
        "OUT \$${"%.2f".format(java.util.Locale.US, output)} / 1M"
}

/**
 * Есть ли у выбранной модели веб-поиск. Игрок должен видеть, откуда берутся ответы про мету:
 * без поиска модель отвечает из головы и может отстать от патчей.
 */
@Composable
internal fun WebSearchIndicator(models: VoiceModels, chatModel: String?) {
    val selectedId = chatModel?.takeIf { id -> models.options.any { it.id == id } } ?: models.default
    val selected = models.options.firstOrNull { it.id == selectedId }
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
