package org.example.overlay.app

import java.nio.file.Path

/**
 * Пути к данным оверлея. Всё лежит в `%LOCALAPPDATA%\mcp-destiny2-gui` (§2.1 плана):
 * настройки — открытым текстом, сессионный токен и BYOK-ключи — под DPAPI.
 */
class AppPaths(val root: Path = defaultRoot()) {
    val settingsFile: Path = root.resolve("settings.json")
    val sessionFile: Path = root.resolve("session.bin")
    val providerApiKeysFile: Path = root.resolve("provider-api-keys.bin")
    val logsDir: Path = root.resolve("logs")

    companion object {
        fun defaultRoot(): Path {
            val base = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
            return Path.of(base, "mcp-destiny2-gui")
        }
    }
}
