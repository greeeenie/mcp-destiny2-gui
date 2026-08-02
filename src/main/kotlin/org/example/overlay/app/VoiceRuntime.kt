package org.example.overlay.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.overlay.audio.AudioDevices
import org.example.overlay.audio.AudioLevelMeter
import org.example.overlay.audio.JavaSoundAudioInput
import org.example.overlay.audio.MicrophoneHub
import org.example.overlay.audio.PcmAudioFormat
import org.example.overlay.audio.WavEncoder
import org.example.overlay.backend.BackendClient
import org.example.overlay.backend.ChatStreamEvent
import org.example.overlay.input.GlobalHotkey
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream

/** Где сейчас ход: клавиша зажата, речь расшифровывается или модель пишет ответ. */
enum class VoicePhase { IDLE, LISTENING, TRANSCRIBING, ANSWERING }

/**
 * Голосовой тракт без Realtime-сессии.
 *
 * Микрофон и горячая клавиша живут с момента запуска, поэтому «включать голос» нечего:
 * соединения ни с кем не держится. По удержанию клавиши речь копится в буфер, по отпусканию
 * уходит на расшифровку, а текст — в ход разговора, ответ которого приходит потоком.
 *
 * Расплата за отказ от сокета: частичного транскрипта во время речи нет — у Inworld
 * стриминговый STT есть только внутри Realtime.
 */
