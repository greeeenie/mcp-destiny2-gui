package org.example.overlay.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.example.overlay.backend.VoiceEventDto
import org.example.overlay.conversation.ConversationEvent
import org.example.overlay.conversation.ConversationState
import org.slf4j.LoggerFactory

/**
 * Пересылает ход разговора на бэкенд (§3.3): реплики игрока и модели идут между оверлеем
 * и Inworld напрямую, и без этой отправки в серверном журнале видны одни вызовы инструментов.
 *
 * Журнал не должен мешать разговору, поэтому отправка асинхронная и необязательная: события
 * кладутся в очередь без ожидания, при переполнении вытесняются старые, а ошибки гасятся —
 * падать из-за недоставленной строки лога незачем.
 */
class VoiceEventReporter(
    private val scope: CoroutineScope,
    private val send: suspend (List<VoiceEventDto>) -> Unit,
) {
    private val queue = Channel<VoiceEventDto>(QUEUE_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var pump: Job? = null

    fun start() {
        if (pump != null) return
        pump = scope.launch { drain() }
    }

    fun stop() {
        pump?.cancel()
        pump = null
    }

    /** Вызывается из обработчика событий движка: не приостанавливается и не бросает. */
    fun report(event: ConversationEvent) {
        val dto = event.toDto() ?: return
        queue.trySend(dto)
    }

    /**
     * Копит события окном в несколько секунд: реплика, вызов инструмента и ответ уезжают
     * одним запросом вместо трёх.
     */
    private suspend fun drain() {
        val batch = mutableListOf<VoiceEventDto>()
        while (currentCoroutineContext().isActive) {
            batch += queue.receive()
            withTimeoutOrNull(BATCH_WINDOW_MS) {
                while (batch.size < BATCH_LIMIT) {
                    batch += queue.receive()
                }
            }
            runCatching { send(batch.toList()) }
                .onFailure { error ->
                    // Только debug: потерянный журнал — не та беда, о которой стоит сообщать игроку.
                    log.debug("Не удалось отправить события разговора ({} шт.): {}", batch.size, error.message)
                }
            batch.clear()
        }
    }

    /** Промежуточные куски транскрипта, аудиочанки и служебные состояния в журнал не идут. */
    private fun ConversationEvent.toDto(): VoiceEventDto? = when (this) {
        is ConversationEvent.Transcript -> takeIf { isFinal && text.isNotBlank() }?.let {
            VoiceEventDto(
                type = "transcript",
                speaker = if (speaker == ConversationEvent.Speaker.USER) "user" else "assistant",
                text = text.take(MAX_TEXT_CHARS),
            )
        }

        is ConversationEvent.ToolCompleted -> VoiceEventDto(type = "tool", name = name, status = status)

        is ConversationEvent.StateChanged -> when (val current = state) {
            is ConversationState.Failed ->
                VoiceEventDto(type = "error", text = current.failure.message.take(MAX_TEXT_CHARS))

            is ConversationState.Active -> VoiceEventDto(type = "state", text = "ACTIVE")
            is ConversationState.Reconnecting -> VoiceEventDto(type = "state", text = "RECONNECTING")
            else -> null
        }

        else -> null
    }

    private companion object {
        const val QUEUE_CAPACITY = 64
        const val BATCH_LIMIT = 16
        const val BATCH_WINDOW_MS = 3_000L

        /** Сервер отвергает текст длиннее 4000 символов (§3.1). */
        const val MAX_TEXT_CHARS = 4_000
        val log = LoggerFactory.getLogger(VoiceEventReporter::class.java)
    }
}
