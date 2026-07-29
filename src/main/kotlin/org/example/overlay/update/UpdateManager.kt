package org.example.overlay.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Что показывать про обновление. [Hidden] — обновления нет или ещё не искали: в UI пусто. */
sealed interface UpdateState {
    data object Hidden : UpdateState
    data class Available(val update: UpdateInfo) : UpdateState
    data class Downloading(val update: UpdateInfo, val progress: Float) : UpdateState

    /** Установщик запущен: приложение сейчас закроется само. */
    data class Installing(val update: UpdateInfo) : UpdateState
    data class Failed(val update: UpdateInfo, val message: String) : UpdateState
}

/**
 * Автообновление. Источник правды — релизы GitHub: приложение периодически сравнивает свою
 * версию с последним тегом и по клику игрока скачивает MSI и запускает установщик. Своего
 * сервера обновлений нет сознательно: публикация релиза и есть публикация обновления.
 *
 * Установка только по клику, не тихая: msiexec всё равно упирается в UAC, а закрывать оверлей
 * без спроса посреди игры нельзя.
 */
class UpdateManager(
    private val scope: CoroutineScope,
    private val http: HttpClient,
    private val mapper: JsonMapper,
    private val currentVersion: String = AppVersion.current,
    private val releasesUrl: URI = URI.create(LATEST_RELEASE_URL),
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Hidden)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** true — установщик стартовал, `Main` закрывает приложение: MSI не заменит файлы живого процесса. */
    private val _installStarted = MutableStateFlow(false)
    val installStarted: StateFlow<Boolean> = _installStarted.asStateFlow()

    /** Проверка тихая: нет сети или GitHub недоступен — молча ждём следующего круга. */
    fun start() {
        scope.launch {
            // Старту приложения и так есть чем заняться: сессия, микрофон, окна.
            delay(FIRST_CHECK_DELAY)
            while (true) {
                checkNow()
                delay(CHECK_INTERVAL)
            }
        }
    }

    suspend fun checkNow() {
        // Идущее скачивание или установка перепроверкой не сбиваются.
        if (_state.value !is UpdateState.Hidden && _state.value !is UpdateState.Available) return
        val update = try {
            fetchLatest()
        } catch (error: Exception) {
            log.info("Проверка обновлений не удалась: {}", error.toString())
            return
        }
        if (update != null && Versions.isNewer(update.version, currentVersion)) {
            _state.value = UpdateState.Available(update)
        }
    }

    private suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(releasesUrl)
            .timeout(CHECK_TIMEOUT)
            .header("Accept", "application/vnd.github+json")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "GitHub ответил HTTP ${response.statusCode()}" }
        ReleaseFeed.parse(mapper.readTree(response.body()))
    }

    /** Скачать MSI и запустить установку. Дальше приложение закрывается — см. [installStarted]. */
    fun install() {
        val update = when (val current = _state.value) {
            is UpdateState.Available -> current.update
            is UpdateState.Failed -> current.update
            else -> return
        }
        scope.launch {
            _state.value = UpdateState.Downloading(update, 0f)
            try {
                val msi = download(update)
                _state.value = UpdateState.Installing(update)
                launchInstaller(msi)
                _installStarted.value = true
            } catch (error: Exception) {
                log.warn("Обновление не установилось", error)
                _state.value = UpdateState.Failed(update, error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private suspend fun download(update: UpdateInfo): Path = withContext(Dispatchers.IO) {
        val target = Files.createTempDirectory("mcp-destiny2-gui-update").resolve(update.fileName)
        val request = HttpRequest.newBuilder(URI.create(update.msiUrl))
            .timeout(DOWNLOAD_TIMEOUT)
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() in 200..299) { "Загрузка не удалась: HTTP ${response.statusCode()}" }
        var copied = 0L
        response.body().use { input ->
            Files.newOutputStream(target).use { output ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (update.sizeBytes > 0) {
                        val progress = (copied.toFloat() / update.sizeBytes).coerceIn(0f, 1f)
                        _state.value = UpdateState.Downloading(update, progress)
                    }
                }
            }
        }
        // Оборванная загрузка не должна доехать до msiexec: битый MSI — непонятная ошибка установки.
        check(update.sizeBytes <= 0 || copied == update.sizeBytes) {
            "Файл скачался не целиком: $copied из ${update.sizeBytes} байт"
        }
        target
    }

    private fun launchInstaller(msi: Path) {
        // /passive — прогресс без вопросов (UAC система покажет всё равно). Установщик стартует,
        // приложение закрывается: пока он дойдёт до замены файлов, процесса уже нет.
        val msiPath = msi.toAbsolutePath().toString()
        // Путь к своему exe лаунчер jpackage кладёт в системное свойство. Он переживает
        // обновление: MSI ставится в ту же папку. В dev-запуске свойства нет — перезапускать
        // нечего, установщик просто отрабатывает сам по себе.
        val exePath = System.getProperty("jpackage.app-path")
        if (exePath == null) {
            ProcessBuilder("msiexec", "/i", msiPath, "/passive", "/norestart").start()
            return
        }
        // Перезапуск делает «провожатый» — процесс wscript, который переживает и приложение,
        // и установку: дожидается msiexec (третий аргумент Run — True) и поднимает
        // свежепоставленный exe. VBS вместо cmd, чтобы на экране не мигало консольное окно.
        // Если установка сорвалась (игрок закрыл UAC), поднимется прежняя версия — путь тот же.
        val script = listOf(
            "Set sh = CreateObject(\"WScript.Shell\")",
            "sh.Run \"msiexec /i \"\"$msiPath\"\" /passive /norestart\", 1, True",
            "sh.Run \"\"\"$exePath\"\"\", 1, False",
        ).joinToString("\r\n")
        val vbs = msi.resolveSibling("update-and-restart.vbs")
        // UTF-16 LE с BOM (байты FF FE): без него wscript читает файл в ANSI,
        // и кириллица в путях ломает скрипт.
        Files.write(vbs, byteArrayOf(-1, -2) + script.toByteArray(StandardCharsets.UTF_16LE))
        ProcessBuilder("wscript", "//B", vbs.toString()).start()
    }

    private companion object {
        val log = LoggerFactory.getLogger(UpdateManager::class.java)
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/greeeenie/mcp-destiny2-gui/releases/latest"
        val FIRST_CHECK_DELAY = 10.seconds

        /** Обнова доезжает за минуты, не за перезапуск. GitHub без токена даёт 60 запросов
         * в час с адреса — 12 проверок в час укладываются с запасом. */
        val CHECK_INTERVAL = 5.minutes
        val CHECK_TIMEOUT: Duration = Duration.ofSeconds(20)

        /** MSI весит ~76 МБ: на медленном канале это минуты, а не секунды. */
        val DOWNLOAD_TIMEOUT: Duration = Duration.ofMinutes(15)
        const val DOWNLOAD_BUFFER_BYTES = 256 * 1024
    }
}
