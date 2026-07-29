package org.example.overlay.update

import tools.jackson.databind.JsonNode

/** Готовое к установке обновление: версия из тега релиза и MSI из его ассетов. */
data class UpdateInfo(
    val version: String,
    val msiUrl: String,
    val fileName: String,
    val sizeBytes: Long,
)

/** Разбор ответа GitHub `releases/latest`. Отдельно от сети — чтобы тестировался без неё. */
object ReleaseFeed {
    fun parse(release: JsonNode): UpdateInfo? {
        val version = release.path("tag_name").asString().trim().removePrefix("v")
        if (version.isEmpty()) return null
        // Релиз без MSI (например, только исходники) обновлением не считается.
        val msi = release.path("assets").firstOrNull { it.path("name").asString().endsWith(".msi") } ?: return null
        val url = msi.path("browser_download_url").asString()
        if (url.isEmpty()) return null
        return UpdateInfo(
            version = version,
            msiUrl = url,
            fileName = msi.path("name").asString(),
            sizeBytes = msi.path("size").asLong(),
        )
    }
}
