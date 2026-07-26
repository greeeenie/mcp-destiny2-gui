package org.example.overlay.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.overlay.audio.AudioDevices
import org.example.overlay.audio.AudioLevelMeter
import org.example.overlay.audio.GatedAudioInput
import org.example.overlay.audio.JavaSoundAudioInput
import org.example.overlay.audio.JavaSoundAudioOutput
import org.example.overlay.audio.MicrophoneHub
import org.example.overlay.audio.PcmAudioFormat
import org.example.overlay.backend.VoiceSessionProvider
import org.example.overlay.conversation.ConversationEvent
import org.example.overlay.conversation.ConversationState
import org.example.overlay.input.GlobalHotkey
import org.example.overlay.inworld.InworldConversationEngine
import org.example.overlay.inworld.InworldProtocol
import org.example.overlay.inworld.JdkRealtimeTransport
import org.example.overlay.inworld.RealtimeTransportFactory
import org.example.overlay.ptt.ActivationSignal
import org.example.overlay.ptt.ActivationState
import org.example.overlay.ptt.JavaSoundActivationSignal
import org.example.overlay.ptt.PushToTalkController
import org.example.overlay.tools.ToolExecutor
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant

/**
 * Голосовой тракт целиком: микрофон → гейт → движок → колонка, плюс горячая клавиша.
 *
 * Собирается только при включении и разбирается при выключении: до первого `/voice/session`
 * оверлей не знает ни частоты, ни размера чанка — своих значений по умолчанию он не заводит (§3.2).
 */
