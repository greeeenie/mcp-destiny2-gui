package org.example.overlay.ptt

sealed interface ActivationState {
    data object Idle : ActivationState
    data object Connecting : ActivationState

    /** Клавиша зажата, гейт микрофона открыт. */
    data object Listening : ActivationState

    /** Клавиша отпущена, ход закрыт, ответа ещё нет. */
    data object Thinking : ActivationState
    data object Speaking : ActivationState

    /** Окно после ответа: в hands-free микрофон открыт, в push-to-talk ждём клавишу. */
    data class FollowUp(val deadlineNanos: Long) : ActivationState
    data class Failed(val message: String) : ActivationState
}
