package org.example.overlay.app

/** Строка лога инструментов: `searchWeapons → SUCCESS (1.2 с)` (§5.3). */
data class ToolLogEntry(
    val name: String,
    val status: String,
    val durationMs: Long,
    val message: String? = null,
) {
    val isFailure: Boolean get() = status != "SUCCESS"

    /** Веб-поиск роутера: сервер шлёт его тем же событием `tool`, но рисуется он иначе. */
    val isWebSearch: Boolean get() = name == WEB_SEARCH

    companion object {
        const val WEB_SEARCH = "webSearch"
    }
}
