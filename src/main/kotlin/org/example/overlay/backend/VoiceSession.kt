package org.example.overlay.backend

import java.net.URI
import java.time.Instant

/**
 * Ответ `POST /voice/session` (§3.2 плана) — всё, что нужно для одной Realtime-сессии.
 *
 * `sessionUpdate` хранится строкой и уходит в сокет как есть: оверлей его не разбирает и не
 * меняет, поэтому промпт, голос, модель, VAD и набор инструментов правятся на сервере.
 */
data class VoiceAccess(
    val token: String,
    val tokenType: String,
    val expiresAt: Instant,
    val uri: URI,
    val audio: VoiceAudioSettings,
    val client: VoiceClientSettings,
    val sessionUpdate: String,
) {
    val authorizationHeader: String get() = "$tokenType $token"
}

data class VoiceAudioSettings(
    val inputSampleRate: Int,
    val outputSampleRate: Int,
    val chunkMs: Int,
    val chunkBytes: Int,
)

/**
 * Настройки клиента приходят с сервера целиком. Своих значений по умолчанию оверлей не заводит —
 * то же правило, что на бэкенде (§3.2).
 */
data class VoiceClientSettings(
    val connectTimeoutMs: Long,
    val interruptTimeoutMs: Long,
    val reconnectAttempts: Int,
    val reconnectBaseDelayMs: Long,
    val reconnectMaxDelayMs: Long,
    val maxToolCallsPerTurn: Int,
    val toolBudgets: Map<String, Int> = emptyMap(),
)
