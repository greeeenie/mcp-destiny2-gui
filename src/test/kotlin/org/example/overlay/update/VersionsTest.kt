package org.example.overlay.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionsTest {

    @Test
    fun `новее, когда старше любая из частей`() {
        assertTrue(Versions.isNewer("1.0.1", "1.0.0"))
        assertTrue(Versions.isNewer("1.1.0", "1.0.9"))
        assertTrue(Versions.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun `сравнение числовое, а не строковое`() {
        // Строково "1.10.0" < "1.9.0" — классическая ловушка сортировки версий.
        assertTrue(Versions.isNewer("1.10.0", "1.9.0"))
        assertFalse(Versions.isNewer("1.9.0", "1.10.0"))
    }

    @Test
    fun `равная и более старая версии обновлением не считаются`() {
        assertFalse(Versions.isNewer("1.0.0", "1.0.0"))
        assertFalse(Versions.isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun `префикс v из тега и разная длина не мешают`() {
        assertTrue(Versions.isNewer("v1.0.1", "1.0.0"))
        assertTrue(Versions.isNewer("1.0.0.1", "1.0.0"))
        assertFalse(Versions.isNewer("1.0", "1.0.0"))
    }

    @Test
    fun `нечисловой хвост отбрасывается, мусор не роняет сравнение`() {
        assertTrue(Versions.isNewer("1.0.2-beta", "1.0.1"))
        assertFalse(Versions.isNewer("garbage", "1.0.0"))
    }
}
