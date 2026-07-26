package org.example.overlay.audio

import javax.sound.sampled.AudioFormat

data class PcmAudioFormat(
    val sampleRate: Int,
    val channels: Int = 1,
    val sampleSizeBits: Int = 16,
    val signed: Boolean = true,
    val bigEndian: Boolean = false,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels > 0) { "channels must be positive" }
        require(sampleSizeBits > 0 && sampleSizeBits % Byte.SIZE_BITS == 0) {
            "sampleSizeBits must contain whole bytes"
        }
    }

    val frameSizeBytes: Int = channels * sampleSizeBits / Byte.SIZE_BITS

    fun toJavax(): AudioFormat = AudioFormat(
        if (signed) AudioFormat.Encoding.PCM_SIGNED else AudioFormat.Encoding.PCM_UNSIGNED,
        sampleRate.toFloat(),
        sampleSizeBits,
        channels,
        frameSizeBytes,
        sampleRate.toFloat(),
        bigEndian,
    )

    fun chunkBytes(chunkMs: Int): Int {
        require(chunkMs > 0) { "chunkMs must be positive" }
        val frames = sampleRate.toLong() * chunkMs / MILLIS_PER_SECOND
        require(frames > 0 && frames * MILLIS_PER_SECOND == sampleRate.toLong() * chunkMs) {
            "chunkMs must produce a whole number of PCM frames"
        }
        return Math.toIntExact(frames * frameSizeBytes)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000
    }
}
