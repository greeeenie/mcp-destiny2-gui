package org.example.overlay.backend

import org.example.overlay.tools.ToolExecutor
import org.example.overlay.tools.ToolResult
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode

/**
 * `function_call` → `POST /tools/{name}` нашего бэкенда (§2, §3.1).
 *
 * Оверлей не ходит в `mcp-destiny2` напрямую именно поэтому: `x-api-key` MCP-профиля не должен
 * уезжать на машину игрока. Здесь только Bearer-токен пользователя.
 *
 * Сообщения об ошибках — на английском: они уходят модели, а не игроку.
 */
class BackendToolExecutor(
    private val backend: BackendClient,
    private val tokenProvider: () -> String?,
    /** Хук для UI: голосовой путь привязки Bungie ловит здесь ссылку из `authorize` (§6 фазы). */
    private val onResult: (String, ToolResult) -> Unit = { _, _ -> },
) : ToolExecutor {

    override suspend fun execute(name: String, arguments: JsonNode): ToolResult {
        val token = tokenProvider()
            ?: return ToolResult.rejected("The overlay is not signed in. Ask the user to sign in first.")
        return try {
            val result = backend.callTool(token, name, arguments)
            ToolResult(status = result.status, output = result.output, message = result.message)
                .also { onResult(name, it) }
        } catch (error: UnauthorizedException) {
            ToolResult.rejected("The overlay session expired. Ask the user to sign in again.")
        } catch (error: BackendException) {
            log.warn("Инструмент {} не отработал: {}", name, error.message)
            // Сессию не рвём: ассистент проговорит ошибку и продолжит разговор.
            ToolResult.failed(error.message)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(BackendToolExecutor::class.java)
    }
}
