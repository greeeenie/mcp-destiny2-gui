package org.example.overlay.inworld

import org.example.overlay.tools.ToolResult
import tools.jackson.databind.json.JsonMapper
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InworldProtocolTest {

    private val mapper = JsonMapper.builder().build()
    private val protocol = InworldProtocol(mapper)

    @Test
    fun `кадр звука содержит base64 полезной нагрузки`() {
        val bytes = byteArrayOf(1, 2, 3, 4)

        val frame = mapper.readTree(protocol.appendAudio(bytes))

        assertEquals("input_audio_buffer.append", frame.path("type").asString())
        assertEquals(Base64.getEncoder().encodeToString(bytes), frame.path("audio").asString())
    }

    @Test
    fun `commit и clear - отдельные кадры без полей`() {
        assertEquals("input_audio_buffer.commit", mapper.readTree(protocol.inputAudioBufferCommit()).path("type").asString())
        assertEquals("input_audio_buffer.clear", mapper.readTree(protocol.inputAudioBufferClear()).path("type").asString())
    }

    @Test
    fun `патч модальностей просит только текст`() {
        // Без него Inworld синтезирует речь и берёт за неё деньги, даже если её не играть.
        val frame = mapper.readTree(protocol.textOnlyOutput())
        val modalities = frame.path("session").path("output_modalities")

        assertEquals("session.update", frame.path("type").asString())
        assertEquals(1, modalities.size())
        assertEquals("text", modalities.path(0).asString())
    }

    @Test
    fun `отмена ответа несёт его идентификатор`() {
        val frame = mapper.readTree(protocol.cancelResponse("resp-7"))

        assertEquals("response.cancel", frame.path("type").asString())
        assertEquals("resp-7", frame.path("response_id").asString())
    }

    @Test
    fun `результат инструмента кладётся строкой внутрь function_call_output`() {
        val output = mapper.createObjectNode().put("weapons", 2)
        val result = ToolResult(ToolResult.SUCCESS, output = output, message = "готово")

        val frame = mapper.readTree(protocol.functionCallOutput("call-1", result))
        val item = frame.path("item")

        assertEquals("conversation.item.create", frame.path("type").asString())
        assertEquals("function_call_output", item.path("type").asString())
        assertEquals("call-1", item.path("call_id").asString())

        // Поле output — именно строка: так его ждёт Inworld.
        assertTrue(item.path("output").isString)
        val payload = mapper.readTree(item.path("output").asString())
        assertEquals("SUCCESS", payload.path("status").asString())
        assertEquals("готово", payload.path("message").asString())
        assertEquals(2, payload.path("output").path("weapons").asInt())
    }

    @Test
    fun `оба имени события со звуком разбираются одинаково`() {
        val payload = Base64.getEncoder().encodeToString(byteArrayOf(9, 9))

        listOf("response.audio.delta", "response.output_audio.delta").forEach { type ->
            val event = protocol.decode("""{"type":"$type","delta":"$payload","response_id":"r1"}""")

            val audio = assertIs<InworldServerEvent.AudioDelta>(event)
            assertEquals("r1", audio.responseId)
            assertEquals(2, audio.bytes.size)
        }
    }

    @Test
    fun `финальный транскрипт пользователя помечается финальным`() {
        val event = protocol.decode(
            """{"type":"conversation.item.input_audio_transcription.completed","transcript":"покажи пушки"}""",
        )

        val transcript = assertIs<InworldServerEvent.Transcript>(event)
        assertEquals(InworldServerEvent.Speaker.USER, transcript.speaker)
        assertEquals("покажи пушки", transcript.text)
        assertTrue(transcript.isFinal)
    }

    @Test
    fun `вызов инструмента с нестроковыми аргументами помечается битым`() {
        val event = protocol.decode(
            """{"type":"response.function_call_arguments.done","call_id":"c1","name":"searchWeapons","arguments":{"a":1}}""",
        )

        val call = assertIs<InworldServerEvent.FunctionCallArgumentsDone>(event)
        assertTrue(call.malformed)
    }

    @Test
    fun `аргументы инструмента разбираются из строки`() {
        val parsed = protocol.parseToolArguments("""{"name":"Falling Guillotine"}""")

        assertEquals("Falling Guillotine", parsed?.path("name")?.asString())
        assertNull(protocol.parseToolArguments("не json"))
    }

    @Test
    fun `неизвестное событие не роняет разбор`() {
        val event = protocol.decode("""{"type":"something.new"}""")

        assertEquals(InworldServerEvent.Unknown("something.new"), event)
    }

    @Test
    fun `битый base64 в звуке даёт ошибку протокола`() {
        val error = runCatching { protocol.decode("""{"type":"response.output_audio.delta","delta":"!!!"}""") }

        assertIs<InworldProtocolException>(error.exceptionOrNull())
    }
}
