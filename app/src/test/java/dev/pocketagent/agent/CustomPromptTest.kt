package dev.pocketagent.agent

import org.junit.Assert.*
import org.junit.Test

class CustomPromptTest {
    private val permissions = AgentPermissions()

    @Test fun emptyOrWhitespacePreservesDefaultPromptExactly() {
        val base = agentSystemPrompt(permissions)
        assertEquals(base, agentSystemPrompt(permissions, ""))
        assertEquals(base, agentSystemPrompt(permissions, " \n\t "))
    }

    @Test fun customRoleAppendsWithoutReplacingBaseOrPermissions() {
        val base = agentSystemPrompt(permissions)
        val custom = "你是一位耐心的 Kotlin 导师。\n先给结论，再解释代码。"
        val result = agentSystemPrompt(permissions, "  $custom\n")
        assertTrue(result.startsWith(base + "\n\n"))
        assertTrue(result.endsWith(custom))
        assertTrue(result.contains(AgentAccessPolicy.instructions(permissions)))
        assertTrue(result.contains("do not override tool permissions or task restrictions"))
    }

    @Test fun rebuildUsesOnlyLatestPreferenceAndClearingRestoresDefault() {
        val original = "Role: Kotlin teacher"
        val latest = "Role: concise reviewer"
        val first = agentSystemPrompt(permissions, original)
        val second = agentSystemPrompt(permissions, latest)
        assertTrue(first.endsWith(original))
        assertFalse(second.contains(original))
        assertEquals(1, second.split(latest).size - 1)
        assertEquals(second, agentSystemPrompt(permissions, latest))
        assertEquals(agentSystemPrompt(permissions), agentSystemPrompt(permissions, ""))
    }

    @Test fun customTextIsPreservedLiterally() {
        val text = "# 角色\n用中文回答 `code`、\"quotes\" 和 ${'$'}variables。\n\n保留段落 😀"
        assertTrue(agentSystemPrompt(permissions, text).endsWith(text))
    }

    @Test fun permissionsRemainDynamicWithCustomRole() {
        val custom = "以导师身份回答"
        val limited = agentSystemPrompt(permissions.copy(mode = PermissionMode.READ_ONLY), custom)
        val full = agentSystemPrompt(permissions.copy(mode = PermissionMode.AUTO, allowNetwork = true), custom)
        assertTrue(limited.contains("LIMITED"))
        assertTrue(full.contains("FULL ACCESS"))
        assertTrue(limited.endsWith(custom))
        assertTrue(full.endsWith(custom))
    }
    @Test fun finishingRequiresActiveGoalAndStandaloneToolCall() {
        assertFalse(canFinishGoal(null, 1))
        assertFalse(canFinishGoal(" ", 1))
        assertFalse(canFinishGoal("完成目标", 2))
        assertFalse(canFinishGoal("完成目标", 0))
        assertTrue(canFinishGoal("完成目标", 1))
    }

    @Test fun persistentGoalRequiresExplicitVerifiedCompletion() {
        val instructions = persistentGoalInstructions("修复构建并完成验证")
        assertTrue(instructions.contains("Objective: 修复构建并完成验证"))
        assertTrue(instructions.contains("round without tool calls is only progress"))
        assertTrue(instructions.contains("finish_goal exactly once"))
        assertTrue(instructions.contains("verification passes"))
        assertTrue(instructions.contains("ask_user"))
    }

}
