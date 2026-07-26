package org.example.overlay.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PcmAudioFormatTest {

    @Test
    fun `24 кГц на 100 мс дают 4800 байт`() {
        // Ровно то число, которое сервер присылает в audio.chunkBytes (§2.2 плана).
        assertEquals(4800, PcmAudioFormat(24_000).chunkBytes(100))
    }

    @Test
    fun `дробное число кадров не принимается`() {
        // На 24000 Гц дробного случая не бывает вовсе, поэтому проверяем на 44100.
        assertFailsWith<IllegalArgumentException> { PcmAudioFormat(44_100).chunkBytes(3) }
    }

    @Test
    fun `размер кадра моно PCM16 равен двум байтам`() {
        assertEquals(2, PcmAudioFormat(24_000).frameSizeBytes)
    }

    @Test
    fun `нулевая частота отвергается`() {
        assertFailsWith<IllegalArgumentException> { PcmAudioFormat(0) }
    }
}
