package org.example.overlay.ptt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.overlay.audio.GatedAudioInput
import org.example.overlay.conversation.ConversationEvent
import org.example.overlay.conversation.ConversationState
import org.example.overlay.conversation.RealtimeConversationEngine
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Донорский `ActivationController`, в котором wake word заменён горячей клавишей (§5.1).
 *
 * Машина состояний и маршрутизация звука сохранены: гейт микрофона — единственный путь звука
 * в облако, и он открыт только тогда, когда ассистент точно не говорит. Зацикливание через
 * колонки структурно невозможно: микрофон и вывод никогда не активны одновременно (§6).
 */
class PushToTalkController(
    private val engine: RealtimeConversationEngine,
    private val gate: GatedAudioInput,
    private val signal: ActivationSignal,
    private val scope: CoroutineScope,
    private val chunkBytes: Int,
    private val chunkMs: Long,
    /** Дольше окна тишины сокет не живёт — открытая сессия тарифицируется (§12). */
    private val followUpMs: Long = DEFAULT_FOLLOW_UP_MS,
    private val handsFree: () -> Boolean = { false },
    /** Запасной путь §5.2: если хвоста тишины не хватит, ход закрывается явным commit. */
    private val commitOnRelease: () -> Boolean = { false },
    private val silenceTailChunks: Int = DEFAULT_SILENCE_TAIL_CHUNKS,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private val transitions = Mutex()
    private val closed = AtomicBoolean()
    private val mutableState = MutableStateFlow<ActivationState>(ActivationState.Idle)

    private var eventJob: Job? = null
    private var connectionJob: Job? = null
    private var followUpJob: Job? = null
    private var releaseJob: Job? = null
    private var stopJob: Job? = null

    private var keyHeld = false
    private var latestResponseGeneration = 0L
    private var minimumResponseGeneration = 0L

    val state: StateFlow<ActivationState> = mutableState

    fun start() {
        check(!closed.get()) { "Контроллер уже остановлен" }
        if (eventJob != null) return
        eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect(::onConversationEvent)
        }
    }

    /** Чанк с физического микрофона. Пропускаем в облако, только если гейт открыт. */
    suspend fun onMicrophoneChunk(bytes: ByteArray) {
        gate.offer(bytes)
    }

    suspend fun onKeyDown() = transitions.withLock {
        if (closed.get()) return
        keyHeld = true
        releaseJob?.cancel()
        releaseJob = null
        when (mutableState.value) {
            ActivationState.Idle, is ActivationState.Failed -> connect()
            is ActivationState.FollowUp -> {
                followUpJob?.cancel()
                followUpJob = null
                openListening()
            }
            ActivationState.Speaking -> {
                // Клавиша во время ответа — это прерывание. Локальный сигнал, который эхо
                // не подделает: ровно то место, которое в доноре занимал wake word (§6).
                if (engine.interrupt()) {
                    minimumResponseGeneration = maxOf(minimumResponseGeneration, latestResponseGeneration + 1)
                }
                openListening()
            }
            ActivationState.Connecting, ActivationState.Listening, ActivationState.Thinking -> Unit
        }
    }

    suspend fun onKeyUp() = transitions.withLock {
        if (closed.get()) return
        keyHeld = false
        if (mutableState.value != ActivationState.Listening) return
        mutableState.value = ActivationState.Thinking
        releaseJob = scope.launch { finishTurn() }
    }

    suspend fun stop() {
        if (!closed.compareAndSet(false, true)) return
        val (jobs, pendingStop) = transitions.withLock {
            gate.closeGate()
            val owned = listOfNotNull(followUpJob, releaseJob, connectionJob, eventJob)
            val pending = stopJob
            followUpJob = null
            releaseJob = null
            connectionJob = null
            eventJob = null
            stopJob = null
            mutableState.value = ActivationState.Idle
            owned to pending
        }
        jobs.forEach { it.cancelAndJoin() }
        pendingStop?.join()
        engine.stop()
    }

    /**
     * Закрытие хода по отпусканию клавиши: дописываем хвост тишины, чтобы `semantic_vad`
     * увидел паузу и сам закрыл ход. Серверный `turn_detection` при этом не трогаем — менять
     * его оверлей права не имеет (§5.2).
     */
    private suspend fun finishTurn() {
        val silence = ByteArray(chunkBytes)
        repeat(silenceTailChunks) {
            gate.offer(silence)
            delay(chunkMs)
        }
        transitions.withLock {
            gate.closeGate()
            runCatching { signal.closed() }
        }
        if (commitOnRelease()) {
            engine.commitInputAudio()
        }
    }

    private fun connect() {
        gate.closeGate()
        mutableState.value = ActivationState.Connecting
        // Тихое закрытие сессии останавливает движок асинхронно; клавиша может прийти раньше,
        // чем остановка закончится, поэтому дожидаемся её, иначе движок отвергнет старт.
        val pendingStop = stopJob
        connectionJob = scope.launch {
            if (pendingStop?.isActive == true) pendingStop.join()
            runCatching { engine.start() }
                .onFailure { error ->
                    log.warn("Не удалось поднять голосовую сессию: {}", error.message)
                    fail(error.message ?: "Сессия не поднялась")
                }
        }
    }

    private fun stopEngineAsync() {
        stopJob = scope.launch { runCatching { engine.stop() } }
    }

    private suspend fun onConversationEvent(event: ConversationEvent) = transitions.withLock {
        when (event) {
            is ConversationEvent.StateChanged -> onConversationState(event.state)

            is ConversationEvent.AudioChunk -> {
                latestResponseGeneration = maxOf(latestResponseGeneration, event.responseGeneration)
                if (event.responseGeneration >= minimumResponseGeneration && isMicrophoneOpenState()) {
                    gate.closeGate()
                    mutableState.value = ActivationState.Speaking
                }
            }

            is ConversationEvent.ResponseStarted -> {
                latestResponseGeneration = maxOf(latestResponseGeneration, event.responseGeneration)
                if (event.responseGeneration >= minimumResponseGeneration) {
                    followUpJob?.cancel()
                    followUpJob = null
                    gate.closeGate()
                    mutableState.value = ActivationState.Speaking
                }
            }

            ConversationEvent.ResponseCompleted -> openFollowUp()

            ConversationEvent.SpeechStarted -> {
                if (mutableState.value is ActivationState.FollowUp) {
                    followUpJob?.cancel()
                    followUpJob = null
                    mutableState.value = ActivationState.Listening
                }
            }

            is ConversationEvent.Transcript,
            is ConversationEvent.Diagnostic,
            is ConversationEvent.ToolCompleted,
            -> Unit
        }
    }

    private suspend fun onConversationState(conversationState: ConversationState) {
        when (conversationState) {
            is ConversationState.Active -> {
                if (mutableState.value == ActivationState.Connecting) {
                    connectionJob = null
                    if (keyHeld) {
                        openListening()
                    } else {
                        // Клавишу отпустили, пока поднималась сессия: хода не было, ждём следующей.
                        mutableState.value = ActivationState.FollowUp(deadline())
                        scheduleFollowUpClose()
                    }
                }
            }

            is ConversationState.Failed -> failLocked(conversationState.failure.message)

            ConversationState.Idle -> if (mutableState.value is ActivationState.Failed) {
                mutableState.value = ActivationState.Idle
            }

            ConversationState.Connecting,
            is ConversationState.Reconnecting,
            ConversationState.Stopping,
            -> Unit
        }
    }

    private suspend fun openListening() {
        gate.openGate()
        runCatching { signal.opened() }
        mutableState.value = ActivationState.Listening
    }

    /**
     * Окно после ответа. В hands-free микрофон открыт, и донорская логика работает как была;
     * в push-to-talk окно нужно только чтобы не закрывать сокет между быстрыми фразами.
     */
    private suspend fun openFollowUp() {
        followUpJob?.cancel()
        if (handsFree()) {
            gate.openGate()
            runCatching { signal.opened() }
        } else {
            gate.closeGate()
        }
        mutableState.value = ActivationState.FollowUp(deadline())
        scheduleFollowUpClose()
    }

    private fun scheduleFollowUpClose() {
        followUpJob = scope.launch {
            delay(followUpMs)
            closeFollowUp()
        }
    }

    private suspend fun closeFollowUp() = transitions.withLock {
        if (mutableState.value !is ActivationState.FollowUp) return
        followUpJob = null
        gate.closeGate()
        runCatching { signal.closed() }
        mutableState.value = ActivationState.Idle
        stopEngineAsync()
    }

    private fun isMicrophoneOpenState(): Boolean =
        mutableState.value == ActivationState.Listening || mutableState.value is ActivationState.FollowUp

    private fun deadline(): Long = monotonicNanos() + followUpMs * NANOS_PER_MILLISECOND

    private suspend fun fail(message: String) = transitions.withLock { failLocked(message) }

    private fun failLocked(message: String) {
        followUpJob?.cancel()
        followUpJob = null
        gate.closeGate()
        mutableState.value = ActivationState.Failed(message)
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L

        /** 3.5 с — донорское окно follow-up. */
        const val DEFAULT_FOLLOW_UP_MS = 3_500L

        /** 4 чанка по 100 мс = ~400 мс тишины (§5.2). */
        const val DEFAULT_SILENCE_TAIL_CHUNKS = 4
        val log = LoggerFactory.getLogger(PushToTalkController::class.java)
    }
}
