package org.example.overlay.inworld

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.example.overlay.audio.AudioDeviceException
import org.example.overlay.audio.AudioInput
import org.example.overlay.audio.AudioOutput
import org.example.overlay.audio.PlaybackCompletion
import org.example.overlay.backend.UnauthorizedException
import org.example.overlay.backend.VoiceAccess
import org.example.overlay.backend.VoiceAccessProvider
import org.example.overlay.conversation.ConversationEvent
import org.example.overlay.conversation.ConversationFailure
import org.example.overlay.conversation.ConversationState
import org.example.overlay.conversation.RealtimeConversationEngine
import org.example.overlay.tools.ToolBudgetDecision
import org.example.overlay.tools.ToolBudgetPolicy
import org.example.overlay.tools.ToolExecutor
import org.example.overlay.tools.ToolResult
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Движок разговора — донорский `InworldConversationEngine`, урезанный по §1.1 плана.
 *
 * Осталось то, ради чего он переносился: счётчик поколений соединения, жизненный цикл ответа,
 * ровно одно прерывание на ответ, ожидание доигрывания перед завершением хода и обслуживание
 * `function_call` с дедупликацией и бюджетом.
 *
 * Ушло: память, метрики, обучение, текстовый режим Telegram, сборка промпта и настройки голоса —
 * всё это теперь на сервере и приезжает готовой строкой `sessionUpdate`.
 */
