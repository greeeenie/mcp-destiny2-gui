# План реализации mcp-destiny2-gui

Десктопный голосовой оверлей по Destiny 2: окно поверх игры, разговор с ассистентом
голосом, действия в инвентаре через инструменты MCP. Realtime-сессию с Inworld держит
сам оверлей; секреты (ключ Inworld, `x-api-key` MCP-профиля) остаются на сервере.

Система из трёх частей:

| Часть | Что делает | Состояние |
| --- | --- | --- |
| `mcp-destiny2` | MCP-сервер с инструментами по инвентарю | чужой, готов |
| `mcp-destiny2-client` | учётки, ключи, MCP-сессии, выдача доступа к Inworld | задеплоен, `https://duoxik.space/mcp-destiny2-client` |
| `mcp-destiny2-gui` | **этот проект**: сокет к Inworld, звук, движок диалога, UI | проектируется |

Разделение уже зафиксировано в `docs/plan.md` бэкенда и не пересматривается: аудио через
сервер не идёт, `function_call` обслуживает тот, кто держит сокет, а чтобы `x-api-key`
не уезжал на машину игрока — оверлей вызывает не `mcp-destiny2`, а `POST /tools/{name}`
нашего бэкенда своим Bearer-токеном.

Проверено 26.07.2026: `GET /actuator/health` бэкенда отдаёт `{"status":"UP"}`,
`GET /tools` без токена — 401. Сервер живой, контракт можно проверять сразу.

## 1. Что берём из донора

Донор — `C:\Users\greenie\IdeaProjects\mcp-assistant`: та же связка Inworld Realtime +
MCP-инструменты, работающая через колонку и Telegram. Пакет `com.example.mcpassistant`.

| Файл-донор | Объём | Что делаем |
| --- | --- | --- |
| `inworld/InworldProtocol.kt` | 237 стр | Переносим разбор событий (`decode`, `InworldServerEvent`) и кадры (`appendAudio`, `cancelResponse`, `createResponse`, `functionCallOutput`, `parseToolArguments`) как есть. Выбрасываем `sessionUpdate(...)` — кадр приходит с сервера готовым. Дописываем `inputAudioBufferCommit()` и `inputAudioBufferClear()`. |
| `inworld/RealtimeTransport.kt` | 19 стр | Как есть. |
| `inworld/JdkRealtimeTransport.kt` | 105 стр | Как есть (`java.net.http.WebSocket`, сборка фрагментированных текстовых кадров, канал на 64 сообщения). |
| `inworld/ReconnectPolicy.kt` | 71 стр | Переносим `ReconnectPolicy`, `InworldFailureClassifier`, `InworldConnectionException`, `RealtimeTransportClosedException`. Источник настроек меняем с `InworldProperties` на блок `client` из `POST /voice/session`. |
| `inworld/InworldConversationEngine.kt` | 817 стр | **Режем** — см. §1.1. |
| `conversation/ConversationEngine.kt`, `ConversationState.kt`, `ConversationEvent.kt`, `ConversationCoordinator.kt` | 15/30/35/32 стр | Как есть. |
| `tools/ToolBudgetPolicy.kt` | 54 стр | Как есть: бюджет вызовов на ход, `Admit`/`Reject` с текстом для модели. |
| `tools/ToolDefinition.kt`, `ToolRequest.kt`, `ToolResult.kt`, `ToolExecutor.kt` | ~90 стр | **Пишем заново, короче**: сервер уже отдаёт `{status, output, message}` строками, локальный диспетчер, таймауты, ретраи и взаимное исключение живут на сервере. Оверлею нужен только `ToolResult(status: String, output: JsonNode?, message: String?)`. |
| `tools/ToolDispatcher.kt` | 259 стр | **Не переносим**: он уже стоит на сервере (`org.example.tools.ToolDispatcher`). |
| `audio/PcmAudioFormat.kt` | 44 стр | Как есть. |
| `audio/AudioInput.kt`, `audio/AudioOutput.kt` | 13/24 стр | Как есть, включая `PlaybackCompletion` и `currentPlaybackGeneration()`. |
| `audio/JavaSoundAudioInput.kt` | 113 стр | Переносим; добавляем выбор конкретного `Mixer` вместо `AudioSystem.getLine`. |
| `audio/JavaSoundAudioOutput.kt` | 175 стр | Переносим целиком — механика `playbackGeneration` + барьер `awaitPlaybackComplete()` — это ядро корректного прерывания. Добавляем выбор `Mixer`. |
| `activation/ControlledAudioInput.kt` | 40 стр | Переносим под именем `GatedAudioInput`: гейт с `DROP_OLDEST` и очисткой очереди при открытии/закрытии. Это же — защита от эха. |
| `activation/ActivationController.kt` | 259 стр | **Режем**: машина состояний `Idle/Connecting/Listening/Speaking/FollowUp/Failed` и вся логика маршрутизации звука сохраняется, `WakeWordDetector` заменяется на горячую клавишу. |
| `activation/ActivationState.kt`, `ActivationSignal.kt` | 10/5 стр | Как есть. |
| `activation/JavaSoundActivationSignal.kt` | 40 стр | Как есть: синтезированные тоны 880/440 Гц на открытие и закрытие микрофона. |
| `activation/MicrophoneHub.kt` | 38 стр | Как есть. |
| `activation/VoskWakeWordDetector.kt`, `WakeWordDetector.kt` | 145 стр | **Не переносим**: wake word не нужен (см. §5), а модель Vosk требует 16 кГц против наших 24 кГц. |
| `mcp/*`, `agent/*`, `agentmemory/*`, `skills/*`, `capability/*`, `setup/*`, `panel/*`, `telegram/*`, `gateway/*`, `memory/*`, `monitoring/*`, `voice/VoiceSettings.kt` | ~1 МБ | Не переносим. |

