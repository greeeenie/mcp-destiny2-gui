package org.example.overlay.tools

import tools.jackson.databind.JsonNode

/**
 * Результат инструмента в том виде, в каком его отдаёт бэкенд: строковый статус, готовый
 * `output` и текст для модели.
 *
 * Тонкий намеренно (§1 плана): локальный диспетчер, таймауты, ретраи и взаимное исключение
 * живут на сервере (`org.example.tools.ToolDispatcher`), и переносить их сюда нельзя —
 * оверлей не должен становиться вторым местом принятия решений об инструментах.
 */
data class ToolResult(
    val status: String,
    val output: JsonNode? = null,
    val message: String? = null,
) {
    companion object {
        const val SUCCESS = "SUCCESS"
        const val TIMEOUT = "TIMEOUT"
        const val BUSY = "BUSY"
        const val REJECTED = "REJECTED"
        const val INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
        const val FAILED = "FAILED"

        fun rejected(message: String) = ToolResult(REJECTED, message = message)

        fun invalidArguments(message: String) = ToolResult(INVALID_ARGUMENTS, message = message)

        fun failed(message: String) = ToolResult(FAILED, message = message)
    }
}

/** Единственная точка исполнения инструмента: HTTP-вызов `POST /tools/{name}` нашего бэкенда. */
fun interface ToolExecutor {
    suspend fun execute(name: String, arguments: JsonNode): ToolResult
}
