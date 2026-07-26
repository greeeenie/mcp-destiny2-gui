package org.example.overlay.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tools.jackson.core.type.TypeReference
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
import java.time.Instant

/** Ошибка бэкенда. Тело ошибок всегда `{"error": "..."}` — деталей внешних систем в нём нет. */
open class BackendException(
    val statusCode: Int,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** 401. Refresh-ручки нет, поэтому единственная реакция — экран логина (§4). */
class UnauthorizedException(message: String) : BackendException(401, message)

/**
 * Клиент `mcp-destiny2-client` на `java.net.http` — том же, на котором построен транспорт
 * Inworld: один стек HTTP на REST и WebSocket, лишних зависимостей нет (§7).
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

    suspend fun tools(token: String): List<ToolInfo> =
        rawGet("/tools", token).requireSuccess().parse(object : TypeReference<List<ToolInfo>>() {})

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

    /**
     * Доступ к Inworld. Ключ Inworld и `x-api-key` MCP-профиля остаются на сервере — сюда
     * приходит только короткоживущий JWT и готовый кадр `session.update` (§3.2).
     */
    suspend fun voiceSession(token: String): VoiceAccess {
        val body = send(
            request("/voice/session", token, VOICE_SESSION_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
        ).requireSuccess().body()

        val root = try {
            mapper.readTree(body)
        } catch (error: Exception) {
            throw BackendException(200, "Не разобрать ответ /voice/session: ${error.message}", error)
        }
        val sessionUpdate = root.path("sessionUpdate")
        if (sessionUpdate.isMissingNode || sessionUpdate.isNull) {
            throw BackendException(200, "В ответе /voice/session нет sessionUpdate")
        }
        return VoiceAccess(
            token = root.path("token").asString(),
            tokenType = root.path("tokenType").asString().ifBlank { "Bearer" },
            expiresAt = parseInstant(root.path("expiresAt").asString()),
            uri = URI.create(root.path("uri").asString()),
            audio = mapper.treeToValue(root.path("audio"), VoiceAudioSettings::class.java),
            client = mapper.treeToValue(root.path("client"), VoiceClientSettings::class.java),
            // Строкой и без изменений — это и есть смысл контракта.
            sessionUpdate = sessionUpdate.toString(),
        )
    }

    private fun parseInstant(raw: String): Instant = try {
        Instant.parse(raw)
    } catch (error: Exception) {
        throw BackendException(200, "Неразбираемый expiresAt в /voice/session: '$raw'", error)
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

    private fun <T> HttpResponse<String>.parse(type: TypeReference<T>): T = try {
        mapper.readValue(body(), type)
    } catch (error: Exception) {
        throw BackendException(statusCode(), "Не разобрать ответ бэкенда: ${error.message}", error)
    }

    companion object {
        private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(20)
        private val TOOL_CALL_TIMEOUT: Duration = Duration.ofSeconds(60)

        /** Сервер поднимает MCP-сессию и ходит в Inworld — 20 секунд ему может не хватить. */
        private val VOICE_SESSION_TIMEOUT: Duration = Duration.ofSeconds(30)

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
