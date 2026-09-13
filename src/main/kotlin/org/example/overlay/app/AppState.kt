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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.overlay.backend.AuthorizationLink
import org.example.overlay.backend.BackendClient
import org.example.overlay.backend.ProviderApiKeyStore
import org.example.overlay.backend.ProviderApiKeys
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
    private val providerApiKeyStore: ProviderApiKeyStore,
    private val backend: BackendClient,
    private val updates: UpdateManager,
    private val scope: CoroutineScope,
) {
    val settings: StateFlow<Settings> = settingsHolder.settings

    private val _providerApiKeys = MutableStateFlow(providerApiKeyStore.load())
    val providerApiKeys: StateFlow<ProviderApiKeys> = _providerApiKeys.asStateFlow()

    private val _hotkeyCaptureActive = MutableStateFlow(false)
    private val _activeToolName = MutableStateFlow<String?>(null)

    private val _hudInteractionMode = MutableStateFlow(HudInteractionMode.PASS_THROUGH)
    val hudInteractionMode: StateFlow<HudInteractionMode> = _hudInteractionMode.asStateFlow()

    /**
     * Голос всегда наготове: микрофон и клавиша поднимаются вместе с приложением, соединения
     * ни с кем не держится — ход уходит на сервер только по отпусканию клавиши.
     */
    private val voice = VoiceRuntime(
        scope = scope,
        settings = settingsHolder,
        backend = backend,
        tokenProvider = { _session.value?.token },
        providerApiKey = ::providerApiKey,
        hotkeyEnabled = { !_hotkeyCaptureActive.value },
        onHudDoubleTap = ::cycleHudInteractionMode,
        onTurnStarted = ::keepHudOpenForListening,
        onUserText = ::replaceHudFeedForNewTurn,
        onEvent = ::onChatEvent,
        onMicLevel = { level -> _micLevel.value = level },
        onError = { message ->
            _activeToolName.value = null
            _voiceMessage.value = message
        },
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

    private val _userTranscript = MutableStateFlow("")
    val userTranscript: StateFlow<String> = _userTranscript.asStateFlow()

    private val _assistantTranscript = MutableStateFlow("")
    val assistantTranscript: StateFlow<String> = _assistantTranscript.asStateFlow()

    private val _voiceMessage = MutableStateFlow<String?>(null)
    val voiceMessage: StateFlow<String?> = _voiceMessage.asStateFlow()

    /** Завершённые ходы для листания прошлых ответов в HUD: хвост из [MAX_TURN_HISTORY]. */
    private val _turnHistory = MutableStateFlow<List<HudTurn>>(emptyList())
    val turnHistory: StateFlow<List<HudTurn>> = _turnHistory.asStateFlow()

    /** null — живая лента; иначе индекс хода из [turnHistory], который сейчас смотрит игрок. */
    private val _turnHistoryIndex = MutableStateFlow<Int?>(null)
    val turnHistoryIndex: StateFlow<Int?> = _turnHistoryIndex.asStateFlow()

    /** Ссылка авторизации Bungie: пока она есть, в HUD висит плашка «открой и вернись». */
    private val _authUrl = MutableStateFlow<String?>(null)
    val authUrl: StateFlow<String?> = _authUrl.asStateFlow()

    private val _authPolling = MutableStateFlow(false)
    val authPolling: StateFlow<Boolean> = _authPolling.asStateFlow()

    private var authPollJob: Job? = null
    private var hudInteractionTimeoutJob: Job? = null

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

    /**
     * Номер живой ленты и её мьютекс. Отсчёт и голосовой ход живут в общем
     * Dispatchers.Default и могут закончиться одновременно. Номер не даёт старому отсчёту
     * очистить уже начавшийся новый ход, а мьютекс делает проверку и очистку неразрывными.
     */
    private val hudFeedLock = Any()
    private val _hudFeedRevision = MutableStateFlow(0L)

    init {
        restoreSession()
        // Микрофон и клавиша поднимаются сразу: соединения это не открывает, а «включать голос»
        // руками игроку незачем.
        startVoice()
        updates.start()
        scope.launch {
            combine(voice.phase, voice.ready, _activeToolName, ::resolveStatus).collect { _status.value = it }
        }
        scope.launch { runCollapseCountdown() }
        scope.launch { refreshProfilePeriodically() }
    }

    /**
     * Профиль подтягивается фоном: время синхронизации инвентаря в HUD должно расти
     * от реальной последней синхронизации, а не от момента логина.
     */
    private suspend fun refreshProfilePeriodically() {
        while (true) {
            delay(PROFILE_REFRESH_MS)
            val token = _session.value?.token ?: continue
            runCatching { _profile.value = backend.profile(token) }
                .onFailure { error -> log.debug("Background profile refresh failed: {}", error.toString()) }
        }
    }

    /**
     * Автосворачивание HUD: ход закончился, ответ повисел [HudSettings.collapseSeconds] секунд —
     * лента чистится, и HUD спадает в пилюлю. Наведение курсора не просто ставит отсчёт на паузу,
     * а возвращает его на старт: игрок читает — торопить нечего.
     *
     * Плашка авторизации Bungie отсчёт блокирует: её нельзя «досмотреть», по ней нужно сходить.
     */
    private suspend fun runCollapseCountdown() {
        // Просмотр истории — тоже контент: без него HUD с открытым прошлым ответом
        // не попадал бы под отсчёт и висел бы развёрнутым вечно.
        val hasContent = combine(
            _userTranscript,
            _assistantTranscript,
            _toolLog,
            _voiceMessage,
            _turnHistoryIndex,
        ) { user, answer, tools, message, viewing ->
            user.isNotBlank() || answer.isNotBlank() || tools.isNotEmpty() || message != null || viewing != null
        }
        combine(_status, hasContent, _authUrl, _hudFeedRevision, _hudHovered) {
                status, content, auth, revision, hovered ->
            Triple(status == OverlayStatus.Ready && content && auth == null, revision, hovered)
        }
            .distinctUntilChanged()
            .collectLatest { (eligible, revision, hovered) ->
                if (!eligible) {
                    _collapseFraction.value = null
                    _hudDismissed.value = false
                    return@collectLatest
                }
                if (hovered) {
                    // Наведение отменяет даже уже начавшуюся задержку очистки. После ухода
                    // новый upstream-снимок запустит полный отсчёт с начала.
                    _hudDismissed.value = false
                    _collapseFraction.value = 1f
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
                val dismissalStarted = synchronized(hudFeedLock) {
                    if (_hudFeedRevision.value != revision || _hudHovered.value) {
                        false
                    } else {
                        // Сначала скрываем панель и только потом убираем кольцо. Обратный порядок
                        // давал один кадр развёрнутого HUD без статусной иконки.
                        _hudDismissed.value = true
                        _collapseFraction.value = null
                        true
                    }
                }
                if (!dismissalStarted) return@collectLatest

                // Отмена collectLatest больше не чистит ленту в finally: новый ход может уже успеть
                // записать свой текст. После анимации чистим только ту же ревизию.
                delay(HUD_DISMISS_CLEAR_MS)
                synchronized(hudFeedLock) {
                    if (_hudFeedRevision.value == revision && !_hudHovered.value) {
                        clearHudFeedLocked()
                        _hudDismissed.value = false
                    }
                }
            }
    }

    /**
     * Наведение на пустой HUD сразу открывает последний ответ: игрок навёл курсор именно затем,
     * чтобы перечитать сказанное. Курсор ушёл — запускается обычный отсчёт, после него HUD
     * сворачивается и закрывает открытый ход.
     * Живую ленту не трогаем: пока на экране висит свежий ответ, показывать нужно его.
     */
    fun setHudHovered(hovered: Boolean) = synchronized(hudFeedLock) {
        _hudHovered.value = hovered
        if (_assistantTranscript.value.isNotBlank() || _userTranscript.value.isNotBlank()) {
            return@synchronized
        }
        val last = _turnHistory.value.lastIndex.takeIf { it >= 0 }
        _turnHistoryIndex.update { current ->
            // Уход указателя не меняет страницу: любая из них закрывается одним и тем же
            // отсчётом. Индекс очистится вместе с лентой после двухфазного сворачивания.
            hudHistoryIndexForHover(current, last, hovered)
        }
    }

    private fun cycleHudInteractionMode() {
        val nextMode = synchronized(hudFeedLock) {
            val next = _hudInteractionMode.value.next()
            _hudInteractionMode.value = next
            _hudFeedRevision.value += 1
            _hudDismissed.value = false
            next
        }
        hudInteractionTimeoutJob?.cancel()
        hudInteractionTimeoutJob = null
        if (nextMode != HudInteractionMode.PASS_THROUGH) {
            hudInteractionTimeoutJob = scope.launch {
                voice.phase.filter { it == VoicePhase.IDLE }.first()
                val timeoutMs = settings.value.hud.collapseSeconds
                    .coerceIn(HudSettings.MIN_COLLAPSE_SECONDS, HudSettings.MAX_COLLAPSE_SECONDS) * 1000L
                delay(timeoutMs)
                _hudInteractionMode.value = HudInteractionMode.PASS_THROUGH
            }
        }
    }

    fun installUpdate() = updates.install()

    /**
     * Новый PTT отменяет сворачивание, но не убирает предыдущий ответ во время записи.
     * Лента заменится распознанной фразой в [replaceHudFeedForNewTurn], поэтому HUD не
     * уменьшается под пальцем и у игрока остаётся контекст следующего вопроса.
     */
    private fun keepHudOpenForListening() = synchronized(hudFeedLock) {
        _activeToolName.value = null
        _hudFeedRevision.value += 1
        _collapseFraction.value = null
        _hudDismissed.value = false
    }

    private fun replaceHudFeedForNewTurn(text: String) = synchronized(hudFeedLock) {
        _hudFeedRevision.value += 1
        // Старая лента и режим истории исчезают до публикации новой фразы. Так UI не успевает
        // собрать переходный кадр из нового вопроса и старого ответа/ошибки.
        clearHudFeedLocked()
        _userTranscript.value = text
        _collapseFraction.value = null
        _hudDismissed.value = false
    }

    private fun clearHudFeedLocked() {
        _userTranscript.value = ""
        _assistantTranscript.value = assistantBuffer.clear()
        _toolLog.value = emptyList()
        _voiceMessage.value = null
        _turnHistoryIndex.value = null
    }

    /**
     * Показать предыдущий ответ. С живой ленты уходим на предпоследний ход: последний
     * и есть то, что на экране. Если лента уже пуста — на последний.
     */
    fun showPreviousTurn() {
        val history = _turnHistory.value
        if (history.isEmpty()) return
        _turnHistoryIndex.update { current ->
            when {
                current != null -> (current - 1).coerceAtLeast(0)
                _assistantTranscript.value.isBlank() -> history.size - 1
                else -> (history.size - 2).coerceAtLeast(0)
            }
        }
    }

    /** Вперёд по истории. Последний сохранённый ход — конец списка. */
    fun showNextTurn() {
        _turnHistoryIndex.update { current -> nextHudHistoryIndex(current, _turnHistory.value.size) }
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
                log.error("Could not start voice pipeline", error)
                _voiceMessage.value = "${error.javaClass.simpleName}: ${error.message ?: "no details"}"
            }
        }
    }

    /** Переоткрыть звуковые линии: после смены устройства или обрыва (риск 3). */
    fun restartVoice() {
        scope.launch {
            _voiceMessage.value = null
            runCatching { voice.restart() }
                .onFailure { error -> _voiceMessage.value = "Audio failed to start: ${error.message}" }
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
                synchronized(hudFeedLock) {
                    _hudFeedRevision.value += 1
                    clearHudFeedLocked()
                    _turnHistory.value = emptyList()
                    _collapseFraction.value = null
                    _hudDismissed.value = false
                }
            } catch (error: Exception) {
                _voiceMessage.value = "Could not clear history: ${error.message}"
            }
        }
    }

    private fun onChatEvent(event: ChatStreamEvent) {
        when (event) {
            is ChatStreamEvent.Delta -> {
                _activeToolName.value = null
                _assistantTranscript.value = assistantBuffer.accept(event.text, false)
            }
            is ChatStreamEvent.Done -> {
                _activeToolName.value = null
                if (event.text.isNotBlank()) {
                    _assistantTranscript.value = assistantBuffer.accept(event.text, true)
                    _turnHistory.update { history ->
                        (history + HudTurn(question = _userTranscript.value, answer = event.text))
                            .takeLast(MAX_TURN_HISTORY)
                    }
                }
            }

            is ChatStreamEvent.Tool -> {
                if (event.name == ToolLogEntry.WEB_SEARCH) {
                    _activeToolName.value = event.name.takeIf { event.status == "RUNNING" }
                } else {
                    appendToolLog(ToolLogEntry(event.name, event.status, event.durationMs))
                }
            }
            is ChatStreamEvent.Failed -> {
                _activeToolName.value = null
                _voiceMessage.value = event.message
            }
        }
    }

    private fun resolveStatus(phase: VoicePhase, ready: Boolean, activeToolName: String?): OverlayStatus = when {
        !ready -> OverlayStatus.Disconnected
        phase == VoicePhase.LISTENING -> OverlayStatus.Listening
        phase == VoicePhase.TRANSCRIBING -> OverlayStatus.Thinking
        phase == VoicePhase.ANSWERING && activeToolName == ToolLogEntry.WEB_SEARCH -> OverlayStatus.SearchingWeb
        phase == VoicePhase.ANSWERING -> OverlayStatus.Answering
        else -> OverlayStatus.Ready
    }

    // --- настройки и окна ---

    fun updateSettings(transform: (Settings) -> Settings) = settingsHolder.update(transform)

    fun updateProviderApiKey(provider: String, apiKey: String) {
        val updated = _providerApiKeys.value.withProvider(provider, apiKey.trim())
        _providerApiKeys.value = updated
        providerApiKeyStore.save(updated)
    }

    fun setHotkeyCaptureActive(active: Boolean) {
        _hotkeyCaptureActive.value = active
    }

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

    private fun providerApiKey(modelId: String?): String? {
        val models = _chatModels.value ?: return null
        val selectedId = modelId?.takeIf { id -> models.options.any { it.id == id } } ?: models.default
        val provider = models.options.firstOrNull { it.id == selectedId }?.provider ?: return null
        return _providerApiKeys.value.forProvider(provider).takeIf(String::isNotBlank)
    }

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
            .onFailure { error -> log.warn("Could not load model list: {}", error.toString()) }
            .getOrNull()
    }

    // --- привязка Bungie (§6 фазы) ---

    /** Кнопка «Привязать»: тот же `authorize`, только без голоса. */
    fun linkBungie() = launchAccount {
        val token = requireToken()
        val result = backend.callTool(token, AuthorizationLink.TOOL_NAME, backend.mapper.createObjectNode())
        appendToolLog(ToolLogEntry(AuthorizationLink.TOOL_NAME, result.status, 0))
        val url = AuthorizationLink.extract(ToolResult(result.status, result.output, result.message))
        if (url == null) {
            _accountMessage.value = result.message ?: "Backend did not return an authorization link"
        } else {
            showAuthorizationPrompt(url)
        }
    }

    /** Ссылка «отвязать» у статуса Bungie: после отвязки профиль перечитывается. */
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
            _accountMessage.value = "Session expired — sign in again"
            return
        }
        _session.value = stored
        refreshAccount()
    }

    private fun requireToken(): String =
        _session.value?.token ?: throw BackendException(401, "Sign in first")

    private fun parseExpiry(raw: String): Long = try {
        Instant.parse(raw).epochSecond
    } catch (error: Exception) {
        log.warn("Could not parse expiresAt '{}': {}. Assuming 12 hours from now.", raw, error.toString())
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
                _accountMessage.value = "Session is no longer valid — sign in again"
            } catch (error: BackendException) {
                _accountMessage.value = error.message
            } catch (error: Exception) {
                log.warn("Backend operation failed", error)
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

        /** Сколько прошлых ответов листается в HUD. Дальше — консоль и серверная история. */
        const val MAX_TURN_HISTORY = 10

        /** Период фонового обновления профиля: точность «х назад» в минутах, чаще незачем. */
        const val PROFILE_REFRESH_MS = 5 * 60_000L
    }
}
