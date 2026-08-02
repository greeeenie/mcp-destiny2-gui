package org.example.overlay.app

/** Завершённый ход разговора: вопрос игрока и полный ответ ассистента. Для листания в HUD. */
data class HudTurn(
    val question: String,
    val answer: String,
)

internal fun nextHudHistoryIndex(current: Int?, historySize: Int): Int? =
    if (current != null && current < historySize - 1) current + 1 else current
