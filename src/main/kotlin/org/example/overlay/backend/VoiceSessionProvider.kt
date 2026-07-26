package org.example.overlay.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.overlay.audio.PcmAudioFormat
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Источник доступа к Inworld. Интерфейс нужен движку: так его цикл соединения проверяется
 * тестами без сети.
 */
interface VoiceAccessProvider {
    suspend fun get(): VoiceAccess

    /** Сбросить кэш: авторизационная ошибка означает, что токен пора перевыпустить. */
    suspend fun invalidate()
}

/**
 * Доступ к Inworld живёт только в памяти — на диск не пишется вовсе (§2.1).
 *
 * Правило, снимающее проблему «токен истёк посреди разговора»: срок проверяется перед каждым
 * `connect()`, а не во время сессии (§4). Разговор длиннее четырёх часов физически не бывает.
 */
class VoiceSessionProvider(
    private val backend: BackendClient,
    private val tokenProvider: () -> String?,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
) : VoiceAccessProvider {
    private val mutex = Mutex()
    private var cached: VoiceAccess? = null
    private var refreshJob: Job? = null

    override suspend fun get(): VoiceAccess = mutex.withLock {
        val current = cached
        if (current != null && remaining(current) > CACHE_MIN_REMAINING) {
            scheduleBackgroundRefresh(current)
            return current
        }
        fetch().also { cached = it }
    }

    override suspend fun invalidate() = mutex.withLock {
        cached = null
    }

    /** Срок текущего доступа — по нему решается тихая ротация сокета (§4). */
    fun cachedExpiry(): Instant? = cached?.expiresAt

    private suspend fun fetch(): VoiceAccess {
        val token = tokenProvider() ?: throw UnauthorizedException("Нет сессии бэкенда — нужен вход")
        val access = backend.voiceSession(token)
        verifyChunkBytes(access)
        log.info("Получен доступ к Inworld до {} ({} мин)", access.expiresAt, remaining(access).toMinutes())
        return access
    }

    /**
     * Сервер считает `chunkBytes` сам; оверлей его не вычисляет, а сверяет со своим расчётом
     * и падает на несовпадении (§2.2) — иначе рассинхрон формата вылезет искажённым звуком.
     */
    private fun verifyChunkBytes(access: VoiceAccess) {
        val expected = PcmAudioFormat(access.audio.inputSampleRate).chunkBytes(access.audio.chunkMs)
        require(expected == access.audio.chunkBytes) {
            "Сервер прислал chunkBytes=${access.audio.chunkBytes}, а из ${access.audio.inputSampleRate} Гц " +
                "и ${access.audio.chunkMs} мс получается $expected"
        }
    }

    /**
     * Пока сокет открыт, обновляем кэш заранее и в фоне: возможный реконнект не должен ждать
     * HTTP-запроса к бэкенду.
     */
    private fun scheduleBackgroundRefresh(current: VoiceAccess) {
        if (remaining(current) > BACKGROUND_REFRESH_THRESHOLD) return
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            runCatching { fetch() }
                .onSuccess { fresh -> mutex.withLock { cached = fresh } }
                .onFailure { log.warn("Фоновое обновление доступа к Inworld не удалось: {}", it.toString()) }
        }
    }

    private fun remaining(access: VoiceAccess): Duration = Duration.between(clock(), access.expiresAt)

    private companion object {
        val log = LoggerFactory.getLogger(VoiceSessionProvider::class.java)

        /** Меньше десяти минут до конца — берём новый токен, а не рискуем серединой разговора. */
        val CACHE_MIN_REMAINING: Duration = Duration.ofMinutes(10)
        val BACKGROUND_REFRESH_THRESHOLD: Duration = Duration.ofMinutes(15)
    }
}