Итого из донора приходит ~1000 строк проверенного кода, ещё ~600 пишется заново
(Compose UI, backend-клиент, хранение сессии, горячая клавиша).

### 1.1 Что выкидываем из `InworldConversationEngine`

Выбрасываем:

- `agentBindings` / `AgentTurnGate` / `AgentTurnContext` / `currentTurnContext()` /
  `assembledInstructions()` / `recordDispatchOutcome()` / `settleSkillTurn()` и поля
  `evidenceLock`, `observedToolOutputs`, `lastUserUtterance`, `voiceSessionId`, `householdId`;
- память: `historyProvider`, `injectHistory()`;
- обучение и метрики: `AssistantMetrics`, поля `*Nanos`;
- `VoiceSettingsProvider`, `applyRuntimeSettings()`, `settingsJob` — настройки задаются
  сервером в `sessionUpdate` и меняются только между сессиями;
- текстовый режим Telegram: `submitText`, `submitAudio`, `submitExplicitTurn`,
  `explicitTurnResult`, `explicitTurnText`, `textOnlyOutput`, `EXPLICIT_TURN_TIMEOUT_MS`
  и связанную ветку в `completeResponse()`;
- `properties.validateForSession()` — валидация осталась на сервере.

Оставляем без изменений (это и есть ценность донора):

- `connectionLoop()` со счётчиком поколений: `generationCounter`/`activeGeneration`
  отсекают события и результаты инструментов, пришедшие от старого соединения;
- жизненный цикл ответа: `activeResponseId`, `interruptedResponseId`, `cancelSent`,
  `providerResponseDone`, `responseSettlement`, `activePlaybackGeneration`;
- `requestInterruption()`: `audioOutput.flush()` + `response.cancel` под общим мьютексом,
  ровно один раз на ответ;
- `completeResponse()`: ждать `awaitPlaybackComplete()` и не считать ход завершённым,
  пока колонка не доиграла и пока ответ ждёт результата инструмента (`toolResponseIds`);
- обслуживание `function_call`: `toolAdmissionMutex`, `seenToolCallIds` (дедупликация),
  `toolTurnGeneration`, ленивый запуск job'а, проверка актуальности перед отправкой
  `function_call_output`, `ToolBudgetPolicy`;
- `startAudio()`/`stopAudio()`/`failAudio()`.

Меняем:

- заголовок соединения — `Authorization: Bearer <JWT>` вместо `Basic <apiKey>`;
- на `session.created` отправляем **строку `sessionUpdate`, полученную с сервера, как есть**,
  не разбирая её;
- перед каждой попыткой `connect()` движок дёргает `VoiceSessionProvider` за свежим
  доступом (§4);
- `toolDispatcher: ToolDispatcher?` заменяется на `toolExecutor: BackendToolExecutor`
  (HTTP-вызов `POST /tools/{name}`); список инструментов движку не нужен вовсе — он уже
  внутри `sessionUpdate`;
- в `connectionLoop` добавляется одна ветка: ошибка категории `AUTHENTICATION` один раз
  за сессию не считается фатальной — сначала перевыпускаем `/voice/session` и пробуем ещё
  раз, и только повторная — `Failed` (§4).

После правок движок — примерно 420 строк.

## 2. Архитектура

```
┌──────────────────────────── mcp-destiny2-gui (JVM, Windows) ────────────────────────┐
│                                                                                     │
│  ui/          Compose Desktop: HudWindow (always-on-top, non-focusable)             │
│               ConsoleWindow (обычное окно: логин, настройки, лог инструментов)      │
│                    ▲ StateFlow                                                      │
│  app/         AppState — единственный держатель UI-модели                           │
│                    ▲ ConversationEvent / ConversationState                          │
│  ptt/         PushToTalkController — машина состояний, гейт микрофона               │
│  input/       GlobalHotkey (JNA User32, GetAsyncKeyState)                           │
│                    ▲                                                                │
│  inworld/     InworldConversationEngine ── InworldProtocol ── JdkRealtimeTransport   │
│                    │                                          ReconnectPolicy       │
│  audio/       JavaSoundAudioInput → GatedAudioInput → (engine) → JavaSoundAudioOutput│
│                    │                                                                │
│  backend/     BackendClient (java.net.http) · SessionStore (DPAPI) ·                 │
│               VoiceSessionProvider · BackendToolExecutor                            │
└─────────────────────────────────────────────────────────────────────────────────────┘
        │  WSS, Authorization: Bearer <JWT 4 ч>          │  HTTPS, Authorization: Bearer <токен 12 ч>
        ▼                                                ▼
   Inworld Realtime                          https://duoxik.space/mcp-destiny2-client
   (звук + function_call одной сессией)      /auth/* /profile /tools /tools/{name} /voice/session
```

### 2.1 Где живёт состояние

| Состояние | Держатель | Живёт |
| --- | --- | --- |
| Сессия Realtime, ответы, вызовы инструментов | `InworldConversationEngine` (`StateFlow<ConversationState>`, `SharedFlow<ConversationEvent>`) | в памяти, на время сессии |
| Маршрутизация микрофона, follow-up окно | `PushToTalkController` (`StateFlow<ActivationState>`) | в памяти |
| Токен бэкенда, имя пользователя | `SessionStore` | `%LOCALAPPDATA%\mcp-destiny2-gui\session.bin`, DPAPI |
| Доступ к Inworld (`token`, `uri`, `audio`, `client`, `sessionUpdate`) | `VoiceSessionProvider` | **только в памяти**, на диск не пишется |
| Настройки (устройства, клавиша, прозрачность, положение окна, base URL) | `SettingsStore` | `%LOCALAPPDATA%\mcp-destiny2-gui\settings.json`, открытым текстом |
| Транскрипт, лог инструментов, статус, ссылка авторизации | `AppState` | в памяти, обнуляется при выходе |

Истории диалога нет — как и на сервере. Каждая активация начинается с чистого контекста.

