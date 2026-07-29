package org.example.overlay

import androidx.compose.runtime.LaunchedEffect
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
import org.example.overlay.backend.SessionStore
import org.example.overlay.ui.ConsoleWindow
import org.example.overlay.ui.HudWindow
import org.example.overlay.ui.TrayIconPainter
import org.example.overlay.update.UpdateManager

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
    val updates = UpdateManager(scope = appScope, http = httpClient, mapper = BackendClient.MAPPER)

    val state = AppState(
        settingsHolder = settingsHolder,
        sessionStore = sessionStore,
        backend = backend,
        updates = updates,
        scope = appScope,
    )

    // Без входа оверлей бесполезен: голос упрётся в «нет сессии бэкенда». Сессию `AppState`
    // восстанавливает в конструкторе, поэтому проверка здесь уже видит результат.
    if (state.session.value == null) state.setConsoleVisible(true)

    application {
        val consoleVisible by state.consoleVisible.collectAsState()
        val installStarted by state.updateInstallStarted.collectAsState()

        // Установщик обновления запущен — уходим: MSI не заменит файлы работающего процесса.
        // Настройки допишутся штатно, как при любом выходе (см. flushSettings ниже).
        LaunchedEffect(installStarted) {
            if (installStarted) exitApplication()
        }

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
            ConsoleWindow(state, onClose = { state.setConsoleVisible(false) })
        }
    }

    appScope.cancel()
    // Debounce-корутина уже мертва — дописываем последнее состояние настроек сами.
    state.flushSettings()
}
