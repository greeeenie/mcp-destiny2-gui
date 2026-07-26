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
    fun `половина амплитуды даёт примерно половину уровня`() {
        val chunk = ByteArray(4800)
        for (offset in chunk.indices step Pcm16.BYTES_PER_SAMPLE) {
            Pcm16.writeSample(chunk, offset, Short.MAX_VALUE / 2)
        }

        val level = AudioLevelMeter.level(chunk)
        assertTrue(level in 0.45f..0.55f, "уровень $level не похож на половину")
    }

    @Test
    fun `пустой чанк не роняет счётчик`() {
        assertEquals(0f, AudioLevelMeter.level(ByteArray(0)))
    }
}