Потоков ровно два семейства: AWT EDT (Compose) и `CoroutineScope(SupervisorJob() +
Dispatchers.Default)` приложения, внутри которого захват и воспроизведение звука уходят на
`Dispatchers.IO` (как в доноре). UI ничего не вызывает синхронно — только читает
`StateFlow` через `collectAsState()` и шлёт команды через `launch`.

Никакого DI-фреймворка: сборка графа руками в `Main.kt` (~60 строк). Spring в десктопном
приложении не нужен, а `@ConfigurationProperties`-стиль донора заменяется data-классами
из ответа `/voice/session`.

### 2.2 Схема потоков аудио

```
TargetDataLine (PCM16LE, 24000 Гц, mono)
   └─ JavaSoundAudioInput ──chunks: ByteArray(4800) каждые 100 мс──┐
                                                                   ▼
                                          GatedAudioInput.offer()  ── гейт закрыт ──► отброшено
                                                                   │  гейт открыт
                                                                   ▼
                        InworldProtocol.appendAudio() → base64 → WS "input_audio_buffer.append"
                                                                   │
   Inworld ── "response.output_audio.delta" ── base64 decode ──────┘
                                                                   ▼
        JavaSoundAudioOutput.play(bytes, generation)  ── generation устарел ──► отброшено
                                                                   │
                                                                   ▼
                                                    SourceDataLine (24000 Гц)
```

`chunkBytes = 24000 × 100 / 1000 × 2 = 4800` — считается сервером и приходит в
`audio.chunkBytes`; оверлей его не вычисляет, а сверяет с собственным расчётом и падает
на несовпадении.

Очереди намеренно короткие: вход 4 чанка (400 мс), выход 8 чанков. Переполнение входа в
доноре — ошибка (`Microphone queue overflowed`), в гейте — тихий сброс самого старого
чанка: для живой речи свежий звук важнее полного.

## 3. Контракты

### 3.1 Что оверлей берёт у бэкенда

База: `https://duoxik.space/mcp-destiny2-client`, заголовок `Authorization: Bearer <токен>`.

```
POST /auth/register {username, password} → 201 {userId}
      username ^[a-zA-Z0-9_-]{5,20}$ (становится именем MCP-профиля), пароль 8..128
      409 — имя занято; 400 — не прошло валидацию (текст в {error})
POST /auth/login    {username, password} → 200 {token, expiresAt}   401 — неверная пара
GET  /profile   → {name, bungieLinked, bungieProfile{bungieMembershipId, primaryMembershipId,
                   primaryMembershipType, uniqueName, displayName}}
GET  /tools     → [{name, description, executionMode, parameters}]
POST /tools/{name}  тело — JSON-аргументы → {status, output, message}
      status ∈ SUCCESS | TIMEOUT | BUSY | REJECTED | INVALID_ARGUMENTS | FAILED
POST /voice/session → см. 3.2                       502 — Inworld или MCP недоступны
GET  /actuator/health
```

Ошибки бэкенда — всегда `{"error": "..."}` (`ApiExceptionHandler`), детали внешних систем
наружу не отдаются. `POST /auth/ws-ticket` существует, но помечен в плане бэкенда к
удалению — не используем.

Инструменты `mcp-destiny2` на сегодня (`org.example.tool.Tools`): `authorize`,
`searchProfiles`, `getUserCredentialTypes`, `getUserReports`, `searchWeapons`,
`transferWeapon`. Список оверлей не зашивает — берёт из `GET /tools` для UI, а модели он
приходит внутри `sessionUpdate`.

### 3.2 `POST /voice/session`

```json
{
  "token": "<JWT>", "tokenType": "Bearer", "expiresAt": "2026-07-26T14:50:30Z",
  "uri": "wss://api.inworld.ai/api/v1/realtime/session",
  "audio":  {"inputSampleRate":24000,"outputSampleRate":24000,"chunkMs":100,"chunkBytes":4800},
  "client": {"connectTimeoutMs":10000,"interruptTimeoutMs":2000,"reconnectAttempts":3,
             "reconnectBaseDelayMs":250,"reconnectMaxDelayMs":4000,
             "maxToolCallsPerTurn":6,"toolBudgets":{}},
  "sessionUpdate": { "type":"session.update", "session": { ...промпт, модель, голос, инструменты... } }
}
```

`sessionUpdate` оверлей **не разбирает и не изменяет** — хранит как строку и отправляет в
сокет первым кадром после `session.created`. Это позволяет менять промпт, голос, модель,
VAD и набор инструментов на сервере без выпуска новой версии оверлея.

`client` целиком уходит в `ReconnectPolicy` и `ToolBudgetPolicy` — своих значений по
умолчанию оверлей не заводит (то же правило, что на сервере).

### 3.3 Кадры Inworld, которые шлёт оверлей

| Кадр | Когда | Откуда |
| --- | --- | --- |
| `session.update` | на `session.created`, строкой с сервера | `/voice/session` |
| `input_audio_buffer.append` | каждые 100 мс при открытом гейте | `InworldProtocol.appendAudio` |
| `input_audio_buffer.commit` | по отпусканию клавиши, если хвоста тишины не хватило (§5.2) | дописываем |
| `input_audio_buffer.clear` | при прерывании — чтобы модель не начала ход по остаткам буфера | дописываем |
| `response.cancel` | прерывание | `InworldProtocol.cancelResponse` |
| `conversation.item.create` с `function_call_output` | после ответа `/tools/{name}` | `InworldProtocol.functionCallOutput` |

Входящие события разбираются донорским `decode()` — он уже покрывает оба варианта имён
(`response.audio.delta` и `response.output_audio.delta` и т. д.), это дёшево оставить.

Результат инструмента упаковывается ровно как в доноре: `{"status":..., "message":...,
"output":...}` сериализуется в строку и кладётся в поле `output` элемента
`function_call_output`. Статусы бэкенда совпадают с донорским `ToolResultStatus`
один в один — конвертации не нужно.

