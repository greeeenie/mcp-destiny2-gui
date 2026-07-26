package org.example.overlay.app

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.overlay.backend.VoiceEventDto
import org.example.overlay.conversation.ConversationEvent
import org.example.overlay.conversation.ConversationFailure
import org.example.overlay.conversation.ConversationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceEventReporterTest {

    private val sent = mutableListOf<List<VoiceEventDto>>()

    private fun TestScope.reporter() = VoiceEventReporter(this) { batch -> sent += batch }.also { it.start() }

    @Test
    fun `реплики игрока и модели уезжают одной пачкой`() = runTest {
        val reporter = reporter()

        reporter.report(transcript(ConversationEvent.Speaker.USER, "покажи мои ауто"))
        reporter.report(ConversationEvent.ToolCompleted("searchWeapons", "SUCCESS", 412))
        reporter.report(transcript(ConversationEvent.Speaker.ASSISTANT, "Нашёл три автомата."))
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        reporter.stop()

        val batch = sent.single()
        assertEquals(listOf("transcript", "tool", "transcript"), batch.map { it.type })
        assertEquals(listOf("user", null, "assistant"), batch.map { it.speaker })
        assertEquals("покажи мои ауто", batch[0].text)
        assertEquals("searchWeapons", batch[1].name)
        assertEquals("SUCCESS", batch[1].status)
        assertEquals("Нашёл три автомата.", batch[2].text)
    }

    @Test
    fun `промежуточные куски транскрипта не отправляются`() = runTest {
        val reporter = reporter()

        reporter.report(ConversationEvent.Transcript(ConversationEvent.Speaker.ASSISTANT, "Наш", isFinal = false))
        reporter.report(transcript(ConversationEvent.Speaker.ASSISTANT, "   "))
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        reporter.stop()

        assertTrue(sent.isEmpty(), "отправлено лишнее: $sent")
    }

    @Test
    fun `отказ сессии уходит как ошибка`() = runTest {
        val reporter = reporter()

        reporter.report(
            ConversationEvent.StateChanged(
                ConversationState.Failed(
                    ConversationFailure(ConversationFailure.Category.TRANSPORT, "обрыв соединения", true),
                ),
            ),
        )
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        reporter.stop()

        val event = sent.single().single()
        assertEquals("error", event.type)
        assertEquals("обрыв соединения", event.text)
    }

    @Test
    fun `аудио и прочие события журнал не засоряют`() = runTest {
        val reporter = reporter()

        reporter.report(ConversationEvent.AudioChunk(ByteArray(16), 1))
        reporter.report(ConversationEvent.ResponseCompleted)
        reporter.report(ConversationEvent.SpeechStarted)
        reporter.report(ConversationEvent.Diagnostic("session.updated"))
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        reporter.stop()

        assertTrue(sent.isEmpty(), "отправлено лишнее: $sent")
    }

    @Test
    fun `сбой отправки не мешает следующим событиям`() = runTest {
        var attempts = 0
        val reporter = VoiceEventReporter(this) { batch ->
            attempts++
            if (attempts == 1) throw IllegalStateException("бэкенд недоступен")
            sent += batch
        }
        reporter.start()

        reporter.report(transcript(ConversationEvent.Speaker.USER, "первая"))
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        reporter.report(transcript(ConversationEvent.Speaker.USER, "вторая"))
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        reporter.stop()

        assertEquals(2, attempts)
        assertEquals("вторая", sent.single().single().text)
    }

    private fun transcript(speaker: ConversationEvent.Speaker, text: String) =
        ConversationEvent.Transcript(speaker, text, isFinal = true)
}
