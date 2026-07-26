package org.example.overlay.app

/** Строка лога инструментов: `searchWeapons → SUCCESS (1.2 с)` (§5.3). */
data class ToolLogEntry(
    val name: String,
    val status: String,
    val durationMs: Long,
    val message: String? = null,
) {
    val isFailure: Boolean get() = status != "SUCCESS"
}