## 4. Хранение сессии и токены

| Токен | TTL | Где | Как обновляется |
| --- | --- | --- | --- |
| Сессионный токен бэкенда | 12 ч | `session.bin`, DPAPI (`CryptProtectData`, scope пользователя, через JNA `Crypt32`) | Refresh-ручки нет. При 401 на любом запросе — экран логина; за 30 мин до `expiresAt` — ненавязчивый баннер «сессия истекает» |
| JWT Inworld | 4 ч (проверено на живом Inworld 26.07.2026) | только в памяти `VoiceSessionProvider` | `POST /voice/session` |
| Пароль | — | не хранится по умолчанию; по галочке «запомнить» — в том же DPAPI-файле | — |

Правило, снимающее проблему «токен истёк посреди разговора»: **срок проверяется перед
каждым `connect()`, а не во время сессии.**

`VoiceSessionProvider.get()`:
1. если кэш есть и `expiresAt - now > 10 мин` — отдать кэш;
2. иначе `POST /voice/session`, положить в кэш, отдать;
3. параллельно, если сокет открыт и до истечения осталось < 15 мин — тихо обновить кэш в
   фоне, чтобы возможный реконнект не ждал HTTP-запроса.

Разговор длиннее 4 часов физически не бывает: в режиме push-to-talk сокет живёт секунды —
минуты, в hands-free закрывается по таймауту тишины (3.5 с follow-up из донора). Поэтому
основной сценарий — свежий токен на каждое подключение.

Остаточный случай — сокет живёт дольше срока JWT и Inworld его рвёт. Донорский
`InworldFailureClassifier` помечает `AUTHENTICATION` как `retryable = false`, то есть
`ReconnectPolicy` такую ошибку не ретраит и движок уходит в `Failed`. Правка: в
`connectionLoop` заводится флаг `authRetryUsed`; при первой авторизационной ошибке за
сессию провайдер сбрасывает кэш, движок берёт новый токен и делает одну внеочередную
попытку соединения; вторая такая ошибка — `Failed` с внятным текстом. Ровно один
дополнительный if в цикле, без изменения политики реконнекта.

Тихая ротация: если сессия открыта, движок в `Idle`/`FollowUp` (никто не говорит) и до
истечения < 5 мин — сокет закрывается и открывается заново. Между ходами это незаметно.

## 5. UX голоса и окно

### 5.1 Рекомендация: push-to-talk по глобальной горячей клавише

Постоянное прослушивание с `semantic_vad` для Destiny 2 не подходит:

- игрок в наушниках слышит игру, но микрофон ловит **его собственный игровой войс-чат** и
  разговоры с фаертимом — VAD откроет ход на «слева ведьма» и модель полезет в инвентарь;
- если игрок на колонках, микрофон ловит звук игры и ответ ассистента (§6);
- открытая сессия к Inworld — это счёт: держать её всю катку дорого без пользы;
- в бою игрок не хочет случайно потратить ход на «эээ, ща».

Поэтому: **удержание клавиши — говорю, отпустил — ассистент отвечает.** Настройки
`semantic_vad`, которые приходят с сервера, при этом никуда не деваются и продолжают
определять конец фразы внутри удерживаемого окна — оверлей не переопределяет
`turn_detection`, он лишь решает, когда вообще подавать звук.

Второй режим — hands-free (сессия открыта, гейт управляется только состоянием ответа) —
делаем переключателем в настройках, по умолчанию выключенным. Он полезен на пустой
локации и для отладки.

Клавиша: по умолчанию `Right Alt` (`VK_RMENU`, `0xA5`) — в Destiny 2 не занята;
настраивается. Отдельная клавиша «прервать» не нужна: короткое нажатие той же клавиши,
пока ассистент говорит, означает прерывание.

Реализация ввода — **опрос `GetAsyncKeyState` через JNA (`User32`) каждые 16 мс на
отдельном потоке**. Обоснование:

- `RegisterHotKey` отдаёт только `WM_HOTKEY` на нажатие, без отпускания — удержание им
  не сделать;
- `SetWindowsHookExW(WH_KEYBOARD_LL)` даёт удержание, но это глобальный хук клавиатуры —
  максимально «шумный» профиль рядом с античитом, ставим его только если опрос не сработает;
- `GetAsyncKeyState` — чтение состояния клавиатуры, без хуков, без инъекций, без отправки
  ввода в игру. Ниже профиля не бывает.

JNativeHook не берём: лицензия GPL/LGPL и тот же низкоуровневый хук внутри; JNA (Apache 2.0)
хватает.

### 5.2 Закрытие хода при отпускании клавиши

По отпусканию оверлей дописывает в поток ~400 мс тишины (4 нулевых чанка) и закрывает
гейт: `semantic_vad` видит паузу и сам закрывает ход с `create_response = true`. Это не
трогает серверный `sessionUpdate` — важное свойство, потому что менять `turn_detection`
оверлей права не имеет.

Запасной путь, если на живом стенде тишины не хватит: `input_audio_buffer.commit`.
Он в протоколе Inworld есть (протокол совместим с OpenAI Realtime), но при включённом
`turn_detection` может конфликтовать с автоматическим `response.create`. Проверяется в
фазе 5, решение фиксируется там же.

### 5.3 Два окна

| Окно | Свойства | Содержимое |
| --- | --- | --- |
| **HUD** | `alwaysOnTop = true`, `undecorated = true`, `transparent = true`, `window.isFocusableWindowState = false`, ~360×180, угол экрана, прозрачность 70–85 % (настраивается) | статус, транскрипт, ответ, вызванные инструменты, плашка авторизации |
| **Консоль** | обычное фокусируемое окно, вызывается горячей клавишей или из трея | логин/регистрация, список `GET /tools`, настройки, полный лог сессии |

