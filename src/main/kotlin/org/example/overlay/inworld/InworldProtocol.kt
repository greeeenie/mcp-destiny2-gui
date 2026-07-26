package org.example.overlay.inworld

import org.example.overlay.tools.ToolResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.Base64

/**
 * Кадры и разбор событий Inworld Realtime — донорский код, переведённый на Jackson 3.
 *
 * Кадра `session.update` здесь нет намеренно: его целиком собирает бэкенд и присылает в
 * `/voice/session` готовой строкой, а оверлей отправляет её как есть, не разбирая (§3.2).
 * Это позволяет менять промпт, голос, модель и набор инструментов без новой версии оверлея.
 */
class InworldProtocol(
    private val objectMapper: ObjectMapper,
) {
    fun appendAudio(bytes: ByteArray): String =
        AUDIO_APPEND_PREFIX + BASE64_ENCODER.encodeToString(bytes) + AUDIO_APPEND_SUFFIX

    /**
     * Узкий патч модальностей: просим только текст.
     *
     * Отправляется вторым кадром, сразу после серверного `session.update`. Серверную строку мы
     * не разбираем и не переписываем (§3.2) — уточняем ровно одно поле, и только затем, чтобы
     * Inworld не синтезировал речь, за которую придётся платить.
     */
    fun textOnlyOutput(): String = message("session.update") {
        putObject("session").putArray("output_modalities").add("text")
    }

    /** Явное закрытие входного буфера — запасной путь фазы 5, если хвоста тишины не хватит. */
    fun inputAudioBufferCommit(): String = message("input_audio_buffer.commit")

    /** Сброс входного буфера при прерывании: иначе модель начнёт ход по остаткам речи. */
    fun inputAudioBufferClear(): String = message("input_audio_buffer.clear")

    fun cancelResponse(responseId: String? = null): String = message("response.cancel") {
        responseId?.let { put("response_id", it) }
    }

    fun createResponse(toolChoice: String? = null): String = message("response.create") {
        toolChoice?.let { choice -> putObject("response").put("tool_choice", choice) }
    }

    fun parseToolArguments(arguments: String): JsonNode? = try {
        objectMapper.readTree(arguments)?.takeUnless { it.isMissingNode || it.isNull }
    } catch (_: Exception) {
        null
    }

    /**
     * Результат инструмента упаковывается ровно как в доноре: объект `{status, message, output}`
     * сериализуется в строку и кладётся в поле `output`. Статусы бэкенда совпадают с донорскими
     * один в один, поэтому конвертации нет (§3.3).
     */
    fun functionCallOutput(callId: String, result: ToolResult): String {
        val output = objectMapper.createObjectNode().put("status", result.status)
        result.message?.let { output.put("message", it) }
        result.output?.let { output.set("output", it) }

        return message("conversation.item.create") {
            putObject("item")
                .put("type", "function_call_output")
                .put("call_id", callId)
                .put("output", objectMapper.writeValueAsString(output))
        }
    }

    fun decode(json: String): InworldServerEvent {
        val root = objectMapper.readTree(json)
        val type = root.path("type").asString("")
        return when (type) {
            "session.created" -> InworldServerEvent.SessionCreated(root.path("session").path("id").textOrNull())
            "session.updated" -> InworldServerEvent.SessionUpdated
            "response.created" -> InworldServerEvent.ResponseCreated(root.path("response").path("id").asString())
            // Оба варианта имён покрыты намеренно: разные деплои Inworld шлют разные.
            "response.audio.delta", "response.output_audio.delta" ->
                InworldServerEvent.AudioDelta(
                    bytes = decodeBase64(root.path("delta"), type),
                    responseId = root.path("response_id").textOrNull(),
                )
            "input_audio_buffer.speech_started" -> InworldServerEvent.SpeechStarted
            "conversation.item.input_audio_transcription.delta",
            "input_audio_transcription.delta",
            "input_audio_buffer.transcription.delta",
            -> InworldServerEvent.Transcript(InworldServerEvent.Speaker.USER, root.path("delta").asString(), false)
            "conversation.item.input_audio_transcription.completed" ->
                InworldServerEvent.Transcript(InworldServerEvent.Speaker.USER, root.path("transcript").asString(), true)
            "response.output_text.delta",
            "response.text.delta",
            "response.audio_transcript.delta",
            "response.output_audio_transcript.delta",
            -> InworldServerEvent.Transcript(InworldServerEvent.Speaker.ASSISTANT, root.path("delta").asString(), false)
            "response.output_audio_transcript.done" ->
                InworldServerEvent.Transcript(
                    InworldServerEvent.Speaker.ASSISTANT,
                    root.path("transcript").asString(),
                    true,
                )
            "response.output_audio.done" -> InworldServerEvent.AudioOutputDone(root.path("response_id").textOrNull())
            "response.function_call_arguments.done" -> decodeFunctionCall(root)
            "response.done" -> InworldServerEvent.ResponseDone(
                responseId = root.path("response").path("id").textOrNull(),
                status = root.path("response").path("status").textOrNull(),
            )
            "error" -> decodeError(root)
            else -> InworldServerEvent.Unknown(type.ifBlank { "missing-type" })
        }
    }

    private fun decodeError(root: JsonNode): InworldServerEvent.Error {
        val error = root.path("error")
        val code = error.path("code").textOrNull() ?: root.path("errorType").textOrNull() ?: "unknown"
        val message = error.path("message").textOrNull() ?: "Ошибка Inworld Realtime"
        return InworldServerEvent.Error(code, message)
    }

    private fun decodeFunctionCall(root: JsonNode): InworldServerEvent.FunctionCallArgumentsDone {
        val argumentNode = root.path("arguments")
        val arguments = if (argumentNode.isString) argumentNode.asString() else ""
        return InworldServerEvent.FunctionCallArgumentsDone(
            callId = root.path("call_id").textOrNull(),
            name = root.path("name").textOrNull(),
            arguments = arguments,
            responseId = root.path("response_id").textOrNull(),
            itemId = root.path("item_id").textOrNull(),
            malformed = !argumentNode.isString || arguments.length > MAX_TOOL_ARGUMENT_CHARS,
        )
    }

    private fun decodeBase64(node: JsonNode, eventType: String): ByteArray = try {
        Base64.getDecoder().decode(node.asString())
    } catch (error: IllegalArgumentException) {
        throw InworldProtocolException("Неверный Base64 в $eventType", error)
    }

    private fun message(type: String, content: ObjectNode.() -> Unit = {}): String {
        val root = objectMapper.createObjectNode().put("type", type)
        root.content()
        return objectMapper.writeValueAsString(root)
    }

    private fun JsonNode.textOrNull(): String? =
        takeUnless { isMissingNode || isNull }?.asString()?.takeIf { it.isNotBlank() }

    private companion object {
        // Кадр звука собирается конкатенацией строк: он летит каждые 100 мс, и лишняя
        // сериализация дерева здесь ничем не оправдана.
        const val AUDIO_APPEND_PREFIX = "{\"type\":\"input_audio_buffer.append\",\"audio\":\""
        const val AUDIO_APPEND_SUFFIX = "\"}"
        val BASE64_ENCODER: Base64.Encoder = Base64.getEncoder()
        const val MAX_TOOL_ARGUMENT_CHARS = 65_536
    }
}

sealed interface InworldServerEvent {
    data class SessionCreated(val sessionId: String?) : InworldServerEvent
    data object SessionUpdated : InworldServerEvent
    data class ResponseCreated(val responseId: String) : InworldServerEvent
    data class AudioDelta(val bytes: ByteArray, val responseId: String? = null) : InworldServerEvent
    data class AudioOutputDone(val responseId: String?) : InworldServerEvent
    data class Transcript(val speaker: Speaker, val text: String, val isFinal: Boolean) : InworldServerEvent
    data object SpeechStarted : InworldServerEvent
    data class FunctionCallArgumentsDone(
        val callId: String?,
        val name: String?,
        val arguments: String,
        val responseId: String?,
        val itemId: String?,
        val malformed: Boolean,
    ) : InworldServerEvent
    data class ResponseDone(val responseId: String?, val status: String?) : InworldServerEvent
    data class Error(val code: String, val message: String) : InworldServerEvent
    data class Unknown(val type: String) : InworldServerEvent

    enum class Speaker { USER, ASSISTANT }
}

class InworldProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
