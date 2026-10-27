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
        assertTrue(prompt.contains("Termux, Android system shell, and Root are independent"))
        assertTrue(prompt.contains("pkg in Termux does not require disabling Root"))
        assertTrue(prompt.contains("call ask_user with 2–3 concrete"))
        assertTrue(prompt.contains("app returns to Pocket Agent automatically"))
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

    @Test
    fun detectsExternalAppLaunchButNotReturningToPocketAgent() {
        assertTrue(
            commandLaunchesExternalApp(
                "su -c 'am start -W -n com.example/.MainActivity'; sleep 2; am force-stop com.example",
            ),
        )
        assertTrue(
            commandLaunchesExternalApp(
                "/system/bin/monkey -p com.example -c android.intent.category.LAUNCHER 1",
            ),
        )
        assertFalse(
            commandLaunchesExternalApp(
                "am start -W -n dev.pocketagent/.MainActivity",
            ),
        )
        assertFalse(commandLaunchesExternalApp("ls -la && grep 'am start' README.md"))
    }
}
