package dev.pocketagent.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UnifiedTerminalShellTest {
    @Test fun quoteEscapesSpacesAndSingleQuotes() {
        assertEquals("'a'\"'\"'b c'", UnifiedTerminalShell.quote("a'b c"))
    }

    @Test fun elevationRebuildsEnvironmentWithoutExecPreload() {
        val rc = UnifiedTerminalShell.rc(arrayOf("HOME=/home/test", "PATH=/tools/bin:/system/bin",
            "LD_PRELOAD=/tools/lib/preload.so", "TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE=force"),
            "/tools/bin/bash", "/home/test/terminal rc", "/system/bin/su")
        assertTrue(rc.contains("PATH=/tools/bin:/system/bin"))
        assertFalse(rc.contains("preload.so"))
        assertFalse(rc.contains("MODE=force"))
        assertTrue(rc.contains("unset LD_PRELOAD LD_LIBRARY_PATH"))
        assertTrue(rc.contains("printf -v root_command '%s %q'"))
        assertTrue(rc.contains("cd -- \"\$1\" || exit"))
        assertTrue(rc.contains(".bash_history_root"))
        assertTrue(rc.contains("\"\$@\""))
    }

    @Test fun agentElevationPreservesCwdAndEscapesCommand() {
        val command = UnifiedTerminalShell.rootCommand(
            arrayOf("HOME=/shared/home", "PATH=/tools/bin:/system/bin", "LD_PRELOAD=unsafe.so"),
            "/tools/bin/bash", "/sdcard/a'b c", "printf '%s' hello",
        )
        assertTrue(command.contains("export 'HOME=/shared/home'"))
        assertTrue(command.contains(UnifiedTerminalShell.quote("/sdcard/a'b c")))
        assertTrue(command.contains(UnifiedTerminalShell.quote("printf '%s' hello")))
        assertFalse(command.contains("unsafe.so"))
    }

    @Test fun noSuReportsUnavailableInsteadOfRecursing() {
        val rc = UnifiedTerminalShell.rc(emptyArray(), "/bin/bash", "/tmp/rc", null)
        assertTrue(rc.contains("return 127"))
    }

    @Test fun writeDeviceFixtureWhenRequested() {
        val path = System.getProperty("codemate.terminal.fixture") ?: return
        val prefix = System.getenv("PREFIX")
        val home = System.getenv("HOME")
        File(path).writeText(UnifiedTerminalShell.rc(arrayOf("PREFIX=$prefix", "HOME=$home",
            "PATH=$prefix/bin:/system/bin:/system/xbin", "LD_LIBRARY_PATH=$prefix/lib",
            "TMPDIR=$prefix/tmp"), "$prefix/bin/bash", path, "/system/bin/su"))
    }
}
