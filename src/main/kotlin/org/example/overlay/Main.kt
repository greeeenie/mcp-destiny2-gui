package org.example.overlay

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.example.overlay.app.AppPaths
import org.example.overlay.app.AppState
import org.example.overlay.app.SettingsHolder
import org.example.overlay.app.SettingsStore
import org.example.overlay.backend.BackendClient
import org.example.overlay.backend.BackendToolExecutor
import org.example.overlay.backend.SessionStore
import org.example.overlay.backend.VoiceSessionProvider
import org.example.overlay.ui.ConsoleWindow
import org.example.overlay.ui.HudWindow
import org.example.overlay.ui.TrayIconPainter

/**
 * Сборка графа руками, без DI-фреймворка (§2 плана). Потоков ровно два семейства:
 * AWT EDT под Compose и общий `CoroutineScope` приложения.
 */
fun main() {
    val paths = AppPaths()
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsHolder = SettingsHolder(SettingsStore(paths.settingsFile), appScope)
    val httpClient = BackendClient.defaultHttpClient()
    // Адрес бэкенда читается на каждый запрос: его можно поменять в консоли без перезапуска.
    val backend = BackendClient(baseUrl = { settingsHolder.current.baseUrl }, http = httpClient)
    val sessionStore = SessionStore(paths.sessionFile)

    // Ссылка на состояние появляется позже, поэтому токен читается через держатель.
    lateinit var state: AppState
    val tokenProvider = { state.session.value?.token }

    val voiceSessionProvider = VoiceSessionProvider(backend, tokenProvider, appScope)
    val toolExecutor = BackendToolExecutor(
        backend = backend,
        tokenProvider = tokenProvider,
        onResult = { name, result -> state.onToolResult(name, result) },
    )

    state = AppState(
        settingsHolder = settingsHolder,
        sessionStore = sessionStore,
        backend = backend,
        scope = appScope,
        voiceSessionProvider = voiceSessionProvider,
        toolExecutor = toolExecutor,
        httpClient = httpClient,
    )

    // Первый запуск: без выбранных устройств и клавиши оверлей бесполезен, поэтому открываем консоль.
    if (!settingsHolder.current.firstRunCompleted) state.setConsoleVisible(true)

    application {
        val consoleVisible by state.consoleVisible.collectAsState()

        Tray(
            icon = TrayIconPainter,
            tooltip = "mcp-destiny2 overlay",
            onAction = { state.toggleConsole() },
            menu = {
                Item("Консоль", onClick = { state.setConsoleVisible(true) })
                Item("Выход", onClick = { exitApplication() })
            },
        )

        HudWindow(state)

        if (consoleVisible) {
            ConsoleWindow(state, paths, onClose = { state.setConsoleVisible(false) })
        }
    }

    appScope.cancel()
    // Debounce-корутина уже мертва — дописываем последнее состояние настроек сами.
    state.flushSettings()
}
