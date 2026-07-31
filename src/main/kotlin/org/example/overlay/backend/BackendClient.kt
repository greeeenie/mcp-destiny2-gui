package org.example.overlay.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

/** Событие потока ответа: то, что пришло по SSE от `/voice/chat`. */
sealed interface ChatStreamEvent {
    data class Delta(val text: String) : ChatStreamEvent
    data class Tool(val name: String, val status: String, val durationMs: Long) : ChatStreamEvent
    data class Done(val text: String) : ChatStreamEvent
    data class Failed(val message: String) : ChatStreamEvent
}

/** Ошибка бэкенда. Тело ошибок всегда `{"error": "..."}` — деталей внешних систем в нём нет. */
open class BackendException(
    val statusCode: Int,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** 401. Refresh-ручки нет, поэтому единственная реакция — экран логина (§4). */
class UnauthorizedException(message: String) : BackendException(401, message)

/**
 * Клиент `mcp-destiny2-client` на `java.net.http`: лишних зависимостей нет (§7). С Inworld
 * оверлей не разговаривает вовсе — ключ и весь голосовой тракт живут на сервере (риск 10).
 *
 * Запросы блокирующие и уходят на `Dispatchers.IO`: асинхронный API `HttpClient` здесь ничего
 * не даёт, а читать код проще.
 */
class BackendClient(
    private val baseUrl: () -> String,
    private val http: HttpClient = defaultHttpClient(),
) {
    val mapper: JsonMapper = MAPPER

    suspend fun health(): Boolean = try {
        rawGet("/actuator/health", token = null).statusCode() in 200..299
    } catch (_: BackendException) {
        false
    }

    /** 409 — имя занято, 400 — не прошло валидацию; текст обеих ошибок приходит в `{error}`. */
    suspend fun register(username: String, password: String) {
        post("/auth/register", credentials(username, password), token = null).requireSuccess()
    }

    suspend fun login(username: String, password: String): LoginResponse =
        post("/auth/login", credentials(username, password), token = null)
            .requireSuccess()
            .parse(LoginResponse::class.java)

    suspend fun profile(token: String): Profile =
        rawGet("/profile", token).requireSuccess().parse(Profile::class.java)

    /**
     * Отвязать Bungie-аккаунт от MCP-профиля. Ручки на сервере пока нет — до её появления
     * вызов честно вернёт ошибку бэкенда; путь согласован на будущее.
     */
    suspend fun unlinkBungie(token: String) {
        send(request("/profile/bungie", token, DEFAULT_TIMEOUT).DELETE().build()).requireSuccess()
    }

    /**
     * Вызов инструмента. Таймаут больше обычного: MCP-сессия на той стороне поднимается
     * на каждый запрос (риск 2), а обрывать инструмент раньше сервера бессмысленно.
     */
    suspend fun callTool(token: String, name: String, arguments: JsonNode?): ToolCallResult {
        val body = mapper.writeValueAsString(arguments ?: mapper.createObjectNode())
        val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8)
        return post("/tools/$encodedName", body, token, TOOL_CALL_TIMEOUT)
            .requireSuccess()
            .parse(ToolCallResult::class.java)
    }

    /** Речь в текст. Аудио уходит WAV в base64: кодек сервер определяет сам. */
    suspend fun transcribe(token: String, wav: ByteArray, model: String? = null, language: String? = null): String {
        val body = mapper.writeValueAsString(
            mapper.createObjectNode()
                .put("audioBase64", Base64.getEncoder().encodeToString(wav))
                .apply {
                    model?.let { put("model", it) }
                    language?.let { put("language", it) }
                },
        )
        val response = post("/voice/transcribe", body, token, TRANSCRIBE_TIMEOUT).requireSuccess()
        return mapper.readTree(response.body()).path("text").asString().trim()
    }

    /** Модели на выбор. Список живёт на сервере: оверлей его только показывает. */
    suspend fun voiceModels(token: String): VoiceModels =
        rawGet("/voice/models", token).requireSuccess().parse(VoiceModels::class.java)

    /** Сброс истории разговора: сервер забывает контекст, следующий ход — с чистого листа. */
    suspend fun clearVoiceHistory(token: String) {
        send(request("/voice/history", token, DEFAULT_TIMEOUT).DELETE().build()).requireSuccess()
    }

    /**
     * Ход разговора потоком. Сервер отдаёт SSE: `delta` — кусок ответа, `tool` — отработавший
     * инструмент, `done` — ответ целиком, `error` — сорвалось. Читаем построчно и отдаём наружу
     * по мере поступления, поэтому текст появляется в HUD, пока модель ещё пишет.
     */
    suspend fun streamChat(token: String, text: String, model: String?, onEvent: (ChatStreamEvent) -> Unit) {
        val body = mapper.writeValueAsString(
            mapper.createObjectNode().put("text", text).apply { model?.let { put("model", it) } },
        )
        val request = request("/voice/chat", token, CHAT_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()

        withContext(Dispatchers.IO) {
            val response = try {
                http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (error: IOException) {
                throw BackendException(0, "Бэкенд недоступен: ${error.message ?: error.javaClass.simpleName}", error)
            }
            response.body().use { input ->
                if (response.statusCode() == 401) throw UnauthorizedException("Сессия недействительна")
                if (response.statusCode() !in 200..299) {
                    throw BackendException(response.statusCode(), "Бэкенд ответил HTTP ${response.statusCode()}")
                }
                var eventName = "message"
                input.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                    when {
                        line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            val payload = line.removePrefix("data:").trim()
                            if (payload.isNotEmpty()) onEvent(chatEvent(eventName, payload))
                        }
                        // Пустая строка закрывает событие: следующее начнётся со своего event.
                        line.isBlank() -> eventName = "message"
                    }
                }
            }
        }
    }

    private fun chatEvent(name: String, payload: String): ChatStreamEvent {
        val node = runCatching { mapper.readTree(payload) }.getOrNull()
        val text = node?.path("text")?.asString().orEmpty()
        return when (name) {
            "delta" -> ChatStreamEvent.Delta(text)
            "done" -> ChatStreamEvent.Done(text)
            "tool" -> ChatStreamEvent.Tool(
                name = node?.path("name")?.asString().orEmpty(),
                status = node?.path("status")?.asString().orEmpty(),
                durationMs = node?.path("durationMs")?.asLong() ?: 0L,
            )

            else -> ChatStreamEvent.Failed(node?.path("message")?.asString()?.ifBlank { null } ?: "Ход не удался")
        }
    }

    private fun credentials(username: String, password: String): String =
        mapper.writeValueAsString(
            mapper.createObjectNode()
                .put("username", username)
                .put("password", password),
        )

    private suspend fun rawGet(path: String, token: String?): HttpResponse<String> =
        send(request(path, token, DEFAULT_TIMEOUT).GET().build())

    private suspend fun post(
        path: String,
        body: String,
        token: String?,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): HttpResponse<String> = send(
        request(path, token, timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
    )

    private fun request(path: String, token: String?, timeout: Duration): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl().trimEnd('/') + path))
            .timeout(timeout)
            .header("Accept", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private suspend fun send(request: HttpRequest): HttpResponse<String> = withContext(Dispatchers.IO) {
        try {
            http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (error: IOException) {
            throw BackendException(0, "Бэкенд недоступен: ${error.message ?: error.javaClass.simpleName}", error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw BackendException(0, "Запрос к бэкенду прерван", error)
        }
    }

    private fun HttpResponse<String>.requireSuccess(): HttpResponse<String> {
        if (statusCode() in 200..299) return this
        val message = errorMessage(body()) ?: "Бэкенд ответил HTTP ${statusCode()}"
        if (statusCode() == 401) throw UnauthorizedException(message)
        throw BackendException(statusCode(), message)
    }

    private fun errorMessage(body: String): String? = try {
        mapper.readTree(body).path("error").asString().takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private fun <T> HttpResponse<String>.parse(type: Class<T>): T = try {
        mapper.readValue(body(), type)
    } catch (error: Exception) {
        throw BackendException(statusCode(), "Не разобрать ответ бэкенда: ${error.message}", error)
    }

    companion object {
        private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(20)
        private val TOOL_CALL_TIMEOUT: Duration = Duration.ofSeconds(60)

        /** Расшифровка идёт батчем: полминуты речи Inworld разбирает не мгновенно. */
        private val TRANSCRIBE_TIMEOUT: Duration = Duration.ofSeconds(45)

        /** Ход с инструментами живёт дольше обычного запроса: ждём весь поток. */
        private val CHAT_TIMEOUT: Duration = Duration.ofMinutes(2)

        val MAPPER: JsonMapper = JsonMapper.builder()
            .addModule(kotlinModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

        fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }
}