`isFocusableWindowState = false` — ключевое: без него клик по оверлею отбирает фокус у
игры. Мышиные события в нефокусируемое окно AWT доставляет по-прежнему, так что кнопки
работают, а клавиатурный ввод в HUD просто не нужен (для него есть консоль).

Игра запускается в режиме «оконный без рамки»: в exclusive fullscreen оверлей не
показывается — это ограничение ОС, а не наше.

Что видит игрок в HUD:

- **Статус**: точка + слово — `Не подключён` / `Слушаю` (клавиша зажата) / `Думаю` /
  `Говорю` / `Переподключаюсь (2/3)` / `Ошибка`.
- **Индикатор уровня микрофона** — узкая полоска, чтобы «микрофон не тот» было видно сразу.
- **Транскрипт игрока**: частичные дельты бледнее, финальный — обычным. Живёт до конца хода.
- **Ответ ассистента текстом** — по `response.output_audio_transcript.delta`; можно
  выключить в настройках (в бою текст отвлекает, ответ и так слышно).
- **Строка инструмента**: `searchWeapons → SUCCESS (1.2 с)`, ошибки красным. Не более трёх
  последних.
- **Плашка авторизации Bungie**: текст «Открой ссылку и вернись» + кнопка «Открыть в
  браузере» + сама ссылка мелким шрифтом (на случай, если браузер не открылся).

Звуковые подтверждения — донорский `JavaSoundActivationSignal`: 880 Гц на открытие
микрофона, 440 Гц на закрытие. В шлеме это надёжнее визуального индикатора.

## 6. Эхо

Как решено в доноре (`docs/superpowers/specs/2026-06-22-wake-word-activation-design.md`):
**акустического эхоподавления нет намеренно.** Вместо него — жёсткий гейт микрофона:

1. `ControlledAudioInput` — единственный путь звука в облако. Физический микрофон
   принадлежит `MicrophoneHub`, который отдаёт чанки контроллеру, а тот решает, звать ли
   `offer()`.
2. `ActivationController` закрывает гейт (`closeGate()`) при `ResponseStarted` и при первом
   `AudioChunk`, то есть до того, как колонка успевает что-то издать, и переходит в
   `Speaking`. Открывает — на `ResponseCompleted` (follow-up окно) и после успешного
   прерывания.
3. `closeGate()`/`openGate()` не просто ставят флаг, а **вычищают очередь**: звук,
   захваченный в другом состоянии маршрутизации, никогда не проигрывается заново.
4. Прерывание не «догрёбывает» уже отправленный звук: `JavaSoundAudioOutput.flush()`
   увеличивает `playbackGeneration`, и чанки со старым поколением отбрасываются и в очереди,
   и прямо посреди записи в линию.
5. Барж-ин во время `Speaking` в доноре возможен только по wake word — то есть по сигналу,
   который распознаётся локально и который собственная речь ассистента не порождает.

Что делаем мы:

- **Push-to-talk**: гейт открыт только пока клавиша зажата **и** движок не в `Speaking`.
  Зацикливание структурно невозможно — микрофон и колонка никогда не активны одновременно.
- **Прерывание**: нажатие клавиши в `Speaking` → `engine.interrupt()` → `flush()` +
  `response.cancel` + `input_audio_buffer.clear` → гейт открывается. Клавиша занимает то
  место, которое в доноре занимал wake word: локальный сигнал, который эхо не подделает.
- **Hands-free**: та же схема, что в доноре, но follow-up окно (3.5 с) открывается только
  после `ResponseCompleted`, а `SpeechStarted` при закрытом гейте прийти не может — значит
  донорская ветка «`SpeechStarted` → `requestInterruption()`» не приведёт к тому, что
  ассистент прервёт сам себя своим же эхом.
- В настройках — предупреждение «на колонках hands-free работает хуже; включите наушники
  или подавление эха в свойствах устройства Windows». Своего AEC не пишем: это отдельный
  проект, а гейт закрывает 100 % случаев в основном режиме.

## 7. Стек и структура проекта

**Сборка — Gradle (Kotlin DSL), не Maven.** В остальных репозиториях Maven, но
Compose Multiplatform официально поддерживает только Gradle: плагины
`org.jetbrains.compose` и `org.jetbrains.kotlin.plugin.compose`, а также упаковка через
`jpackage` живут там. Тянуть неофициальную Maven-обвязку ради единообразия — плохой обмен.

**JSON — Jackson 3 (`tools.jackson`), как в `mcp-destiny2-client`.** Донор на Jackson 2
(`com.fasterxml.jackson`), но бэкенд уже прошёл этот перенос и записал точный список
отличий (`docs/plan.md`, риск 1): `ObjectMapper()`, `createObjectNode`, `readTree`,
`writeValueAsString`, `valueToTree`, `path`, `put/putObject/putArray` сохранены; `set(...)`
у `ObjectNode` перестал быть генериком (убрать `set<JsonNode>`), исключения стали
unchecked `JacksonException`, `asText()`/`isTextual()` помечены `@Deprecated` в пользу
`asString()`/`isString()`. Это ~15 правок в `InworldProtocol.kt`. Взамен — один диалект JSON
на два репозитория: человек, правивший `ToolsController`, читает `BackendToolExecutor` без
переключения. Второй JSON-стек в проекте не заводим.

**HTTP — `java.net.http.HttpClient` из JDK.** Тот же клиент, на котором построен донорский
`JdkRealtimeTransport`: один экземпляр обслуживает и REST, и WebSocket, лишней зависимости нет.

| Компонент | Выбор | Версия |
| --- | --- | --- |
| Kotlin | JVM | 2.2+ (совместимость с плагином Compose проверить при настройке) |
| JDK | 21 LTS | рантайм всё равно упаковывается `jpackage`, версия бэкенда (25) не важна |
| UI | Compose Multiplatform Desktop | 1.11.x (на июль 2026 последняя — 1.11.1) |
| Корутины | `kotlinx-coroutines-core` | как в доноре |
| JSON | `tools.jackson:jackson-databind` + `jackson-module-kotlin` | 3.x |
| Win32 | `net.java.dev.jna:jna` + `jna-platform` | Apache 2.0 |
| Логи | `slf4j-api` + `logback-classic`, файл в `%LOCALAPPDATA%` | — |
| Тесты | `kotlin-test-junit5`, `kotlinx-coroutines-test` | как в доноре |

