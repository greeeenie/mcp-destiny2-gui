package org.example.overlay.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Pcm16Test {

    private fun samples(vararg values: Int): ByteArray {
        val bytes = ByteArray(values.size * Pcm16.BYTES_PER_SAMPLE)
        values.forEachIndexed { index, value -> Pcm16.writeSample(bytes, index * Pcm16.BYTES_PER_SAMPLE, value) }
        return bytes
    }

    private fun read(bytes: ByteArray): List<Int> =
        (0 until bytes.size / Pcm16.BYTES_PER_SAMPLE).map { Pcm16.readSample(bytes, it * Pcm16.BYTES_PER_SAMPLE) }

    @Test
    fun `отрицательные отсчёты переживают запись и чтение`() {
        assertEquals(listOf(0, -1, 32767, -32768, 1234), read(samples(0, -1, 32767, -32768, 1234)))
    }

    @Test
    fun `децимация вдвое усредняет пары отсчётов`() {
        val source = samples(100, 200, -100, -200, 32767, 32767)

        val result = Pcm16.decimateByTwo(source)

        assertEquals(source.size / 2, result.size)
        assertEquals(listOf(150, -150, 32767), read(result))
    }

    @Test
    fun `децимация требует целое число пар`() {
        assertFailsWith<IllegalArgumentException> { Pcm16.decimateByTwo(samples(1, 2, 3)) }
    }

    @Test
    fun `апсемплинг вдвое дублирует отсчёты`() {
        val result = Pcm16.upsampleByTwo(samples(5, -7))

        assertEquals(listOf(5, 5, -7, -7), read(result))
    }

    @Test
    fun `децимация после апсемплинга возвращает исходный сигнал`() {
        val source = samples(0, 1000, -1000, 32000, -32000)

        assertEquals(read(source), read(Pcm16.decimateByTwo(Pcm16.upsampleByTwo(source))))
    }
}
