package org.example.overlay.audio

import kotlinx.coroutines.flow.Flow

interface AudioInput {
    val chunks: Flow<ByteArray>

    suspend fun start()

    suspend fun stop()
}

/**
 * Сбой звукового устройства. Донорский классификатор относит такие ошибки к категории
 * `AUDIO_DEVICE` и помечает их неретраибельными: переоткрытие линии — решение уровня UI,
 * а не политики реконнекта (риск 3).
 */
class AudioDeviceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
