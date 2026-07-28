package org.example.overlay.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioLevelMeterTest {

    @Test
    fun `тишина даёт ноль`() {
        assertEquals(0f, AudioLevelMeter.level(ByteArray(4800)))
    }

    @Test
    fun `постоянная максимальная амплитуда даёт единицу`() {
        val chunk = ByteArray(4800)
        for (offset in chunk.indices step Pcm16.BYTES_PER_SAMPLE) {
            Pcm16.writeSample(chunk, offset, Short.MAX_VALUE.toInt())
        }

        assertEquals(1f, AudioLevelMeter.level(chunk))
    }

    @Test
    fun `половина амплитуды на децибельной шкале даёт высокий уровень`() {
        val chunk = ByteArray(4800)
        for (offset in chunk.indices step Pcm16.BYTES_PER_SAMPLE) {
            Pcm16.writeSample(chunk, offset, Short.MAX_VALUE / 2)
        }

        // −6 дБ от максимума: на шкале в 50 дБ это почти полная полоска.
        val level = AudioLevelMeter.level(chunk)
        assertTrue(level in 0.8f..0.95f, "уровень $level не похож на −6 дБ")
    }

    @Test
    fun `тихая речь видна на середине шкалы, а не в самом низу`() {
        val chunk = ByteArray(4800)
        for (offset in chunk.indices step Pcm16.BYTES_PER_SAMPLE) {
            // 5% амплитуды — типичный RMS негромкой речи; линейная шкала показывала бы 0.05.
            Pcm16.writeSample(chunk, offset, (Short.MAX_VALUE * 0.05).toInt())
        }

        val level = AudioLevelMeter.level(chunk)
        assertTrue(level in 0.4f..0.6f, "уровень $level не похож на середину шкалы")
    }

    @Test
    fun `пустой чанк не роняет счётчик`() {
        assertEquals(0f, AudioLevelMeter.level(ByteArray(0)))
    }
}
