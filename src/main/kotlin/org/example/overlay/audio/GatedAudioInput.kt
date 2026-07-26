package org.example.overlay.audio

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Гейт микрофона — донорский `ControlledAudioInput`. Единственный путь звука в облако:
 * физический микрофон принадлежит [MicrophoneHub], а сюда чанки попадают, только если гейт
 * открыт. Это же и защита от эха: своего AEC у нас нет и не планируется (§6).
 */
class GatedAudioInput(capacity: Int) : AudioInput {
    // При отставании потребителя выбрасываем самый старый чанк: для живой речи свежий звук
    // важнее полного, а падать из-за переполнения тут нечестно (§2.2).
    private val channel = Channel<ByteArray>(capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val open = AtomicBoolean()

    val isOpen: Boolean get() = open.get()

    override val chunks: Flow<ByteArray> = channel.receiveAsFlow()

    override suspend fun start() = Unit

    override suspend fun stop() = closeGate()

    fun openGate() {
        drain()
        open.set(true)
    }

    fun offer(bytes: ByteArray): Boolean =
        open.get() && channel.trySend(bytes.copyOf()).isSuccess

    fun closeGate() {
        open.set(false)
        drain()
    }

    private fun drain() {
        while (channel.tryReceive().isSuccess) {
            // Звук, захваченный в другом состоянии маршрутизации, не должен проиграться заново.
        }
    }
}
