package org.example.overlay.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.overlay.audio.AudioDeviceException
import org.example.overlay.audio.AudioDevices
import org.example.overlay.audio.AudioSelfTest
import org.example.overlay.backend.AuthorizationLink
import org.example.overlay.backend.BackendClient
import org.example.overlay.backend.BackendException
import org.example.overlay.backend.ChatStreamEvent
import org.example.overlay.backend.Profile
import org.example.overlay.backend.SessionStore
import org.example.overlay.backend.StoredSession
import org.example.overlay.backend.UnauthorizedException
import org.example.overlay.backend.VoiceModels
import org.example.overlay.platform.BrowserLauncher
import org.example.overlay.tools.ToolResult
import org.example.overlay.update.UpdateManager
import org.example.overlay.update.UpdateState
import org.slf4j.LoggerFactory
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
    private val updates: UpdateManager,
    private val scope: CoroutineScope,
) {
    val settings: StateFlow<Settings> = settingsHolder.settings

    /**
     * Голос всегда наготове: микрофон и клавиша поднимаются вместе с приложением, соединения
     * ни с кем не держится — ход уходит на сервер только по отпусканию клавиши.
     */
    private val voice = VoiceRuntime(
        scope = scope,
        settings = settingsHolder,
        backend = backend,
        tokenProvider = { _session.value?.token },
        onUserText = { text ->
            _userTranscript.value = text
            _assistantTranscript.value = assistantBuffer.clear()
            // Журнал инструментов и строка ошибки относятся к ходу: с прошлого хода в HUD
            // висели чужие строки, а сообщение само не гаснет.
            _toolLog.value = emptyList()
            _voiceMessage.value = null
        },
        onEvent = ::onChatEvent,
        onMicLevel = { level -> _micLevel.value = level },
        onError = { message -> _voiceMessage.value = message },
    )

    val voiceEnabled: StateFlow<Boolean> = voice.ready

    private val _status = MutableStateFlow<OverlayStatus>(OverlayStatus.Disconnected)
    val status: StateFlow<OverlayStatus> = _status.asStateFlow()

    private val _consoleVisible = MutableStateFlow(false)
    val consoleVisible: StateFlow<Boolean> = _consoleVisible.asStateFlow()

    /** Растёт на каждый [openConsole]: консоль по нему разворачивается из свёрнутого. */
    private val _consoleRaise = MutableStateFlow(0)
    val consoleRaise: StateFlow<Int> = _consoleRaise.asStateFlow()

    private val _session = MutableStateFlow<StoredSession?>(null)
    val session: StateFlow<StoredSession?> = _session.asStateFlow()

    private val _profile = MutableStateFlow<Profile?>(null)
    val profile: StateFlow<Profile?> = _profile.asStateFlow()

    /** Модели с сервера. Пусто, пока нет сессии или ручка недоступна — селектор тогда скрыт. */
    private val _chatModels = MutableStateFlow<VoiceModels?>(null)
    val chatModels: StateFlow<VoiceModels?> = _chatModels.asStateFlow()

    /** Текст под формой логина: ошибка бэкенда или подсказка. */
    private val _accountMessage = MutableStateFlow<String?>(null)
    val accountMessage: StateFlow<String?> = _accountMessage.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _toolLog = MutableStateFlow<List<ToolLogEntry>>(emptyList())
    val toolLog: StateFlow<List<ToolLogEntry>> = _toolLog.asStateFlow()

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

    /** Автообновление: баннер в консоли, бейдж на пилюле, выход перед установкой. */
    val updateState: StateFlow<UpdateState> = updates.state
    val updateInstallStarted: StateFlow<Boolean> = updates.installStarted

    private val assistantBuffer = TranscriptBuffer()

    /** Курсор над HUD: отсчёт сворачивания стоит на месте и сброшен на старт. */
    private val _hudHovered = MutableStateFlow(false)

    /**
     * Сколько осталось висеть развёрнутым после ответа: 1 → только начали, 0 → пора сворачиваться,
     * `null` — отсчёт не идёт. HUD рисует из этого кольцо на месте статусной иконки.
     */
    private val _collapseFraction = MutableStateFlow<Float?>(null)
    val collapseFraction: StateFlow<Float?> = _collapseFraction.asStateFlow()

    /**
     * Отсчёт истёк — HUD сворачивается. Лента при этом ещё не тронута: она чистится с задержкой,
     * чтобы ответ оставался виден, пока панель схлопывается, и уезжал за её край.
     */
    private val _hudDismissed = MutableStateFlow(false)
    val hudDismissed: StateFlow<Boolean> = _hudDismissed.asStateFlow()

    init {
        restoreSession()
        // Микрофон и клавиша поднимаются сразу: соединения это не открывает, а «включать голос»
        // руками игроку незачем.
        startVoice()
        updates.start()
        scope.launch {
            combine(voice.phase, voice.ready, ::resolveStatus).collect { _status.value = it }
        }
        scope.launch { runCollapseCountdown() }
    }

    /**
     * Автосворачивание HUD: ход закончился, ответ повисел [HudSettings.collapseSeconds] секунд —
     * лента чистится, и HUD спадает в пилюлю. Наведение курсора не просто ставит отсчёт на паузу,
     * а возвращает его на старт: игрок читает — торопить нечего.
     *
     * Плашка авторизации Bungie отсчёт блокирует: её нельзя «досмотреть», по ней нужно сходить.
     */
    private suspend fun runCollapseCountdown() {
        val hasContent = combine(_userTranscript, _assistantTranscript, _toolLog, _voiceMessage) { user, answer, tools, message ->
            user.isNotBlank() || answer.isNotBlank() || tools.isNotEmpty() || message != null
        }
        combine(_status, hasContent, _authUrl) { status, content, auth ->
            status == OverlayStatus.Ready && content && auth == null
        }
            .distinctUntilChanged()
            .collectLatest { eligible ->
                if (!eligible) {
                    _collapseFraction.value = null
                    return@collectLatest
                }
                val totalMs = settings.value.hud.collapseSeconds
                    .coerceIn(HudSettings.MIN_COLLAPSE_SECONDS, HudSettings.MAX_COLLAPSE_SECONDS) * 1000L
                var remainingMs = totalMs
                _collapseFraction.value = 1f
                while (remainingMs > 0) {
                    delay(COLLAPSE_TICK_MS)
                    if (_hudHovered.value) {
                        remainingMs = totalMs
                        _collapseFraction.value = 1f
                    } else {
                        remainingMs -= COLLAPSE_TICK_MS
                        _collapseFraction.value = (remainingMs.toFloat() / totalMs).coerceAtLeast(0f)
                    }
                }
                _collapseFraction.value = null
                _hudDismissed.value = true
                try {
                    // Панель схлопывается с ещё живым контентом; чистим, когда анимация точно
                    // закончилась. Отмена (новый ход, плашка авторизации) тоже проходит через
                    // finally: свёрнутый ответ в любом случае больше не нужен.
                    delay(HUD_DISMISS_CLEAR_MS)
                } finally {
                    clearHudFeed()
                    _hudDismissed.value = false
                }
            }
    }

    fun setHudHovered(hovered: Boolean) {
        _hudHovered.value = hovered
    }

    fun installUpdate() = updates.install()

    /** Убрать с экрана прошедший ход. Только лента HUD: серверная история не трогается. */
    private fun clearHudFeed() {
        _userTranscript.value = ""
        _assistantTranscript.value = assistantBuffer.clear()
        _toolLog.value = emptyList()
        _voiceMessage.value = null
    }

    // --- голос ---

    /** Поднять микрофон и клавишу. Зовётся при старте: включать голос руками больше нечего. */
    fun startVoice() {
        scope.launch {
            _voiceMessage.value = null
            try {
                voice.start()
            } catch (error: Throwable) {
                // Именно Throwable: Error иначе убивает корутину молча, и тракт выглядит
                // сломанным без единого слова в интерфейсе.
                log.error("Не удалось поднять голосовой тракт", error)
                _voiceMessage.value = "${error.javaClass.simpleName}: ${error.message ?: "без описания"}"
            }
        }
    }

    /** Переоткрыть звуковые линии: после смены устройства или обрыва (риск 3). */
    fun restartVoice() {
        scope.launch {
            _voiceMessage.value = null
            runCatching { voice.restart() }
                .onFailure { error -> _voiceMessage.value = "Звук не поднялся: ${error.message}" }
        }
    }

    /**
     * Забыть разговор: сервер чистит историю, оверлей — свои ленты, чтобы вид совпадал.
     * Подтверждение не показываем: опустевший HUD и есть подтверждение, а строка сообщения
     * сама не гаснет и висела бы до следующей ошибки.
     */
    fun clearConversation() {
        scope.launch {
            try {
                backend.clearVoiceHistory(requireToken())
                _userTranscript.value = ""
                _assistantTranscript.value = assistantBuffer.clear()
                _toolLog.value = emptyList()
                _voiceMessage.value = null
            } catch (error: Exception) {
                _voiceMessage.value = "История не сбросилась: ${error.message}"
            }
        }
    }

    private fun onChatEvent(event: ChatStreamEvent) {
        when (event) {
            is ChatStreamEvent.Delta -> _assistantTranscript.value = assistantBuffer.accept(event.text, false)
            is ChatStreamEvent.Done -> if (event.text.isNotBlank()) {
                _assistantTranscript.value = assistantBuffer.accept(event.text, true)
            }

            is ChatStreamEvent.Tool -> appendToolLog(ToolLogEntry(event.name, event.status, event.durationMs))
            is ChatStreamEvent.Failed -> _voiceMessage.value = event.message
        }
    }

    private fun resolveStatus(phase: VoicePhase, ready: Boolean): OverlayStatus = when {
        !ready -> OverlayStatus.Disconnected
        phase == VoicePhase.LISTENING -> OverlayStatus.Listening
        phase == VoicePhase.TRANSCRIBING -> OverlayStatus.Thinking
        phase == VoicePhase.ANSWERING -> OverlayStatus.Answering
        else -> OverlayStatus.Ready
    }

    // --- настройки и окна ---

    fun updateSettings(transform: (Settings) -> Settings) = settingsHolder.update(transform)

    fun setStatus(status: OverlayStatus) {
        _status.value = status
    }

    fun setConsoleVisible(visible: Boolean) {
        _consoleVisible.value = visible
    }

    /**
     * Открыть консоль по-настоящему: окно может уже существовать, но быть свёрнутым —
     * одна лишь видимость его не разворачивает. Счётчик — сигнал окну развернуться
     * и подняться наверх (см. `ConsoleWindow`).
     */
    fun openConsole() {
        _consoleVisible.value = true
        _consoleRaise.update { it + 1 }
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
        _chatModels.value = null
        _accountMessage.value = null
    }

    fun refreshAccount() = launchAccount { loadAccountData(requireToken()) }

    private suspend fun loadAccountData(token: String) {
        _profile.value = backend.profile(token)
        // Список моделей — украшение, а не условие: без него голос работает на серверном дефолте.
        _chatModels.value = runCatching { backend.voiceModels(token) }
            .onFailure { error -> log.warn("Список моделей не загрузился: {}", error.toString()) }
            .getOrNull()
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

    /**
     * Кнопка «Отвязать аккаунт». Серверная ручка ещё не поднята: до неё клик покажет
     * ошибку бэкенда, а когда появится — заработает без правок здесь.
     */
    fun unlinkBungie() = launchAccount {
        backend.unlinkBungie(requireToken())
        loadAccountData(requireToken())
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
        _toolLog.update { log ->
            // Поиск обновляется на месте: «ищу…» сменяется итогом, а не копится строками.
            val base = if (entry.isWebSearch) log.filterNot { it.isWebSearch } else log
            (base + entry).takeLast(MAX_TOOL_LOG)
        }
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
        loadAccountData(session.token)
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

        /** Шаг отсчёта сворачивания: кольцо в 18 dp чаще перерисовывать незачем. */
        const val COLLAPSE_TICK_MS = 100L

        /** Запас на двухфазную анимацию сворачивания, после него лента чистится незаметно. */
        const val HUD_DISMISS_CLEAR_MS = 900L
    }
}
