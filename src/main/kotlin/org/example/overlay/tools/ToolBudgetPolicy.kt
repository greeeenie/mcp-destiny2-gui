package org.example.overlay.tools

/**
 * Бюджет вызовов инструментов на один ход. Ограничивает и общее число вызовов, и число вызовов
 * одного инструмента: имена и лимиты — это конфигурация (приходит с сервера в блоке `client`),
 * а не логика движка.
 *
 * Не потокобезопасен: вызывающий сериализует [admit] и [resetTurn] (движок держит вокруг них
 * свой мьютекс допуска).
 */
class ToolBudgetPolicy(
    private val maxCallsPerTurn: Int,
    private val perToolCallsPerTurn: Map<String, Int>,
) {
    private var totalCalls = 0
    private val perToolCalls = HashMap<String, Int>()
    private var terminalRequested = false

    fun admit(toolName: String?): ToolBudgetDecision {
        totalCalls++
        val perToolExceeded = toolName != null &&
            perToolCallsPerTurn[toolName]?.let { limit ->
                perToolCalls.merge(toolName, 1, Int::plus)!! > limit
            } == true
        if (totalCalls <= maxCallsPerTurn && !perToolExceeded) {
            return ToolBudgetDecision.Admit
        }
        val requestTerminalResponse = !terminalRequested
        terminalRequested = true
        // Текст уезжает модели как результат инструмента, поэтому он на английском —
        // на том же языке, что и остальной протокол.
        val message = if (perToolExceeded) {
            "Tool retry limit for this action reached. Explain the previous failure to the user instead of retrying."
        } else {
            "Tool call limit reached. Answer the user now using the results already available."
        }
        return ToolBudgetDecision.Reject(message, requestTerminalResponse)
    }

    fun resetTurn() {
        totalCalls = 0
        perToolCalls.clear()
        terminalRequested = false
    }
}

sealed interface ToolBudgetDecision {
    data object Admit : ToolBudgetDecision

    data class Reject(
        val message: String,
        val requestTerminalResponse: Boolean,
    ) : ToolBudgetDecision
}
