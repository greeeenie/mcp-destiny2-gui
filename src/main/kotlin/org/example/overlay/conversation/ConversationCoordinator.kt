package org.example.overlay.conversation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Сериализует запуск и остановку сессии: две горячие клавиши подряд не должны открыть два сокета. */
class ConversationCoordinator(
    private val engine: ConversationEngine,
) {
    private val lifecycleMutex = Mutex()
    private var started = false

    suspend fun startSession(): Boolean = lifecycleMutex.withLock {
        if (started) return false

        started = true
        try {
            engine.start()
            true
        } catch (error: Throwable) {
            started = false
            throw error
        }
    }

    suspend fun stopSession(): Boolean = lifecycleMutex.withLock {
        if (!started) return false

        started = false
        engine.stop()
        true
    }
}
