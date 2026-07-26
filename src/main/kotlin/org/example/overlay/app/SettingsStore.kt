package org.example.overlay.app

import org.slf4j.LoggerFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Чтение и запись `settings.json`. Битый или частичный файл не должен мешать запуску —
 * любая ошибка разбора означает «берём значения по умолчанию».
 */
class SettingsStore(private val file: Path) {

    fun load(): Settings {
        if (!Files.exists(file)) return Settings()
        return try {
            MAPPER.readValue(Files.readString(file), Settings::class.java)
        } catch (error: Exception) {
            log.warn("Не удалось прочитать {}, беру настройки по умолчанию: {}", file, error.toString())
            Settings()
        }
    }

    /** Пишем через временный файл: обрыв записи не оставит пустой `settings.json`. */
    fun save(settings: Settings) {
        try {
            Files.createDirectories(file.parent)
            val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(temp, MAPPER.writeValueAsString(settings))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            log.warn("Не удалось сохранить {}: {}", file, error.toString())
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(SettingsStore::class.java)
        val MAPPER: JsonMapper = JsonMapper.builder()
            .addModule(kotlinModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()
    }
}