Vosk, Spring, ktor, MCP SDK — не нужны.

```
mcp-destiny2-gui/
├── build.gradle.kts, settings.gradle.kts, gradle/libs.versions.toml
├── docs/plan.md
└── src/
    ├── main/kotlin/org/example/overlay/
    │   ├── Main.kt                    сборка графа, запуск окон
    │   ├── app/AppState.kt            UI-модель, подписка на события движка
    │   ├── app/SettingsStore.kt
    │   ├── backend/BackendClient.kt   /auth /profile /tools /voice/session
    │   ├── backend/Dtos.kt
    │   ├── backend/SessionStore.kt    токен 12 ч под DPAPI
    │   ├── backend/VoiceSessionProvider.kt
    │   ├── backend/BackendToolExecutor.kt   function_call → POST /tools/{name}
    │   ├── inworld/InworldProtocol.kt       ← донор (без sessionUpdate)
    │   ├── inworld/RealtimeTransport.kt     ← донор
    │   ├── inworld/JdkRealtimeTransport.kt  ← донор
    │   ├── inworld/ReconnectPolicy.kt       ← донор
    │   ├── inworld/InworldConversationEngine.kt  ← донор, урезан
    │   ├── conversation/{ConversationEngine,State,Event,Coordinator}.kt ← донор
    │   ├── tools/ToolBudgetPolicy.kt        ← донор
    │   ├── tools/ToolResult.kt              новый, тонкий
    │   ├── audio/{PcmAudioFormat,AudioInput,AudioOutput}.kt          ← донор
    │   ├── audio/{JavaSoundAudioInput,JavaSoundAudioOutput}.kt       ← донор + выбор Mixer
    │   ├── audio/GatedAudioInput.kt         ← ControlledAudioInput донора
    │   ├── audio/AudioDevices.kt            перечисление устройств
    │   ├── ptt/PushToTalkController.kt      ← ActivationController донора, урезан
    │   ├── ptt/ActivationSignal.kt          ← донор
    │   ├── input/GlobalHotkey.kt            JNA User32
    │   ├── platform/Dpapi.kt                JNA Crypt32
    │   ├── platform/BrowserLauncher.kt      Desktop.browse
    │   └── ui/{HudWindow,ConsoleWindow,LoginScreen,ToolLogView,SettingsScreen}.kt
    └── test/kotlin/...
```

## 8. Фазы

Каждая фаза заканчивается проверкой, которую можно выполнить руками; фазы 3+ требуют
живого Inworld, то есть тратят деньги — держим их короткими.

### Фаза 0. Каркас и окно
- Gradle-проект, Compose Desktop, `jpackage`-конфигурация (пока не собираем инсталлятор).
- `HudWindow`: `alwaysOnTop`, `undecorated`, `transparent`, `isFocusableWindowState = false`,
  перетаскивание за фон, сохранение позиции и прозрачности в `settings.json`.
- `ConsoleWindow` — пустая заготовка, вызывается из трея.
- **Проверка**: Destiny 2 запущена в «оконном без рамки», HUD виден поверх игры,
  клик по HUD не отбирает фокус (персонаж продолжает двигаться по WASD), Alt+Tab не ломает
  оверлей, положение восстанавливается после перезапуска.

### Фаза 1. Backend-клиент, логин, список инструментов
- `BackendClient` на `java.net.http`: register / login / profile / tools / tools/{name},
  разбор `{error}`, таймауты, 401 → событие «нужен логин».
- `SessionStore` на DPAPI через JNA `Crypt32`; галочка «запомнить пароль».
- `LoginScreen` и экран профиля в консоли: имя, `bungieLinked`, список инструментов
  с описаниями и `executionMode`.
- **Проверка**: регистрация нового пользователя против живого
  `https://duoxik.space/mcp-destiny2-client`, повторная регистрация → 409 с внятным текстом,
  логин, перезапуск приложения без повторного ввода пароля, `GET /tools` показывает шесть
  инструментов `mcp-destiny2`, ручной вызов `searchWeapons` из консоли возвращает результат.

### Фаза 2. Аудио-слой
- Перенос `PcmAudioFormat`, `AudioInput`/`AudioOutput`, `JavaSoundAudioInput`/`Output`,
  `GatedAudioInput`, `MicrophoneHub`, `JavaSoundActivationSignal`.
- Выбор устройств: перечисление `AudioSystem.getMixerInfo()`, пункт «системное по
  умолчанию», сохранение выбора.
- Индикатор уровня входа (RMS по чанку) в HUD.
- **Проверка**: локальный «эхо-тест» — записать 5 секунд с микрофона на 24 кГц PCM16LE и
  сразу проиграть; звук чистый, без щелчков и без ускорения/замедления. Отдельно
  проверить, что линия на **ровно 24000 Гц** открывается на реальной звуковой карте
  (см. риск 6). Смена устройства в настройках переоткрывает линии без перезапуска.

### Фаза 3. Realtime-сессия без инструментов
- Перенос `InworldProtocol` (без `sessionUpdate`), транспорта, `ReconnectPolicy`,
  `conversation/*`; урезанный движок.
- `VoiceSessionProvider`, `Bearer`-заголовок, отправка серверного `sessionUpdate` строкой.
- Статус и транскрипт в HUD.
- **Проверка**: нажал клавишу, сказал «расскажи, что ты умеешь», отпустил — услышал ответ
  голосом, в HUD видно оба транскрипта. Замерить задержку от отпускания клавиши до первого
  `output_audio.delta`. Отдельно: выдернуть Wi-Fi посреди ответа — статус
  «Переподключаюсь (1/3)», после возврата сети сессия поднимается.

