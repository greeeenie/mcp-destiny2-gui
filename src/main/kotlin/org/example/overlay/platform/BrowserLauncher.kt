package org.example.overlay.platform

import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.net.URI

/**
 * Открытие ссылки авторизации Bungie. Если браузер не открылся, ссылка всё равно показана
 * в HUD мелким шрифтом — игрок скопирует её руками (§5.3).
 */
object BrowserLauncher {

    fun open(url: String): Boolean = try {
        val desktop = Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() }
        if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
            desktop.browse(URI.create(url))
            true
        } else {
            log.warn("Открытие браузера не поддерживается в этой системе")
            false
        }
    } catch (error: Exception) {
        log.warn("Не удалось открыть браузер: {}", error.toString())
        false
    }

    private val log = LoggerFactory.getLogger(BrowserLauncher::class.java)
}
