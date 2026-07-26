package org.example.overlay.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Живые настройки приложения. Отдельно от [AppState], потому что их читает и `BackendClient`
 * (адрес бэкенда), и UI — а состояние разговора им не нужно.
 */
class SettingsHolder(
    private val store: SettingsStore,
    scope: CoroutineScope,
) {
    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    init {
        // Перетаскивание окна даёт десятки событий в секунду — на диск пишем не чаще раза в полсекунды.
        @OptIn(FlowPreview::class)
        scope.launch {
            _settings.drop(1).debounce(SAVE_DEBOUNCE_MS).collect(store::save)
        }
    }

    fun update(transform: (Settings) -> Settings) = _settings.update(transform)

    /** Финальная запись при выходе: debounce-корутина к этому моменту уже отменена. */
    fun flush() = store.save(_settings.value)

    private companion object {
        const val SAVE_DEBOUNCE_MS = 500L
    }
}