class InworldConversationEngine(
    private val protocol: InworldProtocol,
    private val transportFactory: RealtimeTransportFactory,
    private val voiceSessionProvider: VoiceAccessProvider,
    private val scope: CoroutineScope,
    private val audioInput: AudioInput? = null,
    private val audioOutput: AudioOutput? = null,
    private val toolExecutor: ToolExecutor? = null,
    /**
     * Просить у Inworld только текст. Без этого он синтезирует речь и берёт за неё деньги,
     * даже если оверлей её не играет.
     */
    private val textOnlyOutput: Boolean = false,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) : RealtimeConversationEngine {

    private val mutableState = MutableStateFlow<ConversationState>(ConversationState.Idle)
    private val mutableEvents = MutableSharedFlow<ConversationEvent>(extraBufferCapacity = 32)

    private val generationCounter = AtomicLong()
    private val responseGenerationCounter = AtomicLong()
    private var activeGeneration = 0L
    private var transport: RealtimeTransport? = null
    private var sessionJob: Job? = null
    private var audioInputJob: Job? = null
    private var audioStarted = false

    private var activeResponseId: String? = null
    private var interruptedResponseId: String? = null
    private var cancelSent = false
    private var providerResponseDone = false
    private var activeResponseGeneration = 0L
    private var activePlaybackGeneration = 0L
    private var responseSettlement: CompletableDeferred<Unit>? = null
    private val responseMutex = Mutex()

    private val toolAdmissionMutex = Mutex()
    private val toolJobs = ConcurrentHashMap<String, Job>()
    private val seenToolCallIds = LinkedHashSet<String>()
    private val toolResponseIds = ConcurrentHashMap.newKeySet<String>()
    private var toolTurnGeneration = 0L

    /** Политики приходят с сервера в блоке `client`; своих значений по умолчанию у нас нет. */
    private lateinit var reconnectPolicy: ReconnectPolicy
    private lateinit var budgetPolicy: ToolBudgetPolicy
    @Volatile private var interruptTimeoutMs = 0L

    /**
     * Одна внеочередная попытка на сессию при авторизационной ошибке (§4): Inworld мог порвать
     * сокет по истечении JWT. Вторая такая ошибка — уже честный `Failed`.
     */
    @Volatile private var authRetryUsed = false
    @Volatile private var stopRequested = false

    override val state: StateFlow<ConversationState> = mutableState
    override val events: SharedFlow<ConversationEvent> = mutableEvents

    override suspend fun start() {
        check(sessionJob == null) { "Разговор уже идёт" }
        stopRequested = false
        authRetryUsed = false

        // Доступ берём до цикла: из него приходят и таймаут подключения, и политика реконнекта.
        val access = voiceSessionProvider.get()
        applyAccess(access)

        val firstActive = CompletableDeferred<Unit>()
        sessionJob = scope.launch { connectionLoop(firstActive) }
        try {
            withTimeout(access.client.connectTimeoutMs) { firstActive.await() }
        } catch (error: Throwable) {
            stopRequested = true
            sessionJob?.cancelAndJoin()
            sessionJob = null
            transport = null
            if (mutableState.value !is ConversationState.Failed) {
                updateState(ConversationState.Failed(InworldFailureClassifier.classify(error)))
            }
            throw error
        }
    }

    override suspend fun stop() {
        val job = sessionJob ?: run {
            updateState(ConversationState.Idle)
            return
        }
        stopRequested = true
        updateState(ConversationState.Stopping)
        job.cancelAndJoin()
        sessionJob = null
        transport = null
        updateState(ConversationState.Idle)
    }

    /**
     * Прерывание по клавише: сброс воспроизведения, `response.cancel` и очистка входного буфера.
     * Место, которое в доноре занимал wake word, здесь занимает клавиша — локальный сигнал,
     * который эхо подделать не может (§6).
     */
    override suspend fun interrupt(): Boolean {
        val activeTransport = transport ?: return false
        if (state.value !is ConversationState.Active) return false
        val settlement = runCatching { requestInterruption(activeTransport, clearInputBuffer = true) }
            .getOrElse { return false }
            ?: return false
        return withTimeoutOrNull(interruptTimeoutMs) {
            settlement.await()
            true
        } ?: false
    }

    /** Явное закрытие хода — запасной путь фазы 5, если хвоста тишины не хватит (§5.2). */
    override suspend fun commitInputAudio(): Boolean {
        val activeTransport = transport ?: return false
        return runCatching { activeTransport.send(protocol.inputAudioBufferCommit()) }.isSuccess
    }

    private fun applyAccess(access: VoiceAccess) {
        reconnectPolicy = ReconnectPolicy(access.client)
        budgetPolicy = ToolBudgetPolicy(access.client.maxToolCallsPerTurn, access.client.toolBudgets)
        interruptTimeoutMs = access.client.interruptTimeoutMs
    }

    /**
     * Счётчик попыток вынесен в объект, а обработчик отказа — в обычный метод класса.
     * Локальная suspend-функция внутри этого цикла ломала кодогенерацию Kotlin: класс не
     * проходил верификацию JVM (`VerifyError: Bad local variable type`).
     */
    private class RetryState(var completed: Int = 0)

    private suspend fun handleFailure(
        failure: ConversationFailure,
        retries: RetryState,
        firstActive: CompletableDeferred<Unit>,
    ): Boolean {
        if (failure.category == ConversationFailure.Category.AUTHENTICATION && !authRetryUsed) {
            authRetryUsed = true
            voiceSessionProvider.invalidate()
            log.info("Авторизационная ошибка Inworld — перевыпускаю доступ и пробую ещё раз")
            return true
        }
        if (!reconnectPolicy.shouldRetry(failure, retries.completed)) {
            updateState(ConversationState.Failed(failure))
            firstActive.completeExceptionally(InworldConnectionException(failure))
            return false
        }
        retries.completed++
        updateState(ConversationState.Reconnecting(retries.completed))
        audioOutput?.flush()
        retryDelay(reconnectPolicy.delayMillis(retries.completed))
        return true
    }

    private enum class AttemptOutcome { RETRY, STOP }

    /**
     * Цикл соединения намеренно короткий, а тело попытки вынесено в [runConnectionAttempt]:
     * один большой suspend-цикл с вложенными try/catch/finally компилятор Kotlin 2.2 переводит
     * в машину состояний, которую JVM отвергает (`VerifyError: Bad local variable type`).
     * Две функции поменьше дают тот же порядок действий и рабочий байткод.
     */
    private suspend fun connectionLoop(firstActive: CompletableDeferred<Unit>) {
        val retries = RetryState()
        try {
            while (currentCoroutineContext().isActive && !stopRequested) {
                if (runConnectionAttempt(retries, firstActive) == AttemptOutcome.STOP) return
            }
        } finally {
            withContext(NonCancellable) {
                cancelToolJobs()
                stopAudio()
            }
        }
    }

    private suspend fun runConnectionAttempt(
        retries: RetryState,
        firstActive: CompletableDeferred<Unit>,
    ): AttemptOutcome {
        cancelToolJobs()
        val generation = generationCounter.incrementAndGet()
        activeGeneration = generation
        resetResponseState()
        updateState(
            if (retries.completed == 0) {
                ConversationState.Connecting
            } else {
                ConversationState.Reconnecting(retries.completed)
            },
        )

        // Свежий доступ перед каждой попыткой: срок JWT проверяется до connect(), а не
        // посреди разговора (§4).
        val access = try {
            voiceSessionProvider.get().also(::applyAccess)
        } catch (error: Throwable) {
            if (stopRequested || !currentCoroutineContext().isActive) return AttemptOutcome.STOP
            val retry = handleFailure(classifyAccessFailure(error), retries, firstActive)
            return if (retry) AttemptOutcome.RETRY else AttemptOutcome.STOP
        }

        val activeTransport = transportFactory.create()
        transport = activeTransport

        try {
            log.debug("Подключаюсь к Inworld: {}", access.uri)
            activeTransport.connect(access.uri, mapOf("Authorization" to access.authorizationHeader))
            activeTransport.incoming.collect { json ->
                if (generation == activeGeneration) {
                    val becameActive = handle(protocol.decode(json), activeTransport, access, firstActive)
                    if (becameActive) {
                        retries.completed = 0
                        authRetryUsed = false
                    }
                }
            }
            if (!stopRequested) throw RealtimeTransportClosedException()
            return AttemptOutcome.STOP
        } catch (error: Throwable) {
            if (stopRequested || !currentCoroutineContext().isActive) return AttemptOutcome.STOP
            val failure = InworldFailureClassifier.classify(error)
            log.warn(
                "Обрыв соединения с Inworld | категория={} попыток={} причина={}",
                failure.category,
                retries.completed,
                error.javaClass.simpleName,
            )
            val retry = handleFailure(failure, retries, firstActive)
            return if (retry) AttemptOutcome.RETRY else AttemptOutcome.STOP
        } finally {
            cancelToolJobs()
            if (transport === activeTransport) transport = null
            runCatching { activeTransport.close() }
        }
    }

    /** 401 бэкенда — это не сбой сети: перевыпуск токена не поможет, нужен вход. */
    private fun classifyAccessFailure(error: Throwable): ConversationFailure = when (error) {
        is UnauthorizedException -> ConversationFailure(
            ConversationFailure.Category.AUTHENTICATION,
            "Сессия бэкенда недействительна — нужен вход",
            retryable = false,
        )

        else -> InworldFailureClassifier.classify(error)
    }

    private suspend fun handle(
        event: InworldServerEvent,
        activeTransport: RealtimeTransport,
        access: VoiceAccess,
        firstActive: CompletableDeferred<Unit>,
    ): Boolean = when (event) {
        is InworldServerEvent.SessionCreated -> {
            // Строка с сервера уходит как есть — оверлей её не разбирает (§3.2).
            activeTransport.send(access.sessionUpdate)
            // Затем, если озвучка не нужна, отдельным кадром просим только текст.
            if (textOnlyOutput) activeTransport.send(protocol.textOnlyOutput())
            false
        }

        InworldServerEvent.SessionUpdated -> {
            if (state.value is ConversationState.Active) {
                false
            } else {
                startAudio(firstActive)
                updateState(ConversationState.Active("inworld-$activeGeneration"))
                firstActive.complete(Unit)
                true
            }
        }

        is InworldServerEvent.AudioDelta -> {
            val response = responseMutex.withLock {
                ResponseSnapshot(
                    activeResponseId,
                    interruptedResponseId,
                    activeResponseGeneration,
                    activePlaybackGeneration,
                )
            }
            val matchesActive = event.responseId == null || event.responseId == response.activeResponseId
            val interrupted = response.interruptedResponseId != null &&
                (event.responseId == null || event.responseId == response.interruptedResponseId)
            if (!matchesActive || interrupted || response.generation == 0L) {
                false
            } else {
                audioOutput?.play(event.bytes, response.playbackGeneration)
                emit(ConversationEvent.AudioChunk(event.bytes, response.generation))
            }
        }

        is InworldServerEvent.Transcript -> {
            if (event.speaker == InworldServerEvent.Speaker.USER && event.isFinal) {
                val oldTurnJobs = resetToolTurn()
                scope.launch { oldTurnJobs.forEach { it.cancelAndJoin() } }
            }
            // Тексты реплик в лог не пишем — правило донора.
            emit(
                ConversationEvent.Transcript(
                    speaker = if (event.speaker == InworldServerEvent.Speaker.USER) {
                        ConversationEvent.Speaker.USER
                    } else {
                        ConversationEvent.Speaker.ASSISTANT
                    },
                    text = event.text,
                    isFinal = event.isFinal,
                ),
            )
        }

        InworldServerEvent.SpeechStarted -> {
            // Входной буфер здесь не чистим: как раз сейчас говорит игрок.
            requestInterruption(activeTransport, clearInputBuffer = false)
            emit(ConversationEvent.SpeechStarted)
        }

        is InworldServerEvent.ResponseCreated -> {
            val responseGeneration = responseMutex.withLock {
                responseSettlement?.complete(Unit)
                activeResponseId = event.responseId
                interruptedResponseId = null
                cancelSent = false
                providerResponseDone = false
                activeResponseGeneration = responseGenerationCounter.incrementAndGet()
                activePlaybackGeneration = audioOutput?.currentPlaybackGeneration() ?: 0L
                responseSettlement = CompletableDeferred()
                activeResponseGeneration
            }
            emit(ConversationEvent.ResponseStarted(responseGeneration))
        }

        is InworldServerEvent.FunctionCallArgumentsDone -> handleToolCall(event, activeTransport)
        is InworldServerEvent.AudioOutputDone -> false
        is InworldServerEvent.ResponseDone -> completeResponse(event)
        is InworldServerEvent.Error -> throw InworldFailureClassifier.remote(event.code, event.message)
        is InworldServerEvent.Unknown -> emit(ConversationEvent.Diagnostic(event.type))
    }

    // --- звук ---

    private suspend fun startAudio(firstActive: CompletableDeferred<Unit>) {
        if (audioStarted) return
        try {
            audioOutput?.start()
            audioInput?.start()
            audioStarted = true
            audioInputJob = audioInput?.let { input ->
                scope.launch {
                    try {
                        input.chunks.collect { bytes ->
                            val activeTransport = transport
                            if (state.value is ConversationState.Active && activeTransport != null) {
                                activeTransport.send(protocol.appendAudio(bytes))
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        failAudio(error, firstActive)
                    }
                }
            }
        } catch (error: Throwable) {
            stopAudio()
            throw error.asAudioFailure("Не удалось запустить локальный звук")
        }
    }

    private suspend fun failAudio(error: Throwable, firstActive: CompletableDeferred<Unit>) {
        stopRequested = true
        val audioError = error.asAudioFailure("Звуковой тракт отвалился")
        val failure = InworldFailureClassifier.classify(audioError)
        updateState(ConversationState.Failed(failure))
        firstActive.completeExceptionally(InworldConnectionException(failure))
        runCatching { transport?.close(reason = "audio failure") }
    }

    private suspend fun stopAudio() {
        val inputJob = audioInputJob
        audioInputJob = null
        inputJob?.cancelAndJoin()
        runCatching { audioInput?.stop() }
        runCatching { audioOutput?.stop() }
        audioStarted = false
    }

    // --- инструменты ---

    private suspend fun handleToolCall(
        event: InworldServerEvent.FunctionCallArgumentsDone,
        activeTransport: RealtimeTransport,
    ): Boolean {
        val callId = event.callId ?: return emit(ConversationEvent.Diagnostic("tool-call:missing-call-id"))

        val responseId = event.responseId ?: responseMutex.withLock { activeResponseId }
        val job = toolAdmissionMutex.withLock {
            if (!rememberToolCall(callId)) {
                null
            } else {
                val budgetDecision = budgetPolicy.admit(event.name)
                val rejection = budgetDecision as? ToolBudgetDecision.Reject
                responseId?.let(toolResponseIds::add)
                val generation = activeGeneration
                val turnGeneration = toolTurnGeneration
                lateinit var admittedJob: Job
                admittedJob = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val startedAt = System.nanoTime()
                        val result = if (rejection != null) {
                            ToolResult.rejected(rejection.message)
                        } else {
                            toolResult(event)
                        }
                        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                        emit(
                            ConversationEvent.ToolCompleted(
                                name = event.name ?: "?",
                                status = result.status,
                                durationMs = elapsedMs,
                                message = result.message,
                            ),
                        )

                        // Соединение могло смениться, пока инструмент работал: результат от
                        // мёртвого поколения отправлять некуда и незачем.
                        val stillCurrent = toolAdmissionMutex.withLock {
                            currentCoroutineContext().isActive &&
                                generation == activeGeneration &&
                                turnGeneration == toolTurnGeneration &&
                                transport === activeTransport
                        }
                        if (stillCurrent && currentCoroutineContext().isActive) {
                            runCatching {
                                activeTransport.send(protocol.functionCallOutput(callId, result))
                            }.onFailure {
                                log.debug("Отправка function_call_output не удалась | callId={}", callId)
                            }
                        } else {
                            log.debug("Отбрасываю устаревший результат инструмента | callId={}", callId)
                        }
                    } finally {
                        toolAdmissionMutex.withLock { toolJobs.remove(callId, admittedJob) }
                    }
                }
                toolJobs[callId] = admittedJob
                admittedJob
            }
        }
        job?.start()
        return false
    }

    private suspend fun toolResult(event: InworldServerEvent.FunctionCallArgumentsDone): ToolResult {
        if (event.malformed) return ToolResult.invalidArguments("Tool arguments are invalid")
        val arguments = protocol.parseToolArguments(event.arguments)
            ?: return ToolResult.invalidArguments("Tool arguments are invalid")
        val name = event.name ?: return ToolResult.rejected("Tool name is missing")
        val executor = toolExecutor ?: return ToolResult.rejected("Tools are not available")
        return try {
            executor.execute(name, arguments)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn("Инструмент {} упал: {}", name, error.toString())
            ToolResult.failed(error.message ?: "Tool execution failed")
        }
    }

    private suspend fun cancelToolJobs() {
        val jobs = toolAdmissionMutex.withLock {
            toolJobs.values.toList().also { toolJobs.clear() }
        }
        jobs.forEach { it.cancelAndJoin() }
    }

    private suspend fun resetToolTurn(): List<Job> = toolAdmissionMutex.withLock {
        toolTurnGeneration++
        if (::budgetPolicy.isInitialized) budgetPolicy.resetTurn()
        seenToolCallIds.clear()
        toolJobs.values.toList().also { toolJobs.clear() }
    }

    private fun rememberToolCall(callId: String): Boolean {
        if (!seenToolCallIds.add(callId)) return false
        if (seenToolCallIds.size > MAX_SEEN_TOOL_CALL_IDS) {
            seenToolCallIds.remove(seenToolCallIds.first())
        }
        return true
    }

    // --- жизненный цикл ответа ---

    private suspend fun requestInterruption(
        activeTransport: RealtimeTransport,
        clearInputBuffer: Boolean,
    ): CompletableDeferred<Unit>? = responseMutex.withLock {
        val responseId = activeResponseId ?: return null
        if (cancelSent) return null
        cancelSent = true
        interruptedResponseId = responseId
        audioOutput?.flush()
        if (!providerResponseDone) {
            activeTransport.send(protocol.cancelResponse(responseId))
        }
        if (clearInputBuffer) {
            runCatching { activeTransport.send(protocol.inputAudioBufferClear()) }
        }
        responseSettlement ?: CompletableDeferred<Unit>().also { responseSettlement = it }
    }

    private suspend fun completeResponse(event: InworldServerEvent.ResponseDone): Boolean {
        val shouldAwaitPlayback = responseMutex.withLock {
            val matchesActive = event.responseId == null || event.responseId == activeResponseId
            if (!matchesActive) return false
            val responseId = event.responseId ?: activeResponseId
            // Ход не завершён, пока ответ ждёт результата инструмента.
            val awaitingToolOutput = responseId != null && toolResponseIds.remove(responseId)
            providerResponseDone = true
            !awaitingToolOutput && !cancelSent && event.status != "cancelled"
        }
        if (!shouldAwaitPlayback) {
            clearResponseState(event.responseId)
            return false
        }

        // И пока колонка не доиграла: иначе микрофон откроется под собственный хвост ответа.
        val completion = audioOutput?.awaitPlaybackComplete() ?: PlaybackCompletion.PLAYED
        val shouldEmitCompletion = responseMutex.withLock {
            val matchesActive = event.responseId == null || event.responseId == activeResponseId
            matchesActive && !cancelSent && completion == PlaybackCompletion.PLAYED
        }
        clearResponseState(event.responseId)
        return if (shouldEmitCompletion) emit(ConversationEvent.ResponseCompleted) else false
    }

    private suspend fun clearResponseState(responseId: String?) {
        val settlement = responseMutex.withLock {
            var completedSettlement: CompletableDeferred<Unit>? = null
            if (responseId == null || responseId == activeResponseId) {
                activeResponseId = null
                activeResponseGeneration = 0L
                activePlaybackGeneration = 0L
                cancelSent = false
                providerResponseDone = false
                completedSettlement = responseSettlement
                responseSettlement = null
            }
            if (responseId == null || responseId == interruptedResponseId) {
                interruptedResponseId = null
            }
            completedSettlement
        }
        settlement?.complete(Unit)
    }

    private suspend fun resetResponseState() {
        toolResponseIds.clear()
        resetToolTurn()
        val settlement = responseMutex.withLock {
            activeResponseId = null
            activeResponseGeneration = 0L
            activePlaybackGeneration = 0L
            interruptedResponseId = null
            cancelSent = false
            providerResponseDone = false
            responseSettlement.also { responseSettlement = null }
        }
        settlement?.complete(Unit)
    }

    private suspend fun emit(event: ConversationEvent): Boolean {
        mutableEvents.emit(event)
        return false
    }

    private suspend fun updateState(newState: ConversationState) {
        log.debug("Состояние разговора -> {}", newState)
        mutableState.value = newState
        mutableEvents.emit(ConversationEvent.StateChanged(newState))
    }

    private fun Throwable.asAudioFailure(message: String): AudioDeviceException =
        this as? AudioDeviceException ?: AudioDeviceException(message, this)

    private data class ResponseSnapshot(
        val activeResponseId: String?,
        val interruptedResponseId: String?,
        val generation: Long,
        val playbackGeneration: Long,
    )

    private companion object {
        const val MAX_SEEN_TOOL_CALL_IDS = 16
        val log = LoggerFactory.getLogger(InworldConversationEngine::class.java)
    }
}
