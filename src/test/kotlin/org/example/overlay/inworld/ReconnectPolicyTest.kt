package org.example.overlay.inworld

import org.example.overlay.backend.VoiceClientSettings
import org.example.overlay.conversation.ConversationFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconnectPolicyTest {

    private val settings = VoiceClientSettings(
        connectTimeoutMs = 10_000,
        interruptTimeoutMs = 2_000,
        reconnectAttempts = 3,
        reconnectBaseDelayMs = 250,
        reconnectMaxDelayMs = 4_000,
        maxToolCallsPerTurn = 6,
    )

    private fun failure(retryable: Boolean, category: ConversationFailure.Category) =
        ConversationFailure(category, "…", retryable)

    @Test
    fun `сетевой сбой ретраится, пока не исчерпаны попытки`() {
        val policy = ReconnectPolicy(settings)
        val transport = failure(true, ConversationFailure.Category.TRANSPORT)

        assertTrue(policy.shouldRetry(transport, retriesCompleted = 0))
        assertTrue(policy.shouldRetry(transport, retriesCompleted = 2))
        assertFalse(policy.shouldRetry(transport, retriesCompleted = 3))
    }

    @Test
    fun `авторизация не ретраится политикой - её лечит перевыпуск токена`() {
        val policy = ReconnectPolicy(settings)

        assertFalse(policy.shouldRetry(failure(false, ConversationFailure.Category.AUTHENTICATION), 0))
    }

    @Test
    fun `задержка растёт экспоненциально и упирается в потолок`() {
        val policy = ReconnectPolicy(settings, jitter = { 0.5 })

        assertEquals(250, policy.delayMillis(1))
        assertEquals(500, policy.delayMillis(2))
        assertEquals(1_000, policy.delayMillis(3))
        assertEquals(4_000, policy.delayMillis(10))
    }

    @Test
    fun `джиттер держится в коридоре 0,75-1,25`() {
        assertEquals(750, ReconnectPolicy(settings, jitter = { 0.0 }).delayMillis(2) * 2)
        assertEquals(625, ReconnectPolicy(settings, jitter = { 1.0 }).delayMillis(2))
    }
}
