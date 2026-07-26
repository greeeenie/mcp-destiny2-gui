package org.example.overlay.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Владелец физического микрофона: читает чанки из линии и отдаёт их одному потребителю
 * (в нашем случае — контроллеру push-to-talk, который решает, открывать ли гейт).
 */
class MicrophoneHub(
    private val source: AudioInput,
    private val scope: CoroutineScope,
    private val onChunk: suspend (ByteArray) -> Unit,
) {
    private val lifecycle = Mutex()
    private var collectionJob: Job? = null

    suspend fun start() = lifecycle.withLock {
        if (collectionJob != null) return
        source.start()
        collectionJob = scope.launch {
            // Копируем на всякий случай: источник вправе переиспользовать буфер захвата.
            source.chunks.collect { bytes -> onChunk(bytes.copyOf()) }
        }
    }

    suspend fun stop() {
        val job = lifecycle.withLock {
            val activeJob = collectionJob ?: return
            collectionJob = null
            activeJob
        }
        job.cancelAndJoin()
        source.stop()
    }
}
