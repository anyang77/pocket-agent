package dev.pocketagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTaskModeTest {
    @Test fun planningAndReviewRejectEverySideEffectAndExtension() {
        val blocked = listOf("write_file", "edit_file", "run_command", "spawn_subagent",
            "load_skill", "mcp_server_tool", "read_system_file", "list_system_dir", "unknown")
        for (mode in listOf(AgentTaskMode.PLAN, AgentTaskMode.REVIEW)) {
            blocked.forEach { assertFalse("$mode allowed $it", mode.allows(it)) }
            listOf("list_files", "read_file", "search_files", "update_plan", "ask_user")
                .forEach { assertTrue(mode.allows(it)) }
        }
    }

    @Test fun initAllowsOnlyTheGuardedWriterBeyondReadOnlyTools() {
        assertTrue(AgentTaskMode.INIT.allows("write_file"))
        listOf("edit_file", "run_command", "mcp_write", "spawn_subagent")
            .forEach { assertFalse(AgentTaskMode.INIT.allows(it)) }
    }

    @Test fun projectSetupRejectsAbsoluteNestedAndTraversingPaths() {
        assertTrue(isProjectInstructionsPath("AGENTS.md"))
        assertTrue(isProjectInstructionsPath("./AGENTS.md"))
        listOf("/AGENTS.md", "../AGENTS.md", "src/AGENTS.md", "AGENTS.md/../other", "", "agents.md")
            .forEach { assertFalse(it, isProjectInstructionsPath(it)) }
    }

    @Test fun queueRestorationPreservesModesAndFailsSafelyForUnknownValues() {
        AgentTaskMode.entries.forEach { assertEquals(it, AgentTaskMode.restored(it.name)) }
        assertEquals(AgentTaskMode.EXECUTE, AgentTaskMode.restored(null))
        assertEquals(AgentTaskMode.EXECUTE, AgentTaskMode.restored(""))
        assertEquals(AgentTaskMode.PLAN, AgentTaskMode.restored("unknown-future-mode"))
    }

    @Test fun normalExecutionKeepsExistingCapabilities() {
        listOf("write_file", "edit_file", "run_command", "mcp_custom", "spawn_subagent")
            .forEach { assertTrue(AgentTaskMode.EXECUTE.allows(it)) }
    }

    @Test fun diffShowsExactReplacementAndOmitsUnchangedText() {
        val diff = projectInstructionsDiff("title\nold\nend", "title\nnew\nend")
        assertTrue(diff.contains("-old\n+new\n"))
        assertTrue(diff.contains("第 2 行"))
        assertFalse(diff.contains("-title"))
        assertFalse(diff.contains("+end"))
    }

    @Test fun diffSupportsCreationDeletionAndLineEndChanges() {
        assertTrue(projectInstructionsDiff("", "hello").contains("+hello"))
        assertTrue(projectInstructionsDiff("hello", "").contains("-hello"))
        assertTrue(projectInstructionsDiff("hello\n", "hello").contains("-\n"))
        assertEquals("--- AGENTS.md (原内容)\n+++ AGENTS.md (建议内容)\n@@ 从第 2 行替换 @@\n",
            projectInstructionsDiff("a", "a"))
    }
}
