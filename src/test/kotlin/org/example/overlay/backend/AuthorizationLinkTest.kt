package org.example.overlay.backend

import org.example.overlay.tools.ToolResult
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthorizationLinkTest {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `ссылка достаётся из строкового output - именно так её отдаёт бэкенд`() {
        // Риск 5 плана: authorize возвращает голую строку, а не объект с полем url.
        val result = ToolResult(
            status = ToolResult.SUCCESS,
            output = mapper.valueToTree("https://www.bungie.net/en/OAuth/Authorize?client_id=123&state=abc"),
        )

        assertEquals(
            "https://www.bungie.net/en/OAuth/Authorize?client_id=123&state=abc",
            AuthorizationLink.extract(result),
        )
    }

    @Test
    fun `ссылка находится и внутри текста`() {
        val result = ToolResult(
            status = ToolResult.SUCCESS,
            output = mapper.valueToTree("Open https://bungie.net/authorize?x=1 and come back"),
        )

        assertEquals("https://bungie.net/authorize?x=1", AuthorizationLink.extract(result))
    }

    @Test
    fun `ссылка достаётся из объекта, если бэкенд однажды начнёт слать объект`() {
        val output = mapper.createObjectNode().put("url", "https://bungie.net/authorize")

        assertEquals("https://bungie.net/authorize", AuthorizationLink.extract(ToolResult(ToolResult.SUCCESS, output)))
    }

    @Test
    fun `ссылка берётся из message, если output пуст`() {
        val result = ToolResult(ToolResult.FAILED, message = "Go to https://bungie.net/authorize first")

        assertEquals("https://bungie.net/authorize", AuthorizationLink.extract(result))
    }

    @Test
    fun `без ссылки возвращается null`() {
        assertNull(AuthorizationLink.extract(ToolResult(ToolResult.FAILED, message = "нет ссылки")))
        assertNull(AuthorizationLink.extract(null))
    }

    @Test
    fun `подсказка о повторной привязке распознаётся`() {
        // Текст McpToolMapper бэкенда при протухшей авторизации (риск 8).
        val result = ToolResult(ToolResult.FAILED, message = "Please link the Bungie account again")

        assertTrue(AuthorizationLink.mentionsRelink(result))
        assertFalse(AuthorizationLink.mentionsRelink(ToolResult(ToolResult.SUCCESS, message = "ok")))
    }
}
