package dev.pocketagent.agent

import org.junit.Assert.*
import org.junit.Test

class UserChoiceTest {
    @Test fun customInputIsTrimmedAndIdentifiedSeparatelyFromOptions() {
        val choice = customUserChoice("  保留对话，同时展示文件  ")!!
        assertTrue(choice.isCustom)
        assertEquals("保留对话，同时展示文件", choice.detail)
        assertFalse(AgentChoice("__custom__", "模型提供的选项").isCustom)
    }

    @Test fun emptyOrOversizedInputCannotBeSubmitted() {
        assertNull(customUserChoice(" \n\t"))
        assertNull(customUserChoice("a".repeat(4001)))
        assertNotNull(customUserChoice("a".repeat(4000)))
    }

    @Test fun confirmationQuestionsDoNotAllowCustomInputByDefault() {
        assertFalse(UserQuestion("确认写入？", emptyList()).allowCustomInput)
        assertTrue(UserQuestion("选哪个方向？", emptyList(), allowCustomInput = true).allowCustomInput)
    }
}
