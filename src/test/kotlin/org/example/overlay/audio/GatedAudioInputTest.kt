package org.example.overlay.audio

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatedAudioInputTest {

    private fun chunk(marker: Byte) = ByteArray(4) { marker }

    @Test
    fun `закрытый гейт не пропускает звук`() {
        val gate = GatedAudioInput(capacity = 4)

        assertFalse(gate.offer(chunk(1)))
    }

    @Test
    fun `открытый гейт отдаёт чанк потребителю`() = runTest {
        val gate = GatedAudioInput(capacity = 4)
        gate.openGate()

        assertTrue(gate.offer(chunk(7)))
        assertContentEquals(chunk(7), gate.chunks.first())
    }

    @Test
    fun `закрытие гейта вычищает уже принятые чанки`() = runTest {
        val gate = GatedAudioInput(capacity = 4)
        gate.openGate()
        gate.offer(chunk(1))
        gate.offer(chunk(2))

        gate.closeGate()

        // Звук, захваченный в другом состоянии маршрутизации, не должен всплыть позже (§6).
        assertNull(withTimeoutOrNull(50) { gate.chunks.first() })
    }

    @Test
    fun `повторное открытие тоже вычищает очередь`() = runTest {
        val gate = GatedAudioInput(capacity = 4)
        gate.openGate()
        gate.offer(chunk(1))

        gate.openGate()
        gate.offer(chunk(9))

        assertContentEquals(chunk(9), gate.chunks.first())
    }

    @Test
    fun `переполнение выбрасывает самый старый чанк, а не падает`() = runTest {
        val gate = GatedAudioInput(capacity = 2)
        gate.openGate()

        assertTrue(gate.offer(chunk(1)))
        assertTrue(gate.offer(chunk(2)))
        assertTrue(gate.offer(chunk(3)))

        assertContentEquals(chunk(2), gate.chunks.first())
    }

    @Test
    fun `stop закрывает гейт`() = runTest {
        val gate = GatedAudioInput(capacity = 2)
        gate.openGate()

        gate.stop()

        assertFalse(gate.isOpen)
        assertFalse(gate.offer(chunk(1)))
    }
}
