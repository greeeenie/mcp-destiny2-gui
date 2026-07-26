package org.example.overlay.audio

enum class PlaybackCompletion {
    PLAYED,
    FLUSHED,
}

interface AudioOutput {
    suspend fun start()

    suspend fun play(bytes: ByteArray)

    /**
     * Поколение воспроизведения. Всё, что было отправлено до `flush()`, отбрасывается —
     * и в очереди, и прямо посреди записи в линию. Это ядро корректного прерывания (§6).
     */
    fun currentPlaybackGeneration(): Long = 0L

    suspend fun play(bytes: ByteArray, generation: Long) {
        if (generation == currentPlaybackGeneration()) play(bytes)
    }

    suspend fun flush()

    suspend fun awaitPlaybackComplete(): PlaybackCompletion = PlaybackCompletion.PLAYED

    suspend fun stop()
}
