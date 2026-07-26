package org.example.overlay.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine

/**
 * Воспроизведение ответа. Механика поколений (`playbackGeneration`) и барьер
 * `awaitPlaybackComplete()` перенесены из донора без изменений: на них держится и корректное
 * прерывание, и правило «ход не завершён, пока колонка не доиграла» (§1.1, §6).
 *
 * Добавлены выбор устройства и запасной путь на 48 кГц (риск 6).
 */
class JavaSoundAudioOutput(
    private val format: PcmAudioFormat,
    private val scope: CoroutineScope,
    /** `null` — системное устройство по умолчанию. */
    private val mixer: Mixer.Info? = null,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val lineSupported: (Mixer.Info?, DataLine.Info) -> Boolean = ::isSourceLineSupported,
    private val lineProvider: (Mixer.Info?, DataLine.Info) -> SourceDataLine = ::openSourceLine,
) : AudioOutput {
    private val queue = Channel<PlaybackCommand>(queueCapacity)
    private val lifecycle = Mutex()
    private val playbackGeneration = AtomicLong()
    private var line: SourceDataLine? = null
    private var playbackJob: Job? = null

    override suspend fun start() = lifecycle.withLock {
        if (playbackJob != null) return
        val setup = resolvePlaybackFormat()
        val javaFormat = setup.format.toJavax()
        val info = DataLine.Info(SourceDataLine::class.java, javaFormat)

        val openedLine = try {
            lineProvider(mixer, info).also {
                it.open(javaFormat)
                it.start()
            }
        } catch (error: LineUnavailableException) {
            throw AudioDeviceException("Устройство вывода занято", error)
        } catch (error: RuntimeException) {
            throw AudioDeviceException("Не удалось открыть устройство вывода", error)
        }
        line = openedLine
        playbackJob = scope.launch(Dispatchers.IO) { playback(openedLine, setup) }
    }

    override suspend fun play(bytes: ByteArray) {
        play(bytes, playbackGeneration.get())
    }

    override fun currentPlaybackGeneration(): Long = playbackGeneration.get()

    override suspend fun play(bytes: ByteArray, generation: Long) {
        check(playbackJob != null) { "Вывод звука не запущен" }
        try {
            queue.send(PlaybackCommand.Audio(bytes.copyOf(), generation))
        } catch (error: Throwable) {
            throw AudioDeviceException("Очередь воспроизведения недоступна", error)
        }
    }

    override suspend fun flush() {
        playbackGeneration.incrementAndGet()
        discardQueuedCommands()
        withContext(Dispatchers.IO) { line?.flush() }
    }

    override suspend fun awaitPlaybackComplete(): PlaybackCompletion {
        check(playbackJob != null) { "Вывод звука не запущен" }
        val barrier = PlaybackCommand.Barrier(
            generation = playbackGeneration.get(),
            completion = CompletableDeferred(),
        )
        try {
            queue.send(barrier)
            return barrier.completion.await()
        } catch (error: Throwable) {
            throw AudioDeviceException("Барьер воспроизведения недоступен", error)
        }
    }

    override suspend fun stop() {
        val job = lifecycle.withLock {
            val activeJob = playbackJob ?: return
            playbackJob = null
            playbackGeneration.incrementAndGet()
            activeJob.cancel()
            discardQueuedCommands()
            line?.let { activeLine ->
                runCatching { activeLine.stop() }
                runCatching { activeLine.flush() }
                runCatching { activeLine.close() }
            }
            line = null
            activeJob
        }
        job.cancelAndJoin()
    }

    private fun resolvePlaybackFormat(): PlaybackSetup {
        val direct = DataLine.Info(SourceDataLine::class.java, format.toJavax())
        if (lineSupported(mixer, direct)) return PlaybackSetup(format, upsample = false)

        val doubled = format.copy(sampleRate = format.sampleRate * 2)
        val doubledInfo = DataLine.Info(SourceDataLine::class.java, doubled.toJavax())
        if (lineSupported(mixer, doubledInfo)) {
            log.info(
                "Вывод не умеет {} Гц — открываю {} Гц и дублирую отсчёты",
                format.sampleRate,
                doubled.sampleRate,
            )
            return PlaybackSetup(doubled, upsample = true)
        }

        throw AudioDeviceException("Устройство вывода не поддерживает ${format.toJavax().describe()}")
    }

    private suspend fun playback(activeLine: SourceDataLine, setup: PlaybackSetup) {
        var activeBarrier: PlaybackCommand.Barrier? = null
        try {
            for (command in queue) {
                when (command) {
                    is PlaybackCommand.Audio -> {
                        // Пересчёт делаем здесь, а не при постановке в очередь: так очередь
                        // остаётся короткой, а прерывание отбрасывает исходные, а не раздутые данные.
                        val payload = if (setup.upsample) Pcm16.upsampleByTwo(command.bytes) else command.bytes
                        var offset = 0
                        while (offset < payload.size && command.generation == playbackGeneration.get()) {
                            val written = activeLine.write(payload, offset, payload.size - offset)
                            if (written == 0 && command.generation != playbackGeneration.get()) break
                            if (written <= 0) throw AudioDeviceException("Устройство вывода перестало принимать звук")
                            offset += written
                        }
                    }

                    is PlaybackCommand.Barrier -> {
                        activeBarrier = command
                        activeLine.drain()
                        val result = if (command.generation == playbackGeneration.get()) {
                            PlaybackCompletion.PLAYED
                        } else {
                            PlaybackCompletion.FLUSHED
                        }
                        command.completion.complete(result)
                        activeBarrier = null
                    }
                }
            }
        } catch (error: Throwable) {
            if (playbackJob?.isActive == true) {
                queue.close(error.asAudioFailure("Воспроизведение прервалось"))
            }
        } finally {
            activeBarrier?.completion?.complete(PlaybackCompletion.FLUSHED)
            runCatching { activeLine.stop() }
            runCatching { activeLine.flush() }
            runCatching { activeLine.close() }
        }
    }

    private fun Throwable.asAudioFailure(message: String): AudioDeviceException =
        this as? AudioDeviceException ?: AudioDeviceException(message, this)

    private fun discardQueuedCommands() {
        while (true) {
            val command = queue.tryReceive().getOrNull() ?: return
            if (command is PlaybackCommand.Barrier) {
                command.completion.complete(PlaybackCompletion.FLUSHED)
            }
        }
    }

    private data class PlaybackSetup(val format: PcmAudioFormat, val upsample: Boolean)

    private sealed interface PlaybackCommand {
        data class Audio(val bytes: ByteArray, val generation: Long) : PlaybackCommand

        data class Barrier(
            val generation: Long,
            val completion: CompletableDeferred<PlaybackCompletion>,
        ) : PlaybackCommand
    }

    private companion object {
        val log = LoggerFactory.getLogger(JavaSoundAudioOutput::class.java)

        /** 8 чанков = 800 мс: больше буфера — дольше задержка прерывания (§2.2). */
        const val DEFAULT_QUEUE_CAPACITY = 8
    }
}

private fun isSourceLineSupported(mixer: Mixer.Info?, info: DataLine.Info): Boolean =
    if (mixer == null) AudioSystem.isLineSupported(info) else AudioSystem.getMixer(mixer).isLineSupported(info)

private fun openSourceLine(mixer: Mixer.Info?, info: DataLine.Info): SourceDataLine =
    (if (mixer == null) AudioSystem.getLine(info) else AudioSystem.getMixer(mixer).getLine(info)) as SourceDataLine
