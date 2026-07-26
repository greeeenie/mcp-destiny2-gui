package org.example.overlay.conversation

sealed interface ConversationEvent {
    data class StateChanged(val state: ConversationState) : ConversationEvent

    data class Transcript(
        val speaker: Speaker,
        val text: String,
        val isFinal: Boolean,
    ) : ConversationEvent

    data class Diagnostic(val type: String) : ConversationEvent

    data class AudioChunk(
        val bytes: ByteArray,
        val responseGeneration: Long,
    ) : ConversationEvent {
        override fun equals(other: Any?): Boolean =
            other is AudioChunk &&
                responseGeneration == other.responseGeneration &&
                bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + responseGeneration.hashCode()
    }

    data class ResponseStarted(val responseGeneration: Long) : ConversationEvent

    data object SpeechStarted : ConversationEvent
    data object ResponseCompleted : ConversationEvent

    /** Инструмент отработал — строка для лога в HUD (§5.3). */
    data class ToolCompleted(
        val name: String,
        val status: String,
        val durationMs: Long,
        val message: String? = null,
    ) : ConversationEvent

    enum class Speaker {
        USER,
        ASSISTANT,
    }
}
