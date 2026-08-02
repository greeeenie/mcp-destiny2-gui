package org.example.overlay.app

/** Завершённый ход разговора: вопрос игрока и полный ответ ассистента. Для листания в HUD. */
data class HudTurn(
    val question: String,
    val answer: String,
)