### Фаза 4. Инструменты
- `BackendToolExecutor`: `function_call` → `POST /tools/{name}` → `function_call_output`.
- `ToolBudgetPolicy` с `maxToolCallsPerTurn`/`toolBudgets` из `client`.
- Лог инструментов в HUD и полный — в консоли.
- **Проверка**: «покажи мои пушки, которые называются Falling Guillotine» — в логе виден
  вызов `searchWeapons`, ассистент называет найденное. «Перекинь её на титана» — виден
  `transferWeapon`, предмет реально переехал (проверяется в игре). Отдельно: искусственно
  уронить бэкенд (неверный base URL) — инструмент возвращает ошибку, ассистент это
  проговаривает, сессия не рвётся.

### Фаза 5. Push-to-talk, прерывание, эхо
- `GlobalHotkey` на JNA, `PushToTalkController` из донорского `ActivationController`.
- Хвост тишины по отпусканию; при неудаче — `input_audio_buffer.commit` (решение
  фиксируется здесь же, в этом файле).
- Прерывание по клавише: `flush` → `response.cancel` → `input_audio_buffer.clear`.
- Тоны открытия/закрытия микрофона.
- **Проверка**: клавиша работает, пока фокус в игре; отпускание закрывает ход; нажатие во
  время ответа обрывает звук за ≤ 300 мс и открывает микрофон; произнесённое во время
  ответа **без** нажатия клавиши ассистента не прерывает и в облако не уходит (смотрим по
  логу — кадров `append` нет); на колонках с включённым ответом ассистент не начинает
  отвечать сам себе.

### Фаза 6. Привязка Bungie
- Баннер «аккаунт не привязан» по `GET /profile`, кнопка «Привязать» — прямой вызов
  `POST /tools/authorize` без голоса.
- Голосовой путь: перехват `function_call name=authorize`; после ответа бэкенда достать
  URL из `output` (там **строка**, а не объект — см. риск 5), открыть браузер через
  `Desktop.browse`, показать плашку.
- Опрос `GET /profile` каждые 3 с до 5 минут, потом кнопка «Проверить ещё раз».
- **Проверка**: сценарий целиком на чистом пользователе — регистрация → «покажи мои
  пушки» → ассистент вызывает `authorize` и просит открыть ссылку → браузер открылся →
  вход в Bungie → плашка сама сменилась на «привязано» → повторный запрос отрабатывает.

### Фаза 7. Устойчивость
- Ветка `authRetryUsed` в `connectionLoop` и тихая ротация сокета (§4).
- Обработка `AudioDeviceException`: одно авто-переоткрытие линии, затем баннер с кнопкой.
- Поведение при 401 бэкенда посреди сессии: закрыть сокет, показать логин, сохранить
  транскрипт.
- Логи в файл с ротацией; PCM и тексты реплик в лог не пишем (правило донора).
- **Проверка**: выдернуть USB-гарнитуру во время ответа — понятная ошибка, после возврата
  устройства сессия поднимается кнопкой; вручную протухший токен бэкенда → экран логина
  без падения; сутки простоя приложения не приводят к утечке потоков (`jcmd Thread.print`).

### Фаза 8. Упаковка
- `jpackage` через Compose Gradle plugin: `.msi`, иконка, автозапуск опционально, трей.
- Экран «первый запуск»: base URL, устройства, клавиша.
- README: установка, требования (оконный без рамки), что делать при отсутствии звука.
- **Проверка**: установка `.msi` на чистом профиле Windows 11, запуск без установленной JDK,
  полный голосовой цикл на свежей машине.

## 9. Риски и решения

1. **Античит BattlEye.** Destiny 2 защищена BattlEye; вмешательство в процесс игры —
   риск бана. Решение: оверлей — обычное окно ОС; никаких DirectX/OpenGL-хуков, инъекций
   DLL, чтения памяти игры и синтеза ввода в игру. Единственное касание системного ввода —
   чтение состояния клавиши через `GetAsyncKeyState` (read-only) и, если понадобится,
   `RegisterHotKey`. Низкоуровневый хук клавиатуры (`WH_KEYBOARD_LL`) — только как
   запасной вариант и с явным решением владельца. Побочный эффект принятого ограничения:
   в exclusive fullscreen оверлея не видно, игра обязана идти в «оконном без рамки».

2. **Задержка.** Складывается из чанка (100 мс), сети до Inworld, обработки моделью и
   времени вызова инструмента через наш бэкенд (MCP-сессия там поднимается на каждый
   запрос — см. §4 плана бэкенда). Наша часть бюджета: очередь входа 4 чанка,
   очередь выхода 8, никакой дополнительной буферизации, PTT не ждёт VAD на старте фразы.
   Измеряем в фазах 3 и 4; если вызов инструмента окажется медленным, лечится на сервере
   (реестр долгоживущих MCP-сессий — там это уже записано как отложенный пункт).

3. **Устройства ввода/вывода и смена на ходу.** JavaSound на Windows фиксирует микшер в
   момент `open()` и не следует за сменой «устройства по умолчанию»; список `Mixer.Info`
   кэшируется при старте JVM, новые устройства в нём не появляются. Ловить смену на лету
   Java не позволяет. Решение: явный выбор устройства в настройках + пункт «системное по
   умолчанию»; кнопка «Обновить список»; при `LineUnavailableException` или обрыве чтения —
   одно автоматическое переоткрытие, затем баннер с кнопкой. Донорский код уже типизирует
   эти сбои (`AudioDeviceException` → категория `AUDIO_DEVICE`, `retryable = false`), так
   что состояние движка не портится.

