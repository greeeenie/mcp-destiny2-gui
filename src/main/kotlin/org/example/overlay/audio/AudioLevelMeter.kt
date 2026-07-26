package org.example.overlay.audio

import kotlin.math.sqrt

/**
 * Уровень входного чанка для полоски в HUD. Нужен ровно для одного: чтобы «микрофон не тот»
 * было видно сразу, а не после первой неудачной фразы (§5.3).
 */
object AudioLevelMeter {

    fun level(chunk: ByteArray): Float {
        if (chunk.size < Pcm16.BYTES_PER_SAMPLE) return 0f
        var sumOfSquares = 0.0
        var samples = 0
        var offset = 0
        while (offset + Pcm16.BYTES_PER_SAMPLE <= chunk.size) {
            val normalized = Pcm16.readSample(chunk, offset).toDouble() / Short.MAX_VALUE
            sumOfSquares += normalized * normalized
            samples++
            offset += Pcm16.BYTES_PER_SAMPLE
        }
        if (samples == 0) return 0f
        return sqrt(sumOfSquares / samples).toFloat().coerceIn(0f, 1f)
    }
}
