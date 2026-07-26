package org.example.overlay.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeoutOrNull
import javax.sound.sampled.Mixer

/**
 * «Эхо-тест» из фазы 2: записать несколько секунд с микрофона и сразу проиграть.
 *
 * Проверяет разом всё, что может пойти не так до появления сети: то ли устройство выбрано,
 * открывается ли линия на 24 кГц (риск 6), нет ли щелчков и ускорения из-за неверного формата.
 */
class AudioSelfTest(
    private val scope: CoroutineScope,
    private val format: PcmAudioFormat = PcmAudioFormat(SAMPLE_RATE),
    private val chunkMs: Int = CHUNK_MS,
) {
    suspend fun run(
        inputMixer: Mixer.Info?,
        outputMixer: Mixer.Info?,
        seconds: Int = DEFAULT_SECONDS,
        onLevel: (Float) -> Unit = {},
    ): String {
        val expectedChunks = seconds * 1_000 / chunkMs
        val captured = ArrayList<ByteArray>(expectedChunks)

        val input = JavaSoundAudioInput(format, chunkMs, scope, inputMixer)
        input.start()
        try {
            withTimeoutOrNull(seconds * 1_000L + CAPTURE_GRACE_MS) {
                input.chunks.take(expectedChunks).collect { chunk ->
                    captured += chunk
                    onLevel(AudioLevelMeter.level(chunk))
                }
            }
        } finally {
            input.stop()
            onLevel(0f)
        }

        if (captured.isEmpty()) return "Микрофон не отдал ни одного чанка — проверь выбранное устройство"

        val output = JavaSoundAudioOutput(format, scope, outputMixer)
        output.start()
        try {
            captured.forEach { output.play(it) }
            output.awaitPlaybackComplete()
        } finally {
            output.stop()
        }

        val bytes = captured.sumOf { it.size }
        val expectedChunkBytes = format.chunkBytes(chunkMs)
        val actualChunkBytes = captured.first().size
        val mismatch = if (actualChunkBytes != expectedChunkBytes) {
            " ВНИМАНИЕ: чанк $actualChunkBytes байт вместо $expectedChunkBytes."
        } else {
            ""
        }
        return "Записано ${captured.size} чанков ($bytes байт, ${bytes / 2} отсчётов) и проиграно обратно.$mismatch"
    }

    companion object {
        /** Частота задаётся сервером в `/voice/session`; 24000 — то, что он присылает сегодня. */
        const val SAMPLE_RATE = 24_000
        const val CHUNK_MS = 100
        const val DEFAULT_SECONDS = 5
        private const val CAPTURE_GRACE_MS = 1_500L
    }
}