4. **Потеря сети посреди фразы.** `ReconnectPolicy`: 3 попытки, 250 мс → 4 с с джиттером
   0.75–1.25. При реконнекте `audioOutput.flush()`, гейт закрыт, `generationCounter`
   растёт — события и результаты инструментов от старого соединения отбрасываются
   (`generation == activeGeneration`), `function_call_output` в мёртвый транспорт не летит.
   Прерванный ход не восстанавливается: истории нет, контекст на той стороне потерян.
   Игроку это говорится прямо — статус «Соединение потеряно, повтори фразу».
   Отдельный случай — обрыв **после** того, как инструмент уже отработал: `transferWeapon`
   к тому моменту уже переложил предмет, а модель об этом не узнает. Лечения нет, но лог
   инструментов в HUD показывает, что действие прошло.

5. **Формат ответа `authorize`.** Инструмент возвращает голую строку с URL. В
   `McpToolMapper.contentOutput` бэкенда одиночный `TextContent` сначала пробуют разобрать
   как JSON, и URL им не является — значит в `output` приходит **JSON-строка**, а не
   объект. Оверлей обязан это учитывать: если `output.isString()`, искать URL регэкспом в
   тексте, а не лезть в несуществующее поле.

6. **24 кГц на реальной звуковой карте.** Не всякий драйвер отдаёт `TargetDataLine` ровно
   на 24000 Гц (типовые — 44100/48000). Донор жил на 16 кГц и с этим не сталкивался.
   Проверяем в фазе 2. Если линии на 24000 нет — открываем 48000 и децимируем ровно в 2
   раза (целочисленный коэффициент, простое усреднение пар отсчётов), на выходе —
   дублирование. Это единственное место, где может понадобиться собственный ресемплер;
   менять частоту на сервере не нужно, `audio.inputSampleRate` в `/voice/session` описывает
   формат **кадра для Inworld**, а не формат звуковой карты.

7. **Истечение токенов.** Разобрано в §4. Остаточный риск — неизвестно, рвёт ли Inworld
   уже открытый сокет при истечении JWT. Ветка `authRetryUsed` + тихая ротация закрывают
   оба поведения, а живой ответ появится в фазе 7.

8. **Новый игрок без привязки Bungie.** До привязки любой инструмент, кроме `authorize`,
   вернёт ошибку. Решение: оверлей проверяет `bungieLinked` при логине и **до** первой
   голосовой фразы показывает баннер с кнопкой; голосовой путь через `authorize` тоже
   поддержан. Системный промпт на сервере уже содержит инструкцию звать `authorize`.
   Дополнительно: `McpToolMapper` бэкенда распознаёт истёкшую авторизацию и возвращает
   «link the Bungie account again» — этот текст ловим и показываем ту же плашку.

9. **`executionMode` врёт про `transferWeapon`.** В `mcp-destiny2` у `transferWeapon`
   проставлено `readOnlyHint = true`, хотя инструмент меняет инвентарь; бэкенд честно
   отражает подсказку и размечает его `READ_ONLY`. На оверлей это влияет только визуально.
   Решение: не строить предупреждения UI на `executionMode`, вести собственный короткий
   список изменяющих инструментов; сообщить владельцу `mcp-destiny2` — правка там
   однострочная.

10. **Токен бэкенда лежит на машине игрока.** Принято ещё на сервере (риск 6 его плана):
    оверлею доверять нельзя, он и так может вызвать любой инструмент в обход промпта, но
    только в пределах своего профиля Bungie. Наша часть — не хранить токен открытым
    текстом: DPAPI на область текущего пользователя. JWT Inworld на диск не попадает вовсе.

11. **Прозрачное always-on-top окно и разномастные конфигурации.** DPI-масштабирование,
    несколько мониторов, HDR, оконный режим на втором мониторе. Все известные проблемы
    Compose Desktop тут решаются одинаково: положение и размер хранить в логических
    единицах, при старте проверять, что окно попадает в границы существующего экрана,
    иначе — в угол основного. Проверка — фаза 0 на реальной машине.

12. **Стоимость hands-free.** Открытая Realtime-сессия тарифицируется. Донор закрывал
    сессию по истечении follow-up окна (3.5 с) именно поэтому. Переносим это правило
    буквально: тишина дольше окна — сокет закрывается.

## 10. Открытые вопросы

- **Query-параметры сокета.** Донор добавлял к URI `?key=voice-<uuid>&protocol=realtime`
  при авторизации `Basic`. Нужны ли они при `Bearer <JWT>` — неизвестно; проверить на живом
  стенде в фазе 3 и, если нет, брать `uri` из `/voice/session` как есть.
- **Закрытие хода при PTT.** Хватит ли 400 мс тишины, чтобы `semantic_vad` закрыл ход, или
  нужен `input_audio_buffer.commit`; не конфликтует ли ручной commit с
  `create_response = true`. Решается замером в фазе 5.
- **Живёт ли открытый сокет дольше срока JWT.** От ответа зависит, нужна ли тихая ротация
  вообще.
- **Нужен ли hands-free.** Он стоит денег и создаёт весь класс проблем с эхом. Возможно,
  push-to-talk достаточно и режим стоит выкинуть из объёма.
- **Раскладка клавиш по умолчанию.** `Right Alt` — предположение; нужен ответ владельца,
  какие клавиши у него в Destiny 2 свободны.
- **Показывать ли текст ответа в HUD во время боя.** Возможно, в бою нужен только
  статус-индикатор и звук, а текст — в консоли.
- **Нужен ли текстовый ввод для отладки.** В доноре был (`submitText` из Telegram-ветки),
  мы его вырезаем. Вернуть в консоль — примерно 30 строк, но это отдельная ветка в
  `completeResponse()`.
- **Автообновление оверлея.** Пока не в объёме; если нужно — это меняет схему упаковки в
  фазе 8.
- **Настройки голоса в UI.** Сейчас голос, скорость, модель и промпт жёстко заданы в
  `application.yaml` бэкенда. Если их нужно менять из оверлея, на сервере появляется ручка
  настроек — сегодня её нет и в объём это не входит.
