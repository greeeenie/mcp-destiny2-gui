package org.example.overlay.backend

import org.example.overlay.platform.Dpapi
import org.slf4j.LoggerFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Сохранённая сессия бэкенда. Пароль лежит здесь только по явной галочке «запомнить» —
 * по умолчанию поле пустое (§4 плана).
 */
data class StoredSession(
    val username: String,
    val token: String,
    /** Секунды эпохи: не тянем модуль java.time в Jackson ради одного поля. */
    val expiresAtEpochSecond: Long,
    val password: String? = null,
) {
    val expiresAt: Instant get() = Instant.ofEpochSecond(expiresAtEpochSecond)

    fun isExpired(now: Instant = Instant.now()): Boolean = !now.isBefore(expiresAt)

    /** За полчаса до конца показываем ненавязчивый баннер — не рвём разговор на середине. */
    fun isExpiringSoon(now: Instant = Instant.now()): Boolean =
        expiresAt.minusSeconds(EXPIRY_WARNING_SECONDS).isBefore(now)

    private companion object {
        const val EXPIRY_WARNING_SECONDS = 30L * 60
    }
}

/** `session.bin`: JSON, зашифрованный DPAPI под текущего пользователя. */
class SessionStore(private val file: Path) {

    fun load(): StoredSession? {
        if (!Files.exists(file) || !Dpapi.isAvailable) return null
        return try {
            val json = String(Dpapi.unprotect(Files.readAllBytes(file)), Charsets.UTF_8)
            MAPPER.readValue(json, StoredSession::class.java)
        } catch (error: Exception) {
            // Файл от другого пользователя, другой машины или просто битый — начинаем с логина.
            log.warn("Не удалось прочитать сохранённую сессию: {}", error.toString())
            null
        }
    }

    fun save(session: StoredSession) {
        if (!Dpapi.isAvailable) {
            log.warn("DPAPI недоступен — сессия не сохранена")
            return
        }
        try {
            Files.createDirectories(file.parent)
            val encrypted = Dpapi.protect(MAPPER.writeValueAsString(session).toByteArray(Charsets.UTF_8))
            val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.write(temp, encrypted)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            log.warn("Не удалось сохранить сессию: {}", error.toString())
        }
    }

    fun clear() {
        runCatching { Files.deleteIfExists(file) }
            .onFailure { log.warn("Не удалось удалить {}: {}", file, it.toString()) }
    }

    private companion object {
        val log = LoggerFactory.getLogger(SessionStore::class.java)
        val MAPPER: JsonMapper = JsonMapper.builder()
            .addModule(kotlinModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()
    }
}
