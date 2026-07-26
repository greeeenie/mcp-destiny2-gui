package org.example.overlay.backend

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoredSessionTest {

    private val now: Instant = Instant.parse("2026-07-26T12:00:00Z")

    private fun sessionExpiringIn(seconds: Long) = StoredSession(
        username = "guardian",
        token = "token",
        expiresAtEpochSecond = now.plusSeconds(seconds).epochSecond,
    )

    @Test
    fun `свежая сессия не считается истёкшей`() {
        assertFalse(sessionExpiringIn(12 * 3600).isExpired(now))
    }

    @Test
    fun `момент истечения уже считается истёкшим`() {
        assertTrue(sessionExpiringIn(0).isExpired(now))
    }

    @Test
    fun `за полчаса до конца показываем предупреждение`() {
        assertTrue(sessionExpiringIn(29 * 60).isExpiringSoon(now))
        assertFalse(sessionExpiringIn(31 * 60).isExpiringSoon(now))
    }
}
