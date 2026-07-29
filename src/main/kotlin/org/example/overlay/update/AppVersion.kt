package org.example.overlay.update

import java.util.Properties

/**
 * Версия самого приложения. Записывается Gradle в `version.properties` при сборке:
 * захардкоженная строка неизбежно разошлась бы с версией MSI.
 */
object AppVersion {
    val current: String by lazy {
        AppVersion::class.java.getResourceAsStream("/version.properties")
            ?.use { stream -> Properties().apply { load(stream) }.getProperty("version") }
            // "${version}" — ресурс попал в classpath мимо processResources (например, из IDE):
            // такая «версия» не сравнивается ни с чем, честнее считать её нулевой.
            ?.takeIf { it.isNotBlank() && !it.startsWith("\${") }
            ?: "0.0.0"
    }
}
