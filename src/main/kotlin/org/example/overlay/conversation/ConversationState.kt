package org.example.overlay.conversation

sealed interface ConversationState {
    data object Idle : ConversationState
    data object Connecting : ConversationState
    data class Active(val sessionId: String) : ConversationState
    data class Reconnecting(val attempt: Int) : ConversationState
    data object Stopping : ConversationState
    data class Failed(val failure: ConversationFailure) : ConversationState
}

data class ConversationFailure(
    val category: Category,
    val message: String,
    val retryable: Boolean,
) {
    enum class Category {
        CONFIGURATION,
        AUTHENTICATION,
        QUOTA,
        TRANSPORT,
        PROTOCOL,
        AUDIO_DEVICE,
        TOOL,
        UNKNOWN,
    }
}
