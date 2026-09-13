package org.example.overlay.app

enum class HudInteractionMode {
    PASS_THROUGH,
    EXPANDED_PASS_THROUGH,
    INTERACTIVE,
}

internal fun HudInteractionMode.next(): HudInteractionMode = when (this) {
    HudInteractionMode.PASS_THROUGH -> HudInteractionMode.EXPANDED_PASS_THROUGH
    HudInteractionMode.EXPANDED_PASS_THROUGH -> HudInteractionMode.INTERACTIVE
    HudInteractionMode.INTERACTIVE -> HudInteractionMode.PASS_THROUGH
}

/** Что видит игрок в строке статуса HUD (§5.3). */
sealed interface OverlayStatus {
    val label: String

    data object Disconnected : OverlayStatus {
        override val label = "Disconnected"
    }

    /** Голос включён, сессии нет: в push-to-talk это нормальное состояние между фразами. */
    data object Ready : OverlayStatus {
        override val label = "Ready"
    }

    data object Connecting : OverlayStatus {
        override val label = "Connecting"
    }

    data object Listening : OverlayStatus {
        override val label = "Listening"
    }

    data object Thinking : OverlayStatus {
        override val label = "Thinking"
    }

    data object SearchingWeb : OverlayStatus {
        override val label = "Searching web"
    }

    data object Speaking : OverlayStatus {
        override val label = "Speaking"
    }

    /** То же самое, но без озвучки: ответ приходит текстом. */
    data object Answering : OverlayStatus {
        override val label = "Answering"
    }

    data class Reconnecting(val attempt: Int, val maxAttempts: Int? = null) : OverlayStatus {
        override val label get() = "Reconnecting ($attempt${maxAttempts?.let { "/$it" } ?: ""})"
    }

    data class Failed(val reason: String) : OverlayStatus {
        override val label get() = "Error"
    }
}
