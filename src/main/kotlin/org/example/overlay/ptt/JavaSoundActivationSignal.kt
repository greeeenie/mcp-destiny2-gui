package org.example.overlay.ptt

import kotlinx.coroutines.delay
import org.example.overlay.audio.AudioOutput
import kotlin.math.PI
import kotlin.math.sin

/** 880 Гц на открытие микрофона, 440 Гц на закрытие — как в доноре. */
class JavaSoundActivationSignal(
    private val output: AudioOutput,
    private val sampleRate: Int,
    private val waitForPlayback: suspend (Long) -> Unit = { delay(it) },
) : ActivationSignal {

    override suspend fun opened() {
        output.play(tone(frequency = 880.0))
        waitForPlayback(TONE_DURATION_MILLIS.toLong())
    }

    override suspend fun closed() {
        output.play(tone(frequency = 440.0))
        waitForPlayback(TONE_DURATION_MILLIS.toLong())
    }

    private fun tone(frequency: Double): ByteArray {
        val sampleCount = sampleRate * TONE_DURATION_MILLIS / 1_000
        return ByteArray(sampleCount * PCM16_BYTES_PER_SAMPLE).also { bytes ->
            repeat(sampleCount) { sampleIndex ->
                val radians = 2.0 * PI * frequency * sampleIndex / sampleRate
                val sample = (sin(radians) * Short.MAX_VALUE * VOLUME).toInt().toShort()
                bytes[sampleIndex * 2] = (sample.toInt() and 0xff).toByte()
                bytes[sampleIndex * 2 + 1] = ((sample.toInt() ushr 8) and 0xff).toByte()
            }
        }
    }

    private companion object {
        const val TONE_DURATION_MILLIS = 80
        const val PCM16_BYTES_PER_SAMPLE = 2
        const val VOLUME = 0.15
    }
}
