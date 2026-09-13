package org.example.overlay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.overlay.app.AppState
import org.example.overlay.backend.ProviderApiKeys
import org.example.overlay.backend.VoiceModelOption

/** Модель ответа и её контекст отделены от распознавания речи. */
@Composable
fun AssistantScreen(state: AppState) {
    val settings by state.settings.collectAsState()
    val models by state.chatModels.collectAsState()
    val providerApiKeys by state.providerApiKeys.collectAsState()
    val message by state.voiceMessage.collectAsState()

    Column(Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConsoleUtilityButton(
                text = "Clear history",
                icon = { HistoryActionIcon() },
                onClick = state::clearConversation,
                emphasized = true,
                modifier = Modifier.width(232.dp),
            )
            models?.let { available ->
                WebSearchToggle(
                    models = available,
                    chatModel = settings.chatModel,
                    enabled = settings.webSearchEnabled,
                    onEnabledChange = { enabled -> state.updateSettings { it.copy(webSearchEnabled = enabled) } },
                    modifier = Modifier.width(220.dp),
                )
            }
        }

        Spacer(Modifier.height(30.dp))
        ConsoleSection("Response model") {
            val available = models
            if (available == null) {
                Text("Loading models…", color = OverlayColors.TextDim, fontSize = 12.sp)
            } else {
                ProviderModelSelector(
                    options = available.options,
                    default = available.default,
                    current = settings.chatModel,
                    onSelect = { choice -> state.updateSettings { it.copy(chatModel = choice) } },
                    providerApiKeys = providerApiKeys,
                    onApiKeySave = state::updateProviderApiKey,
                )
            }
        }

        message?.let {
            Spacer(Modifier.height(20.dp))
            Text(it, color = OverlayColors.Warn, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ProviderModelSelector(
    options: List<VoiceModelOption>,
    default: String,
    current: String?,
    onSelect: (String?) -> Unit,
    providerApiKeys: ProviderApiKeys,
    onApiKeySave: (String, String) -> Unit,
) {
    val selectedModel = current?.takeIf { id -> options.any { it.id == id } }
    val providers = options.map(VoiceModelOption::provider).distinct().sortedBy { if (it == "OPEN_ROUTER") 0 else 1 }
    val selectedProvider = options.firstOrNull { it.id == (selectedModel ?: default) }?.provider
        ?: providers.firstOrNull().orEmpty()
    var activeProvider by remember(options, selectedModel, default) { mutableStateOf(selectedProvider) }
    var apiKey by remember(activeProvider, providerApiKeys) {
        mutableStateOf(providerApiKeys.forProvider(activeProvider))
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            providers.forEach { provider ->
                ConsoleChoice(
                    text = provider.displayName(),
                    selected = provider == activeProvider,
                    onClick = { activeProvider = provider },
                )
            }
        }
        ModelSelector(
            options = options.filter { it.provider == activeProvider },
            default = default,
            current = selectedModel,
            onSelect = onSelect,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(activeProvider.apiKeyLabel()) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.width(420.dp),
        )
        if (activeProvider == "INWORLD") {
            Text("Base64-encoded key:secret.", color = OverlayColors.TextDim, fontSize = 11.sp)
        }
        Button(
            onClick = { onApiKeySave(activeProvider, apiKey) },
            enabled = apiKey.isNotBlank(),
            shape = RectangleShape,
        ) {
            Text("Save key")
        }
    }
}

private fun String.apiKeyLabel(): String = when (this) {
    "INWORLD" -> "Inworld API key"
    "OPEN_ROUTER" -> "OpenRouter API key"
    else -> "API key"
}

private fun String.displayName(): String = when (this) {
    "INWORLD" -> "INWORLD"
    "OPEN_ROUTER" -> "OPENROUTER"
    else -> replace('_', ' ').uppercase()
}
