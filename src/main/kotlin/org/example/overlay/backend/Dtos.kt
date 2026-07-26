package org.example.overlay.backend

import tools.jackson.databind.JsonNode

/**
 * Контракт бэкенда `mcp-destiny2-client` (§3.1 плана). Поля описаны ровно так, как приходят
 * по проводу: время — строкой ISO-8601, разбор в Instant делает вызывающий код.
 */
data class LoginResponse(
    val token: String,
    val expiresAt: String,
)

data class Profile(
    val name: String,
    val bungieLinked: Boolean,
    val bungieProfile: BungieProfile? = null,
)

data class BungieProfile(
    val bungieMembershipId: String? = null,
    val primaryMembershipId: String? = null,
    val primaryMembershipType: Int? = null,
    val uniqueName: String? = null,
    val displayName: String? = null,
)

data class ToolInfo(
    val name: String,
    val description: String? = null,
    /**
     * Подсказка сервера о характере инструмента. UI на неё не опирается: у `transferWeapon`
     * она врёт (риск 9 плана) — список изменяющих инструментов ведём свой.
     */
    val executionMode: String? = null,
    val parameters: JsonNode? = null,
)

/**
 * Ответ `POST /tools/{name}`. Статусы совпадают с донорским `ToolResultStatus` один в один,
 * поэтому конвертация не нужна — строка уезжает в `function_call_output` как есть (§3.3).
 */
data class ToolCallResult(
    val status: String,
    val output: JsonNode? = null,
    val message: String? = null,
) {
    val isSuccess: Boolean get() = status == STATUS_SUCCESS

    companion object {
        const val STATUS_SUCCESS = "SUCCESS"
    }
}
