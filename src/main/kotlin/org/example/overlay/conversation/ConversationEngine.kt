package org.example.overlay.conversation

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface ConversationEngine {
    val state: StateFlow<ConversationState>
    val events: SharedFlow<ConversationEvent>

    suspend fun start()

    suspend fun interrupt(): Boolean

    suspend fun stop()
}

/**
 * Движок Realtime-сессии. Отдельно от [ConversationEngine] ради `commitInputAudio()`: это
 * запасной путь закрытия хода (§5.2), и контроллеру push-to-talk нужен именно он.
 */
interface RealtimeConversationEngine : ConversationEngine {
    suspend fun commitInputAudio(): Boolean
}
