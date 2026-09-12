package org.example.overlay.audio

/**
 * Работа с PCM16LE-моно.
 *
 * Пересчёт частоты нужен только из-за риска 6: не всякая звуковая карта отдаёт линию ровно
 * на 24000 Гц. Коэффициент строго целый (48000 ↔ 24000), поэтому обходимся усреднением пар
 * и дублированием отсчётов — полноценный ресемплер здесь не нужен и не оправдан.
 */
object Pcm16 {

    const val BYTES_PER_SAMPLE = 2

    /** 48000 → 24000: среднее пары соседних отсчётов. */
    fun decimateByTwo(source: ByteArray): ByteArray {
        require(source.size % (2 * BYTES_PER_SAMPLE) == 0) {
            "Expected a whole number of PCM16 sample pairs, got ${source.size} bytes"
        }
        val result = ByteArray(source.size / 2)
        var read = 0
        var write = 0
        while (read < source.size) {
            val averaged = (readSample(source, read) + readSample(source, read + BYTES_PER_SAMPLE)) / 2
            writeSample(result, write, averaged)
            read += 2 * BYTES_PER_SAMPLE
            write += BYTES_PER_SAMPLE
        }
        return result
    }

    /** 24000 → 48000: каждый отсчёт дублируется. */
    fun upsampleByTwo(source: ByteArray): ByteArray {
        require(source.size % BYTES_PER_SAMPLE == 0) {
            "Expected a whole number of PCM16 samples, got ${source.size} bytes"
        }
        val result = ByteArray(source.size * 2)
        var read = 0
        var write = 0
        while (read < source.size) {
            val sample = readSample(source, read)
            writeSample(result, write, sample)
            writeSample(result, write + BYTES_PER_SAMPLE, sample)
            read += BYTES_PER_SAMPLE
            write += 2 * BYTES_PER_SAMPLE
        }
        return result
    }

    fun readSample(bytes: ByteArray, offset: Int): Int {
        val low = bytes[offset].toInt() and 0xff
        val high = bytes[offset + 1].toInt()
        return ((high shl 8) or low).toShort().toInt()
    }

    fun writeSample(bytes: ByteArray, offset: Int, value: Int) {
        val clamped = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        bytes[offset] = (clamped and 0xff).toByte()
        bytes[offset + 1] = ((clamped shr 8) and 0xff).toByte()
    }
}
