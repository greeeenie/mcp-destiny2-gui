package org.example.overlay.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Уровень входного чанка для полоски в HUD. Нужен ровно для одного: чтобы «микрофон не тот»
 * было видно сразу, а не после первой неудачной фразы (§5.3).
 *
 * Шкала децибельная, как у VU-метра. Сырой RMS для полоски не годится: обычная речь — это
 * 0.02–0.2 от максимума, и линейная шкала заполняла считанные проценты дорожки, будто звука
 * почти нет. Логарифм растягивает рабочий низ диапазона: тихая речь — примерно полшкалы,
 * громкая — ближе к концу.
 */
object AudioLevelMeter {

    /** Рабочий диапазон шкалы: RMS тише −50 дБ считается тишиной, 0 дБ — полная полоска. */
    private const val DB_RANGE = 50.0

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
        val rms = sqrt(sumOfSquares / samples)
        if (rms <= 0.0) return 0f
        val decibels = 20.0 * log10(rms)
        return (1.0 + decibels / DB_RANGE).coerceIn(0.0, 1.0).toFloat()
    }
}
