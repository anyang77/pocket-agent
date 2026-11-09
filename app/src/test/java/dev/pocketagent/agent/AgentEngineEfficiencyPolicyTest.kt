package dev.pocketagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEngineEfficiencyPolicyTest {
    @Test
    fun promptKeepsUnlimitedIterationAndOptimizesRepeatedWork() {
        val prompt = agentSystemPrompt(AgentPermissions())

        assertFalse(prompt.contains("maximum rounds", ignoreCase = true))
        assertTrue(prompt.contains("shortest reliable tool path"))
        assertTrue(prompt.contains("execute in the same agent round"))
        assertTrue(prompt.contains("DUPLICATE_TOOL_CALL_SUPPRESSED"))
        assertTrue(prompt.contains("Agent and terminal share one Android/Termux toolchain"))
        assertTrue(prompt.contains("root changes UID via su, not the toolchain or cwd"))
        assertTrue(prompt.contains("Keep package installation and builds at normal UID"))
        assertTrue(prompt.contains("call ask_user with 2–3 concrete"))
        assertTrue(prompt.contains("keep the tested app in the foreground"))
    }

    @Test
    fun selectsRootOnlyForAndroidAppControlCommands() {
        assertTrue(commandNeedsRootForSystemShell("am start -W -n com.example/.MainActivity"))
        assertTrue(commandNeedsRootForSystemShell("am force-stop com.example"))
        assertTrue(commandNeedsRootForSystemShell("monkey -p com.example 1"))
        assertFalse(commandNeedsRootForSystemShell("pm list packages"))
        assertFalse(commandNeedsRootForSystemShell("su -c 'am start -W -n com.example/.MainActivity'"))
        assertFalse(commandNeedsRootForSystemShell("pkg install python"))
    }

}
