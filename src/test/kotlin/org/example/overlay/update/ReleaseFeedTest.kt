package org.example.overlay.update

import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseFeedTest {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `из ответа GitHub достаются версия и MSI-ассет`() {
        // Усечённый реальный ответ releases/latest: только используемые поля.
        val release = mapper.readTree(
            """
            {
              "tag_name": "v1.0.0",
              "name": "mcp-destiny2-gui 1.0.0",
              "assets": [
                {"name": "checksums.txt", "size": 128, "browser_download_url": "https://example.com/checksums.txt"},
                {
                  "name": "mcp-destiny2-gui-1.0.0.msi",
                  "size": 76736634,
                  "browser_download_url": "https://github.com/greeeenie/mcp-destiny2-gui/releases/download/v1.0.0/mcp-destiny2-gui-1.0.0.msi"
                }
              ]
            }
            """.trimIndent(),
        )

        val update = ReleaseFeed.parse(release)

        assertEquals(
            UpdateInfo(
                version = "1.0.0",
                msiUrl = "https://github.com/greeeenie/mcp-destiny2-gui/releases/download/v1.0.0/mcp-destiny2-gui-1.0.0.msi",
                fileName = "mcp-destiny2-gui-1.0.0.msi",
                sizeBytes = 76736634,
            ),
            update,
        )
    }

    @Test
    fun `релиз без MSI обновлением не считается`() {
        val release = mapper.readTree(
            """{"tag_name": "v1.0.0", "assets": [{"name": "source.zip", "browser_download_url": "https://e.com/s.zip"}]}""",
        )

        assertNull(ReleaseFeed.parse(release))
    }

    @Test
    fun `пустой или чужой ответ не роняет разбор`() {
        assertNull(ReleaseFeed.parse(mapper.readTree("{}")))
        assertNull(ReleaseFeed.parse(mapper.readTree("""{"message": "Not Found"}""")))
    }
}