class VoiceRuntime(
    private val scope: CoroutineScope,
    private val settings: SettingsHolder,
    private val backend: BackendClient,
    private val tokenProvider: () -> String?,
    private val onTurnStarted: () -> Unit,
    private val onUserText: (String) -> Unit,
    private val onEvent: (ChatStreamEvent) -> Unit,
    private val onMicLevel: (Float) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val lifecycle = Mutex()
    private val turns = VoiceTurnGeneration()
    private var parts: Parts? = null
    private val recorded = ByteArrayOutputStream()

    @Volatile private var recording = false
    @Volatile private var recordingTurn = 0L
    @Volatile private var turnJob: Job? = null

    private val _phase = MutableStateFlow(VoicePhase.IDLE)
    val phase: StateFlow<VoicePhase> = _phase.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    suspend fun start() = lifecycle.withLock {
        if (parts != null) return
        val current = settings.current
        val format = PcmAudioFormat(SAMPLE_RATE)

        val microphone = JavaSoundAudioInput(
            format = format,
            chunkMs = CHUNK_MS,
            scope = scope,
            mixer = AudioDevices.resolve(current.audio.inputMixer, AudioDevices.inputs()),
        )
        val hub = MicrophoneHub(microphone, scope) { chunk ->
            onMicLevel(if (recording) AudioLevelMeter.level(chunk) else 0f)
            if (recording) {
                synchronized(recorded) {
                    if (recorded.size() < MAX_RECORDING_BYTES) recorded.write(chunk)
                }
            }
        }
        hub.start()

        val hotkey = GlobalHotkey(keyCode = { settings.current.pttKeyCode }, scope = scope)
        hotkey.start(
            onDown = {
                if (_ready.value && (_phase.value == VoicePhase.IDLE || _phase.value == VoicePhase.ANSWERING)) {
                    val turn = turns.next()
                    turnJob?.cancel()
                    turnJob = null
                    synchronized(recorded) { recorded.reset() }
                    recordingTurn = turn
                    recording = true
                    if (!turns.runIfCurrent(turn) {
                            _phase.value = VoicePhase.LISTENING
                            // Старая лента убирается уже при нажатии PTT, а не после нескольких
                            // секунд распознавания. LISTENING публикуем первым, чтобы очистка не
                            // создала даже краткого Ready+empty между двумя ходами.
                            onTurnStarted()
                        }
                    ) {
                        recording = false
                    }
                }
            },
            onUp = {
                if (recording) {
                    val turn = recordingTurn
                    recording = false
                    onMicLevel(0f)
                    finishTurn(turn)
                }
            },
        )

        parts = Parts(hub, hotkey)
        _ready.value = true
        log.info("Голосовой тракт готов: {} Гц, клавиша 0x{}", SAMPLE_RATE, current.pttKeyCode.toString(16))
    }

    suspend fun stop() = lifecycle.withLock {
        _ready.value = false
        turns.invalidate()
        turnJob?.cancel()
        turnJob = null
        recordingTurn = 0L
        val active = parts
        parts = null
        recording = false
        active?.hotkey?.stop()
        active?.hub?.stop()
        onMicLevel(0f)
        _phase.value = VoicePhase.IDLE
    }

    /** Перезапуск после смены устройства или отвалившейся линии. */
    suspend fun restart() {
        stop()
        start()
    }

    private fun finishTurn(turn: Long) {
        val pcm = synchronized(recorded) { recorded.toByteArray().also { recorded.reset() } }
        if (pcm.size < MIN_RECORDING_BYTES) {
            // Клавишу задели: расшифровывать полсекунды тишины незачем, это лишний запрос.
            turns.runIfCurrent(turn) { _phase.value = VoicePhase.IDLE }
            return
        }
        if (!turns.runIfCurrent(turn) { _phase.value = VoicePhase.TRANSCRIBING }) return
        turnJob?.cancel()
        turnJob = scope.launch {
            try {
                val token = tokenProvider() ?: error("Нет сессии бэкенда — нужен вход")
                val text = backend.transcribe(
                    token,
                    WavEncoder.encode(pcm, SAMPLE_RATE),
                    settings.current.sttModel ?: Settings.DEFAULT_STT_MODEL,
                    settings.current.sttLanguage,
                )
                if (!turns.isCurrent(turn)) return@launch
                if (text.isBlank()) {
                    turns.runIfCurrent(turn) { _phase.value = VoicePhase.IDLE }
                    return@launch
                }
                if (!turns.runIfCurrent(turn) {
                        onUserText(text)
                        _phase.value = VoicePhase.ANSWERING
                    }
                ) return@launch
                backend.streamChat(token, text, settings.current.chatModel) { event ->
                    turns.runIfCurrent(turn) { onEvent(event) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                turns.runIfCurrent(turn) {
                    log.warn("Голосовой ход не удался", error)
                    onError(error.message ?: error.javaClass.simpleName)
                }
            } finally {
                turns.runIfCurrent(turn) { _phase.value = VoicePhase.IDLE }
            }
        }
    }

    private class Parts(
        val hub: MicrophoneHub,
        val hotkey: GlobalHotkey,
    )

    private companion object {
        /** Формат больше не приходит с сервера: расшифровке важна лишь честная шапка WAV. */
        const val SAMPLE_RATE = 24_000
        const val CHUNK_MS = 100

        /** Полсекунды — ниже этого запись считается случайным нажатием. */
        const val MIN_RECORDING_BYTES = SAMPLE_RATE * 2 / 2

        /** Полминуты речи: дальше запрос на расшифровку становится неприлично большим. */
        const val MAX_RECORDING_BYTES = SAMPLE_RATE * 2 * 30
        val log = LoggerFactory.getLogger(VoiceRuntime::class.java)
    }
}

/**
 * Serializes voice-turn ownership so callbacks from a blocking, already cancelled request cannot
 * cross the boundary into the next turn.
 */
internal class VoiceTurnGeneration {
    private val monitor = Any()
    private var current = 0L

    fun next(): Long = synchronized(monitor) { ++current }

    fun invalidate() {
        next()
    }

    fun isCurrent(turn: Long): Boolean = synchronized(monitor) { turn == current }

    fun runIfCurrent(turn: Long, action: () -> Unit): Boolean = synchronized(monitor) {
        if (turn != current) return@synchronized false
        action()
        true
    }
}
