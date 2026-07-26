package org.example.overlay.app

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsStoreTest {

    @Test
    fun `отсутствующий файл даёт настройки по умолчанию`() {
        val store = SettingsStore(createTempDirectory("settings").resolve("settings.json"))

        assertEquals(Settings(), store.load())
    }

    @Test
    fun `сохранённые настройки читаются обратно`() {
        val file = createTempDirectory("settings").resolve("settings.json")
        val store = SettingsStore(file)
        val settings = Settings(
            baseUrl = "https://example.test/api",
            hud = HudSettings(x = 120f, y = 40f, opacity = 0.55f),
            pttKeyCode = 0xA4,
            handsFree = true,
        )

        store.save(settings)

        assertEquals(settings, store.load())
    }

    @Test
    fun `битый файл не мешает запуску`() {
        val file = createTempDirectory("settings").resolve("settings.json")
        Files.writeString(file, "{ это не json")

        assertEquals(Settings(), SettingsStore(file).load())
    }

    @Test
    fun `незнакомые поля игнорируются - файл от новой версии не ломает старую`() {
        val file = createTempDirectory("settings").resolve("settings.json")
        Files.writeString(file, """{"baseUrl":"https://example.test","чегоТоНовое":42}""")

        assertEquals("https://example.test", SettingsStore(file).load().baseUrl)
    }

    @Test
    fun `запись создаёт каталог, которого ещё нет`() {
        val file = createTempDirectory("settings").resolve("вложенный").resolve("settings.json")

        SettingsStore(file).save(Settings())

        assertTrue(Files.exists(file))
    }
}