class VoiceRuntime(
    private val scope: CoroutineScope,
    private val settings: SettingsHolder,
    private val voiceSessionProvider: VoiceSessionProvider,
    private val toolExecutor: ToolExecutor,
    private val httpClient: HttpClient,
    private val mapper: ObjectMapper,
    private val onEvent: suspend (ConversationEvent) -> Unit,
    private val onMicLevel: (Float) -> Unit,
) {
    private val lifecycle = Mutex()
    private var parts: Parts? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _activation = MutableStateFlow<ActivationState>(ActivationState.Idle)
    val activation: StateFlow<ActivationState> = _activation.asStateFlow()

    suspend fun enable() = lifecycle.withLock {
        if (parts != null) return

        // Доступ берём первым делом: из него приходит формат звука, который нужен, чтобы
        // вообще открыть линии.
        val access = voiceSessionProvider.get()
        val current = settings.current

        val inputFormat = PcmAudioFormat(access.audio.inputSampleRate)
        val outputFormat = PcmAudioFormat(access.audio.outputSampleRate)

        val microphone = JavaSoundAudioInput(
            format = inputFormat,
            chunkMs = access.audio.chunkMs,
            scope = scope,
            mixer = AudioDevices.resolve(current.audio.inputMixer, AudioDevices.inputs()),
        )
        val speaker = JavaSoundAudioOutput(
            format = outputFormat,
            scope = scope,
            mixer = AudioDevices.resolve(current.audio.outputMixer, AudioDevices.outputs()),
        )
        val gate = GatedAudioInput(capacity = GATE_CAPACITY)

        // Линию вывода держим сами: она нужна для тонов открытия и закрытия микрофона даже
        // тогда, когда ответы не озвучиваются.
        speaker.start()

        val engine = InworldConversationEngine(
            protocol = InworldProtocol(mapper),
            transportFactory = RealtimeTransportFactory { JdkRealtimeTransport(httpClient) },
            voiceSessionProvider = voiceSessionProvider,
            scope = scope,
            audioInput = gate,
            // Без озвучки движок не получает вывод вовсе: аудио-дельты никуда не играются,
            // а ход завершается не дожидаясь колонки.
            audioOutput = speaker.takeIf { current.speakResponses },
            toolExecutor = toolExecutor,
            // Просить только текст осмысленно лишь тогда, когда звук и не нужен.
            textOnlyOutput = current.requestTextOnly && !current.speakResponses,
        )

        val controller = PushToTalkController(
            engine = engine,
            gate = gate,
            signal = RestartingSignal(speaker, outputFormat.sampleRate),
            scope = scope,
            chunkBytes = access.audio.chunkBytes,
            chunkMs = access.audio.chunkMs.toLong(),
            handsFree = { settings.current.handsFree },
            commitOnRelease = { settings.current.commitOnRelease },
        )

        val hub = MicrophoneHub(microphone, scope) { chunk ->
            onMicLevel(AudioLevelMeter.level(chunk))
            controller.onMicrophoneChunk(chunk)
        }

        val hotkey = GlobalHotkey(keyCode = { settings.current.pttKeyCode }, scope = scope)

        val eventJob = scope.launch { engine.events.collect(onEvent) }
        val activationJob = scope.launch { controller.state.collect { _activation.value = it } }
        val rotationJob = scope.launch { rotateSocketQuietly(engine) }

        controller.start()
        hub.start()
        hotkey.start(onDown = controller::onKeyDown, onUp = controller::onKeyUp)

        parts = Parts(engine, controller, hub, hotkey, speaker, eventJob, activationJob, rotationJob)
        _enabled.value = true
        log.info(
            "Голосовой тракт включён: {} Гц, чанк {} мс, озвучка {}",
            inputFormat.sampleRate,
            access.audio.chunkMs,
            if (current.speakResponses) "включена" else "выключена (просим только текст)",
        )
    }

    suspend fun disable() = lifecycle.withLock {
        val active = parts ?: return
        parts = null
        _enabled.value = false
        active.hotkey.stop()
        active.controller.stop()
        active.hub.stop()
        runCatching { active.speaker.stop() }
        active.eventJob.cancel()
        active.activationJob.cancel()
        active.rotationJob.cancel()
        _activation.value = ActivationState.Idle
        log.info("Голосовой тракт выключен")
    }

    /**
     * Тихая ротация (§4): если сессия открыта, никто не говорит и до истечения JWT меньше
     * пяти минут — закрываем сокет. Следующее нажатие клавиши поднимет его уже со свежим
     * токеном, и игрок ничего не заметит.
     */
    private suspend fun rotateSocketQuietly(engine: InworldConversationEngine) {
        while (currentCoroutineContext().isActive) {
            delay(ROTATION_CHECK_INTERVAL_MS)
            val expiry = voiceSessionProvider.cachedExpiry() ?: continue
            val quiet = _activation.value.let { it is ActivationState.FollowUp || it == ActivationState.Idle }
            val soon = Duration.between(Instant.now(), expiry) < ROTATION_THRESHOLD
            if (quiet && soon && engine.state.value is ConversationState.Active) {
                log.info("Токен Inworld скоро истечёт — закрываю сокет между ходами")
                runCatching { engine.stop() }
                voiceSessionProvider.invalidate()
            }
        }
    }

    /**
     * Тоны микрофона живут дольше одной сессии, а движок закрывает линию вывода в конце своей.
     * Поэтому перед тоном линию переоткрываем: `start()` идемпотентен.
     */
    private class RestartingSignal(
        private val output: JavaSoundAudioOutput,
        sampleRate: Int,
    ) : ActivationSignal {
        private val delegate = JavaSoundActivationSignal(output, sampleRate)

        override suspend fun opened() {
            runCatching { output.start() }
            delegate.opened()
        }

        override suspend fun closed() {
            runCatching { output.start() }
            delegate.closed()
        }
    }

    private class Parts(
        val engine: InworldConversationEngine,
        val controller: PushToTalkController,
        val hub: MicrophoneHub,
        val hotkey: GlobalHotkey,
        val speaker: JavaSoundAudioOutput,
        val eventJob: Job,
        val activationJob: Job,
        val rotationJob: Job,
    )

    private companion object {
        /** 4 чанка = 400 мс: очередь намеренно короткая (§2.2). */
        const val GATE_CAPACITY = 4
        const val ROTATION_CHECK_INTERVAL_MS = 60_000L
        val ROTATION_THRESHOLD: Duration = Duration.ofMinutes(5)
        val log = LoggerFactory.getLogger(VoiceRuntime::class.java)
    }
}
