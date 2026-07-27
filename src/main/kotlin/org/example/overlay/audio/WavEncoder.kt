package org.example.overlay.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Обёртка сырого PCM16 в WAV.
 *
 * Нужна затем, что расшифровка на сервере принимает файл и определяет кодек сама: сырые
 * отсчёты без заголовка ей опознать не по чему, а WAV — это те же отсчёты плюс 44 байта.
 */
object WavEncoder {

    private const val HEADER_BYTES = 44
    private const val PCM_FORMAT = 1.toShort()
    private const val BITS_PER_SAMPLE = 16.toShort()

    fun encode(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels > 0) { "channels must be positive" }

        val byteRate = sampleRate * channels * BITS_PER_SAMPLE / 8
        val blockAlign = (channels * BITS_PER_SAMPLE / 8).toShort()
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(HEADER_BYTES - 8 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(PCM_FORMAT)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign)
        header.putShort(BITS_PER_SAMPLE)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)

        return ByteArrayOutputStream(HEADER_BYTES + pcm.size).apply {
            write(header.array())
            write(pcm)
        }.toByteArray()
    }
}
