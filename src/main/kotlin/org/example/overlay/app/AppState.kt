package org.example.overlay.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.overlay.audio.AudioDeviceException
import org.example.overlay.audio.AudioDevices
import org.example.overlay.audio.AudioSelfTest
import org.example.overlay.backend.AuthorizationLink
import org.example.overlay.backend.BackendClient
import org.example.overlay.backend.BackendException
import org.example.overlay.backend.Profile
import org.example.overlay.backend.SessionStore
import org.example.overlay.backend.StoredSession
import org.example.overlay.backend.ToolInfo
import org.example.overlay.backend.UnauthorizedException
import org.example.overlay.backend.VoiceSessionProvider
import org.example.overlay.conversation.ConversationEvent
import org.example.overlay.conversation.ConversationFailure
import org.example.overlay.conversation.ConversationState
import org.example.overlay.platform.BrowserLauncher
import org.example.overlay.ptt.ActivationState
import org.example.overlay.tools.ToolExecutor
import org.example.overlay.tools.ToolResult
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import java.net.http.HttpClient
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Единственный держатель UI-модели (§2 плана). UI только читает `StateFlow` и шлёт команды;
 * ничего синхронного из окон не вызывается — все обращения к сети уходят в общий скоуп.
 */
class AppState(
    private val settingsHolder: SettingsHolder,
    private val sessionStore: SessionStore,
    private val backend: BackendClient,
    private val scope: CoroutineScope,
    voiceSessionProvider: VoiceSessionProvider,
    toolExecutor: ToolExecutor,
    httpClient: HttpClient,
) {
    val settings: StateFlow<Settings> = settingsHolder.settings

    /** Голосовой тракт собирается при включении: до `/voice/session` формат звука неизвестен. */
    private val voice = VoiceRuntime(
        scope = scope,
        settings = settingsHolder,
        voiceSessionProvider = voiceSessionProvider,
        toolExecutor = toolExecutor,
        httpClient = httpClient,
        mapper = backend.mapper,
        onEvent = ::onConversationEvent,
        onMicLevel = { level -> _micLevel.value = level },
    )

    val voiceEnabled: StateFlow<Boolean> = voice.enabled

    private val _status = MutableStateFlow<OverlayStatus>(OverlayStatus.Disconnected)
    val status: StateFlow<OverlayStatus> = _status.asStateFlow()

    private val _consoleVisible = MutableStateFlow(false)
    val consoleVisible: StateFlow<Boolean> = _consoleVisible.asStateFlow()

    private val _session = MutableStateFlow<StoredSession?>(null)
    val session: StateFlow<StoredSession?> = _session.asStateFlow()

    private val _profile = MutableStateFlow<Profile?>(null)
    val profile: StateFlow<Profile?> = _profile.asStateFlow()

    private val _tools = MutableStateFlow<List<ToolInfo>>(emptyList())
    val tools: StateFlow<List<ToolInfo>> = _tools.asStateFlow()

    /** Текст под формой логина: ошибка бэкенда или подсказка. */
    private val _accountMessage = MutableStateFlow<String?>(null)
    val accountMessage: StateFlow<String?> = _accountMessage.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _toolLog = MutableStateFlow<List<ToolLogEntry>>(emptyList())
    val toolLog: StateFlow<List<ToolLogEntry>> = _toolLog.asStateFlow()

    /** Сырой ответ последнего ручного вызова — нужен, чтобы глазами проверить инструмент. */
    private val _lastToolOutput = MutableStateFlow<String?>(null)
    val lastToolOutput: StateFlow<String?> = _lastToolOutput.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _audioMessage = MutableStateFlow<String?>(null)
    val audioMessage: StateFlow<String?> = _audioMessage.asStateFlow()

    private val _audioBusy = MutableStateFlow(false)
    val audioBusy: StateFlow<Boolean> = _audioBusy.asStateFlow()

    private val _userTranscript = MutableStateFlow("")
    val userTranscript: StateFlow<String> = _userTranscript.asStateFlow()

    private val _assistantTranscript = MutableStateFlow("")
    val assistantTranscript: StateFlow<String> = _assistantTranscript.asStateFlow()

    private val _voiceMessage = MutableStateFlow<String?>(null)
    val voiceMessage: StateFlow<String?> = _voiceMessage.asStateFlow()

    /** Ссылка авторизации Bungie: пока она есть, в HUD висит плашка «открой и вернись». */
    private val _authUrl = MutableStateFlow<String?>(null)
    val authUrl: StateFlow<String?> = _authUrl.asStateFlow()

    private val _authPolling = MutableStateFlow(false)
    val authPolling: StateFlow<Boolean> = _authPolling.asStateFlow()

    private var authPollJob: Job? = null

    private val userBuffer = TranscriptBuffer()
    private val assistantBuffer = TranscriptBuffer()

    /** Состояние движка держим отдельно: статус HUD собирается из него и из состояния клавиши. */
    private val _conversationState = MutableStateFlow<ConversationState>(ConversationState.Idle)

    init {
        restoreSession()
        scope.launch {
            combine(_conversationState, voice.activation, voice.enabled, ::resolveStatus)
                .collect { _status.value = it }
        }
    }

    // --- голос ---

    /** Одно авто-переоткрытие звуковых линий на включение тракта, не больше. */
    private var audioRecoveryUsed = false

    fun toggleVoice() {
        scope.launch {
            _voiceMessage.value = null
            try {
                if (voice.enabled.value) {
                    voice.disable()
                } else {
                    audioRecoveryUsed = false
                    voice.enable()
                }
            } catch (error: UnauthorizedException) {
                logout()
                _voiceMessage.value = "Сессия недействительна — войди заново"
            } catch (error: Throwable) {
                // Именно Throwable: VerifyError и прочие Error иначе убивают корутину молча,
                // и кнопка выглядит сломанной, хотя проблема совсем в другом месте.
                log.error("Не удалось переключить голосовой тракт", error)
                _voiceMessage.value = "${error.javaClass.simpleName}: ${error.message ?: "без описания"}"
            }
        }
    }

    private suspend fun onConversationEvent(event: ConversationEvent) {
        when (event) {
            is ConversationEvent.StateChanged -> {
                _conversationState.value = event.state
                if (event.state is ConversationState.Failed) {
                    _voiceMessage.value = event.state.failure.message
                    handleConversationFailure(event.state.failure)
                }
            }

            is ConversationEvent.Transcript -> when (event.speaker) {
                ConversationEvent.Speaker.USER ->
                    _userTranscript.value = userBuffer.accept(event.text, event.isFinal)

                ConversationEvent.Speaker.ASSISTANT ->
                    _assistantTranscript.value = assistantBuffer.accept(event.text, event.isFinal)
            }

            is ConversationEvent.ResponseStarted -> _assistantTranscript.value = assistantBuffer.clear()

            is ConversationEvent.ToolCompleted -> appendToolLog(
                ToolLogEntry(event.name, event.status, event.durationMs, event.message),
            )

            else -> Unit
        }
    }

    /**
     * Реакции на отказы (§7 фазы). Всё запускается отдельной корутиной: обработчик живёт внутри
     * подписки на события движка, а перезапуск тракта эту самую подписку отменяет.
     */
    private fun handleConversationFailure(failure: ConversationFailure) {
        when (failure.category) {
            ConversationFailure.Category.AUDIO_DEVICE -> scope.launch {
                if (audioRecoveryUsed) {
                    _voiceMessage.value = "${failure.message}. Проверь устройство и включи голос кнопкой."
                    runCatching { voice.disable() }
                    return@launch
                }
                // Одно автоматическое переоткрытие линий, дальше — решение игрока (риск 3).
                audioRecoveryUsed = true
                _voiceMessage.value = "Звуковое устройство отвалилось — переоткрываю линии"
                runCatching {
                    voice.disable()
                    voice.enable()
                }.onFailure { error ->
                    _voiceMessage.value = "Звук не поднялся: ${error.message}. Включи голос заново кнопкой."
                }
            }

            ConversationFailure.Category.AUTHENTICATION -> scope.launch {
                runCatching { voice.disable() }
                // Транскрипт при этом не чистим: игрок должен видеть, на чём всё оборвалось.
                if (failure.message.contains("бэкенд", ignoreCase = true)) {
                    logout()
                    _accountMessage.value = "Сессия истекла — войди заново"
                }
            }

            else -> Unit
        }
    }

    private fun resolveStatus(
        conversation: ConversationState,
        activation: ActivationState,
        voiceOn: Boolean,
    ): OverlayStatus = when {
        conversation is ConversationState.Failed -> OverlayStatus.Failed(conversation.failure.message)
        conversation is ConversationState.Reconnecting -> OverlayStatus.Reconnecting(conversation.attempt)
        activation is ActivationState.Failed -> OverlayStatus.Failed(activation.message)
        activation is ActivationState.Connecting -> OverlayStatus.Connecting
        activation is ActivationState.Listening -> OverlayStatus.Listening
        activation is ActivationState.Thinking -> OverlayStatus.Thinking
        // Без озвучки «Говорю» врёт: ассистент отвечает текстом.
        activation is ActivationState.Speaking ->
            if (settingsHolder.current.speakResponses) OverlayStatus.Speaking else OverlayStatus.Answering
        voiceOn -> OverlayStatus.Ready
        else -> OverlayStatus.Disconnected
    }

    // --- настройки и окна ---

    fun updateSettings(transform: (Settings) -> Settings) = settingsHolder.update(transform)

    fun setStatus(status: OverlayStatus) {
        _status.value = status
    }

    fun setConsoleVisible(visible: Boolean) {
        _consoleVisible.value = visible
    }

    fun toggleConsole() = _consoleVisible.update { !it }

    fun flushSettings() = settingsHolder.flush()

    // --- аккаунт ---

    fun register(username: String, password: String, rememberPassword: Boolean) = launchAccount {
        backend.register(username, password)
        // Регистрация не выдаёт токен, поэтому сразу логинимся теми же данными.
        performLogin(username, password, rememberPassword)
    }

    fun login(username: String, password: String, rememberPassword: Boolean) = launchAccount {
        performLogin(username, password, rememberPassword)
    }

    fun logout() {
        sessionStore.clear()
        _session.value = null
        _profile.value = null
        _tools.value = emptyList()
        _accountMessage.value = null
        _lastToolOutput.value = null
    }

    fun refreshAccount() = launchAccount { loadProfileAndTools(requireToken()) }

    /** Ручной вызов инструмента из консоли — тот же путь, которым позже пойдёт `function_call`. */
    fun callTool(name: String, argumentsJson: String) = launchAccount {
        val token = requireToken()
        val arguments = parseArguments(argumentsJson)
        val startedAt = System.nanoTime()
        val result = backend.callTool(token, name, arguments)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        appendToolLog(ToolLogEntry(name, result.status, elapsedMs, result.message))
        _lastToolOutput.value = backend.mapper.writeValueAsString(result)
    }

    // --- звук ---

    /** Эхо-тест фазы 2: пишем несколько секунд и проигрываем обратно. */
    fun runAudioSelfTest(seconds: Int = AudioSelfTest.DEFAULT_SECONDS) {
        if (_audioBusy.value) return
        scope.launch {
            _audioBusy.value = true
            _audioMessage.value = "Говори — идёт запись…"
            try {
                val settings = settingsHolder.current.audio
                val report = AudioSelfTest(scope).run(
                    inputMixer = AudioDevices.resolve(settings.inputMixer, AudioDevices.inputs()),
                    outputMixer = AudioDevices.resolve(settings.outputMixer, AudioDevices.outputs()),
                    seconds = seconds,
                    onLevel = { level -> _micLevel.value = level },
                )
                _audioMessage.value = report
            } catch (error: AudioDeviceException) {
                _audioMessage.value = error.message
            } catch (error: Exception) {
                log.warn("Эхо-тест не удался", error)
                _audioMessage.value = error.message ?: error.javaClass.simpleName
            } finally {
                _micLevel.value = 0f
                _audioBusy.value = false
            }
        }
    }

    // --- привязка Bungie (§6 фазы) ---

    /** Кнопка «Привязать»: тот же `authorize`, только без голоса. */
    fun linkBungie() = launchAccount {
        val token = requireToken()
        val result = backend.callTool(token, AuthorizationLink.TOOL_NAME, backend.mapper.createObjectNode())
        appendToolLog(ToolLogEntry(AuthorizationLink.TOOL_NAME, result.status, 0))
        val url = AuthorizationLink.extract(ToolResult(result.status, result.output, result.message))
        if (url == null) {
            _accountMessage.value = result.message ?: "Бэкенд не вернул ссылку авторизации"
        } else {
            showAuthorizationPrompt(url)
        }
    }

    /** Голосовой путь: модель сама зовёт `authorize`, ссылку ловим здесь. */
    fun onToolResult(name: String, result: ToolResult) {
        if (name == AuthorizationLink.TOOL_NAME || AuthorizationLink.mentionsRelink(result)) {
            AuthorizationLink.extract(result)?.let(::showAuthorizationPrompt)
        }
    }

    fun dismissAuthorizationPrompt() {
        authPollJob?.cancel()
        authPollJob = null
        _authPolling.value = false
        _authUrl.value = null
    }

    fun openAuthorizationLink() {
        _authUrl.value?.let(BrowserLauncher::open)
    }

    private fun showAuthorizationPrompt(url: String) {
        _authUrl.value = url
        // Браузер открываем сразу: игрок и так должен уйти из игры, лишний клик тут ни к чему.
        BrowserLauncher.open(url)
        startProfilePolling()
    }

    /** Опрос профиля раз в 3 секунды до 5 минут, дальше — кнопка «Проверить ещё раз». */
    fun startProfilePolling() {
        authPollJob?.cancel()
        _authPolling.value = true
        authPollJob = scope.launch {
            val deadline = System.nanoTime() + POLL_LIMIT_MINUTES * 60L * 1_000_000_000L
            while (isActive && System.nanoTime() < deadline) {
                delay(POLL_INTERVAL_MS)
                val token = _session.value?.token ?: break
                val profile = runCatching { backend.profile(token) }.getOrNull() ?: continue
                _profile.value = profile
                if (profile.bungieLinked) {
                    _authUrl.value = null
                    break
                }
            }
            _authPolling.value = false
        }
    }

    fun appendToolLog(entry: ToolLogEntry) {
        _toolLog.update { (it + entry).takeLast(MAX_TOOL_LOG) }
    }

    private suspend fun performLogin(username: String, password: String, rememberPassword: Boolean) {
        val response = backend.login(username, password)
        val session = StoredSession(
            username = username,
            token = response.token,
            expiresAtEpochSecond = parseExpiry(response.expiresAt),
            password = password.takeIf { rememberPassword },
        )
        sessionStore.save(session)
        _session.value = session
        settingsHolder.update { it.copy(rememberPassword = rememberPassword) }
        loadProfileAndTools(session.token)
    }

    private suspend fun loadProfileAndTools(token: String) {
        _profile.value = backend.profile(token)
        _tools.value = backend.tools(token)
    }

    private fun restoreSession() {
        val stored = sessionStore.load() ?: return
        if (stored.isExpired()) {
            sessionStore.clear()
            _accountMessage.value = "Сессия истекла — войди заново"
            return
        }
        _session.value = stored
        refreshAccount()
    }

    private fun requireToken(): String =
        _session.value?.token ?: throw BackendException(401, "Сначала нужно войти")

    private fun parseArguments(raw: String): JsonNode {
        if (raw.isBlank()) return backend.mapper.createObjectNode()
        return try {
            backend.mapper.readTree(raw)
        } catch (error: Exception) {
            throw IllegalArgumentException("Аргументы не разобраны как JSON: ${error.message}")
        }
    }

    private fun parseExpiry(raw: String): Long = try {
        Instant.parse(raw).epochSecond
    } catch (error: Exception) {
        log.warn("Не разобрать expiresAt '{}': {}. Считаю срок 12 часов от сейчас.", raw, error.toString())
        Instant.now().plus(12, ChronoUnit.HOURS).epochSecond
    }

    private fun launchAccount(block: suspend () -> Unit) {
        scope.launch {
            _busy.value = true
            _accountMessage.value = null
            try {
                block()
            } catch (error: UnauthorizedException) {
                // Refresh-ручки нет: единственная честная реакция — вернуть игрока на логин (§4).
                logout()
                _accountMessage.value = "Сессия недействительна — войди заново"
            } catch (error: BackendException) {
                _accountMessage.value = error.message
            } catch (error: Exception) {
                log.warn("Операция с бэкендом не удалась", error)
                _accountMessage.value = error.message ?: error.javaClass.simpleName
            } finally {
                _busy.value = false
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(AppState::class.java)
        const val MAX_TOOL_LOG = 50
        const val POLL_INTERVAL_MS = 3_000L
        const val POLL_LIMIT_MINUTES = 5L
    }
}
