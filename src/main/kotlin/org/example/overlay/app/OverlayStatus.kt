package org.example.overlay.app

/** Что видит игрок в строке статуса HUD (§5.3). */
sealed interface OverlayStatus {
    val label: String

    data object Disconnected : OverlayStatus {
        override val label = "Не подключён"
    }

    /** Голос включён, сессии нет: в push-to-talk это нормальное состояние между фразами. */
    data object Ready : OverlayStatus {
        override val label = "Готов"
    }

    data object Connecting : OverlayStatus {
        override val label = "Подключаюсь"
    }

    data object Listening : OverlayStatus {
        override val label = "Слушаю"
    }

    data object Thinking : OverlayStatus {
        override val label = "Думаю"
    }

    data object Speaking : OverlayStatus {
        override val label = "Говорю"
    }

    /** То же самое, но без озвучки: ответ приходит текстом. */
    data object Answering : OverlayStatus {
        override val label = "Отвечает"
    }

    /** Модель ушла в веб-поиск: ход дольше обычного, и игрок должен видеть почему. */
    data object Searching : OverlayStatus {
        override val label = "Ищу в интернете"
    }

    data class Reconnecting(val attempt: Int, val maxAttempts: Int? = null) : OverlayStatus {
        override val label get() = "Переподключаюсь ($attempt${maxAttempts?.let { "/$it" } ?: ""})"
    }

    data class Failed(val reason: String) : OverlayStatus {
        override val label get() = "Ошибка"
    }
}
