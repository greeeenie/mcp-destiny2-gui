package org.example.overlay.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine

/**
 * Захват с микрофона. Отличия от донора ровно два (§1 плана):
 * выбор конкретного устройства вместо «системного по умолчанию» и запасной путь на 48 кГц
 * для карт, не умеющих 24000 Гц (риск 6).
 */
class JavaSoundAudioInput(
    private val format: PcmAudioFormat,
    private val chunkMs: Int,
    private val scope: CoroutineScope,
    /** `null` — системное устройство по умолчанию. */
    private val mixer: Mixer.Info? = null,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val lineSupported: (Mixer.Info?, DataLine.Info) -> Boolean = ::isTargetLineSupported,
    private val lineProvider: (Mixer.Info?, DataLine.Info) -> TargetDataLine = ::openTargetLine,
) : AudioInput {
    private val channel = Channel<ByteArray>(queueCapacity)
    private val lifecycle = Mutex()
    private var line: TargetDataLine? = null
    private var captureJob: Job? = null

    override val chunks: Flow<ByteArray> = channel.receiveAsFlow()

    override suspend fun start() = lifecycle.withLock {
        if (captureJob != null) return
        while (channel.tryReceive().isSuccess) {
            // Чанки предыдущей сессии не должны проиграться заново.
        }

        val setup = resolveCaptureFormat()
        val javaFormat = setup.format.toJavax()
        val info = DataLine.Info(TargetDataLine::class.java, javaFormat)
        val openedLine = try {
            lineProvider(mixer, info).also {
                it.open(javaFormat, setup.format.chunkBytes(chunkMs) * DEFAULT_LINE_CHUNKS)
                it.start()
            }
        } catch (error: LineUnavailableException) {
            throw AudioDeviceException("Микрофон занят другим приложением", error)
        } catch (error: RuntimeException) {
            throw AudioDeviceException("Не удалось открыть микрофон", error)
        }
        line = openedLine
        captureJob = scope.launch(Dispatchers.IO) { capture(openedLine, setup) }
    }

    override suspend fun stop() {
        val job = lifecycle.withLock {
            val activeJob = captureJob ?: return
            captureJob = null
            activeJob.cancel()
            line?.let { activeLine ->
                runCatching { activeLine.stop() }
                runCatching { activeLine.close() }
            }
            line = null
            activeJob
        }
        job.cancelAndJoin()
    }

    /**
     * 24000 Гц напрямую, иначе 48000 Гц с делением частоты на два. Промежуточных вариантов нет:
     * дробный коэффициент потребовал бы настоящего ресемплера, а это отдельная задача.
     */
    private fun resolveCaptureFormat(): CaptureSetup {
        val direct = DataLine.Info(TargetDataLine::class.java, format.toJavax())
        if (lineSupported(mixer, direct)) return CaptureSetup(format, decimate = false)

        val doubled = format.copy(sampleRate = format.sampleRate * 2)
        val doubledInfo = DataLine.Info(TargetDataLine::class.java, doubled.toJavax())
        if (lineSupported(mixer, doubledInfo)) {
            log.info(
                "Микрофон не умеет {} Гц — открываю {} Гц и децимирую вдвое",
                format.sampleRate,
                doubled.sampleRate,
            )
            return CaptureSetup(doubled, decimate = true)
        }

        throw AudioDeviceException("Микрофон не поддерживает ${format.toJavax().describe()}")
    }

    private suspend fun capture(activeLine: TargetDataLine, setup: CaptureSetup) {
        val chunkBytes = setup.format.chunkBytes(chunkMs)
        try {
            while (currentCoroutineContext().isActive) {
                val chunk = ByteArray(chunkBytes)
                var offset = 0
                while (offset < chunk.size && currentCoroutineContext().isActive) {
                    val read = activeLine.read(chunk, offset, chunk.size - offset)
                    if (read < 0) throw AudioDeviceException("Поток микрофона неожиданно закончился")
                    if (read == 0) continue
                    offset += read
                }
                if (offset != chunk.size) continue
                val payload = if (setup.decimate) Pcm16.decimateByTwo(chunk) else chunk
                if (channel.trySend(payload).isFailure) {
                    throw AudioDeviceException("Очередь микрофона переполнена")
                }
            }
        } catch (error: Throwable) {
            if (currentCoroutineContext().isActive) {
                channel.close(error.asAudioFailure("Захват звука прервался"))
            }
        } finally {
            runCatching { activeLine.stop() }
            runCatching { activeLine.close() }
        }
    }

    private data class CaptureSetup(val format: PcmAudioFormat, val decimate: Boolean)

    private fun Throwable.asAudioFailure(message: String): AudioDeviceException =
        this as? AudioDeviceException ?: AudioDeviceException(message, this)

    private companion object {
        val log = LoggerFactory.getLogger(JavaSoundAudioInput::class.java)

        /** 4 чанка = 400 мс: очередь намеренно короткая, свежий звук важнее полного (§2.2). */
        const val DEFAULT_QUEUE_CAPACITY = 4
        const val DEFAULT_LINE_CHUNKS = 4
    }
}

internal fun AudioFormat.describe(): String =
    "${sampleRate.toInt()} Гц, $sampleSizeInBits бит, $channels кан., " +
        if (isBigEndian) "big-endian" else "little-endian"

private fun isTargetLineSupported(mixer: Mixer.Info?, info: DataLine.Info): Boolean =
    if (mixer == null) AudioSystem.isLineSupported(info) else AudioSystem.getMixer(mixer).isLineSupported(info)

private fun openTargetLine(mixer: Mixer.Info?, info: DataLine.Info): TargetDataLine =
    (if (mixer == null) AudioSystem.getLine(info) else AudioSystem.getMixer(mixer).getLine(info)) as TargetDataLine
