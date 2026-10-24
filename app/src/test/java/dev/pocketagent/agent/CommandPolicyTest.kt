package dev.pocketagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPolicyTest {
    @Test
    fun allowsBuildAndTestCommands() {
        assertTrue(CommandPolicy.check("pytest").allowed)
        assertTrue(CommandPolicy.check("python3 -m pytest").allowed)
        assertTrue(CommandPolicy.check("npm run build").allowed)
        assertTrue(CommandPolicy.check("cargo test").allowed)
        assertTrue(CommandPolicy.check("git diff").allowed)
    }

    @Test
    fun allowsDestructiveAndRootCommandsForApproval() {
        assertTrue(CommandPolicy.check("rm -rf .").allowed)
        assertTrue(CommandPolicy.check("su -c id").allowed)
        assertTrue(CommandPolicy.check("mount -o bind / /mnt").allowed)
        assertTrue(CommandPolicy.check("git reset --hard").allowed)
    }

    @Test
    fun allowsCompleteAndroidShellSyntax() {
        assertTrue(CommandPolicy.check("pytest; rm -rf src").allowed)
        assertTrue(CommandPolicy.check("cat ../../secret").allowed)
        assertTrue(CommandPolicy.check("pytest | tee output").allowed)
        assertTrue(CommandPolicy.check("curl https://example.com").allowed)
        assertTrue(CommandPolicy.check("pm list packages | sort > packages.txt").allowed)
    }

    @Test
    fun classifiesCommandsForApproval() {
        assertEquals(CommandRisk.SAFE, CommandPolicy.assess("pytest").risk)
        assertEquals(
            CommandRisk.NETWORK,
            CommandPolicy.assess("npm install").risk,
        )
        assertEquals(
            CommandRisk.DESTRUCTIVE,
            CommandPolicy.assess("rm notes.txt").risk,
        )
        assertEquals(
            CommandRisk.BLOCKED,
            CommandPolicy.assess("  ").risk,
        )
        assertEquals(
            CommandRisk.DESTRUCTIVE,
            CommandPolicy.assess("pm uninstall com.example.app").risk,
        )
    }
}
