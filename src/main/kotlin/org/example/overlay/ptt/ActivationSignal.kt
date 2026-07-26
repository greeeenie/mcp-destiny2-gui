package org.example.overlay.ptt

/**
 * Звуковое подтверждение маршрутизации микрофона. В шлеме это надёжнее визуального
 * индикатора: глаза заняты игрой (§5.3).
 */
interface ActivationSignal {
    suspend fun opened()

    suspend fun closed()
}
