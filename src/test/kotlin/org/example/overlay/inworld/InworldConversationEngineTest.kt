package org.example.overlay.inworld

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.example.overlay.backend.VoiceAccess
import org.example.overlay.backend.VoiceAccessProvider
import org.example.overlay.backend.VoiceAudioSettings
import org.example.overlay.backend.VoiceClientSettings
import org.example.overlay.conversation.ConversationState
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Проверяет цикл соединения целиком, без сети.
 *
 * Главная ценность — не только логика: сам факт вызова `connectionLoop` ловит `VerifyError`,
 * который однажды уже родился из слишком большой suspend-функции и убивал корутину молча.
 */
class InworldConversationEngineTest {

    private val mapper = JsonMapper.builder().build()

    private val access = VoiceAccess(
        token = "jwt",
        tokenType = "Bearer",
        expiresAt = Instant.parse("2026-07-26T18:00:00Z"),
        uri = URI.create("wss://api.inworld.ai/api/v1/realtime/session"),
        audio = VoiceAudioSettings(24_000, 24_000, 100, 4_800),
        client = VoiceClientSettings(
            connectTimeoutMs = 10_000,
            interruptTimeoutMs = 2_000,
            reconnectAttempts = 3,
            reconnectBaseDelayMs = 250,
            reconnectMaxDelayMs = 4_000,
            maxToolCallsPerTurn = 6,
        ),
        sessionUpdate = """{"type":"session.update","session":{"model":"test"}}""",
    )

    private class FakeProvider(private val access: VoiceAccess) : VoiceAccessProvider {
        var invalidations = 0
        override suspend fun get(): VoiceAccess = access
        override suspend fun invalidate() {
            invalidations++
        }
    }

    /** Транспорт-заглушка: отвечает на первый кадр так, как ответил бы Inworld. */
    private class FakeTransport : RealtimeTransport {
        private val messages = Channel<String>(16)
        val sent = mutableListOf<String>()
        var connectedTo: URI? = null
        var headers: Map<String, String> = emptyMap()

        override val incoming: Flow<String> = messages.receiveAsFlow()

        override suspend fun connect(uri: URI, headers: Map<String, String>) {
            connectedTo = uri
            this.headers = headers
            messages.send("""{"type":"session.created","session":{"id":"s1"}}""")
        }

        override suspend fun send(text: String) {
            sent += text
            if (text.contains("session.update")) {
                messages.send("""{"type":"session.updated"}""")
            }
        }

        override suspend fun close(code: Int, reason: String) {
            messages.close()
        }
    }

    private fun TestScope.engineScope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler))

    private fun engine(
        scope: CoroutineScope,
        transport: FakeTransport,
        provider: VoiceAccessProvider,
        textOnlyOutput: Boolean = false,
    ) = InworldConversationEngine(
        protocol = InworldProtocol(mapper),
        transportFactory = { transport },
        voiceSessionProvider = provider,
        scope = scope,
        textOnlyOutput = textOnlyOutput,
        retryDelay = { },
    )

    @Test
    fun `сессия поднимается и уходит в Active`() = runTest {
        val transport = FakeTransport()
        val engine = engine(engineScope(), transport, FakeProvider(access))

        engine.start()

        assertIs<ConversationState.Active>(engine.state.value)
        assertEquals(access.uri, transport.connectedTo)
        assertEquals("Bearer jwt", transport.headers["Authorization"])
    }

    @Test
    fun `серверный sessionUpdate уходит первым кадром и без изменений`() = runTest {
        val transport = FakeTransport()
        val engine = engine(engineScope(), transport, FakeProvider(access))

        engine.start()

        // Строку с сервера оверлей не разбирает и не пересобирает — иначе смысл §3.2 теряется.
        assertEquals(access.sessionUpdate, transport.sent.first())
    }

    @Test
    fun `без озвучки вторым кадром уходит просьба о текстовой модальности`() = runTest {
        val transport = FakeTransport()
        val engine = engine(engineScope(), transport, FakeProvider(access), textOnlyOutput = true)

        engine.start()

        // Порядок важен: сначала серверная строка как есть, потом наш узкий патч.
        assertEquals(access.sessionUpdate, transport.sent[0])
        val patch = mapper.readTree(transport.sent[1])
        assertEquals("session.update", patch.path("type").asString())
        assertEquals("text", patch.path("session").path("output_modalities").path(0).asString())
    }

    @Test
    fun `с озвучкой лишних кадров не отправляется`() = runTest {
        val transport = FakeTransport()
        val engine = engine(engineScope(), transport, FakeProvider(access), textOnlyOutput = false)

        engine.start()

        assertEquals(listOf(access.sessionUpdate), transport.sent.toList())
    }

    @Test
    fun `остановка возвращает движок в Idle`() = runTest {
        val transport = FakeTransport()
        val engine = engine(engineScope(), transport, FakeProvider(access))
        engine.start()

        engine.stop()

        assertEquals(ConversationState.Idle, engine.state.value)
    }

    @Test
    fun `прерывание без активного ответа ничего не ломает`() = runTest {
        val transport = FakeTransport()
        val engine = engine(engineScope(), transport, FakeProvider(access))
        engine.start()

        assertTrue(!engine.interrupt())
    }
}
