package org.example.overlay.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolBudgetPolicyTest {

    @Test
    fun `в пределах бюджета вызовы допускаются`() {
        val policy = ToolBudgetPolicy(maxCallsPerTurn = 2, perToolCallsPerTurn = emptyMap())

        assertEquals(ToolBudgetDecision.Admit, policy.admit("searchWeapons"))
        assertEquals(ToolBudgetDecision.Admit, policy.admit("transferWeapon"))
    }

    @Test
    fun `превышение общего лимита отклоняется с текстом для модели`() {
        val policy = ToolBudgetPolicy(maxCallsPerTurn = 1, perToolCallsPerTurn = emptyMap())
        policy.admit("searchWeapons")

        val decision = assertIs<ToolBudgetDecision.Reject>(policy.admit("searchWeapons"))

        assertTrue(decision.requestTerminalResponse)
        assertTrue(decision.message.contains("limit reached"))
    }

    @Test
    fun `терминальный ответ запрашивается только один раз за ход`() {
        val policy = ToolBudgetPolicy(maxCallsPerTurn = 0, perToolCallsPerTurn = emptyMap())

        assertTrue(assertIs<ToolBudgetDecision.Reject>(policy.admit("a")).requestTerminalResponse)
        assertTrue(!assertIs<ToolBudgetDecision.Reject>(policy.admit("a")).requestTerminalResponse)
    }

    @Test
    fun `лимит на конкретный инструмент срабатывает раньше общего`() {
        val policy = ToolBudgetPolicy(maxCallsPerTurn = 10, perToolCallsPerTurn = mapOf("transferWeapon" to 1))
        policy.admit("transferWeapon")

        val decision = assertIs<ToolBudgetDecision.Reject>(policy.admit("transferWeapon"))

        assertTrue(decision.message.contains("retry limit"))
        assertEquals(ToolBudgetDecision.Admit, policy.admit("searchWeapons"))
    }

    @Test
    fun `новый ход обнуляет бюджет`() {
        val policy = ToolBudgetPolicy(maxCallsPerTurn = 1, perToolCallsPerTurn = emptyMap())
        policy.admit("a")
        assertIs<ToolBudgetDecision.Reject>(policy.admit("a"))

        policy.resetTurn()

        assertEquals(ToolBudgetDecision.Admit, policy.admit("a"))
    }
}
