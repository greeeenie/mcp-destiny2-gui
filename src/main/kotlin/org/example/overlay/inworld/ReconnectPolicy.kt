package org.example.overlay.inworld

import org.example.overlay.audio.AudioDeviceException
import org.example.overlay.backend.VoiceClientSettings
import org.example.overlay.conversation.ConversationFailure
import java.net.http.WebSocketHandshakeException
import kotlin.math.pow

/**
 * Политика реконнекта. Единственное отличие от донора — источник настроек: не локальный
 * `InworldProperties`, а блок `client` из `POST /voice/session` (§1, §3.2).
 */
class ReconnectPolicy(
    private val settings: VoiceClientSettings,
    private val jitter: () -> Double = Math::random,
) {
    fun shouldRetry(failure: ConversationFailure, retriesCompleted: Int): Boolean =
        failure.retryable && retriesCompleted < settings.reconnectAttempts

    fun delayMillis(retryNumber: Int): Long {
        require(retryNumber > 0) { "retryNumber must be positive" }
        val exponential = settings.reconnectBaseDelayMs * 2.0.pow(retryNumber - 1)
        val capped = exponential.coerceAtMost(settings.reconnectMaxDelayMs.toDouble())
        val jitterFactor = 0.75 + jitter().coerceIn(0.0, 1.0) * 0.5
        return (capped * jitterFactor).toLong().coerceAtLeast(1)
    }
}

object InworldFailureClassifier {
    fun classify(error: Throwable): ConversationFailure {
        if (error is InworldConnectionException) return error.failure
        if (error is WebSocketHandshakeException) {
            return when (error.response.statusCode()) {
                401, 403 -> failure(ConversationFailure.Category.AUTHENTICATION, "Inworld отверг авторизацию", false)
                429 -> failure(ConversationFailure.Category.QUOTA, "Квота Inworld исчерпана", false)
                else -> failure(ConversationFailure.Category.TRANSPORT, "Рукопожатие с Inworld не удалось", true)
            }
        }
        if (error is InworldProtocolException) {
            return failure(ConversationFailure.Category.PROTOCOL, error.message.orEmpty(), false)
        }
        if (error is AudioDeviceException) {
            return failure(ConversationFailure.Category.AUDIO_DEVICE, error.message.orEmpty(), false)
        }
        return failure(ConversationFailure.Category.TRANSPORT, safeMessage(error), true)
    }

    fun remote(code: String, message: String): InworldConnectionException {
        val normalized = code.uppercase()
        val category = when {
            "AUTH" in normalized || normalized in setOf("UNAUTHENTICATED", "PERMISSION_DENIED") ->
                ConversationFailure.Category.AUTHENTICATION
            "QUOTA" in normalized || "RESOURCE" in normalized || "RATE_LIMIT" in normalized ->
                ConversationFailure.Category.QUOTA
            else -> ConversationFailure.Category.PROTOCOL
        }
        return InworldConnectionException(failure(category, message, retryable = false))
    }

    private fun failure(
        category: ConversationFailure.Category,
        message: String,
        retryable: Boolean,
    ) = ConversationFailure(category, message.take(200), retryable)

    private fun safeMessage(error: Throwable): String =
        error.message?.take(200) ?: error::class.simpleName.orEmpty()
}

class InworldConnectionException(
    val failure: ConversationFailure,
) : RuntimeException(failure.message)

class RealtimeTransportClosedException : RuntimeException("Соединение с Inworld Realtime закрыто")
