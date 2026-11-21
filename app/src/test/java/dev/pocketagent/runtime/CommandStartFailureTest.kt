package dev.pocketagent.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CommandStartFailureTest {
    @Test fun oversizedRootArgumentsAreNotReportedAsMissingRoot() {
        val result = commandStartFailure(IOException("Cannot run program su: error=7, Argument list too long"), true, 133)
        assertEquals(-1, result.exitCode)
        assertEquals(133L, result.durationMs)
        assertFalse(result.timedOut)
        assertEquals("", result.output)
        assertTrue(result.error.orEmpty().contains("E2BIG"))
        assertTrue(result.error.orEmpty().contains("不是 Root 未授权"))
        assertTrue(result.error.orEmpty().contains("未降级、未重试"))
        assertTrue(result.error.orEmpty().contains("error=7"))
    }

    @Test fun errnoInNestedCauseIsPreservedAndClassified() {
        val result = commandStartFailure(IOException("launch failed", IOException("EACCES")), true, 1)
        assertTrue(result.error.orEmpty().contains("SELinux"))
        assertTrue(result.error.orEmpty().contains("launch failed"))
        assertTrue(result.error.orEmpty().contains("EACCES"))
    }

    @Test fun genericAndOrdinaryUserErrorsDoNotInventPermissionLoss() {
        val root = commandStartFailure(IOException("Resource temporarily unavailable"), true, 2)
        assertTrue(root.error.orEmpty().contains("Resource temporarily unavailable"))
        val normal = commandStartFailure(IOException("No such file"), false, 3)
        assertFalse(normal.error.orEmpty().contains("Root"))
        assertTrue(normal.error.orEmpty().contains("No such file"))
    }

    @Test fun suLauncherDropsLoadersButRetainsToolchainAndAuthentication() {
        val environment = mutableMapOf("LD_PRELOAD" to "app.so", "LD_LIBRARY_PATH" to "/app/lib",
            "TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE" to "force", "PATH" to "/tools/bin:/system/bin",
            "HOME" to "/app/home", "GIT_ASKPASS" to "/app/askpass")
        sanitizeRootLauncherEnvironment(environment)
        assertFalse(environment.containsKey("LD_PRELOAD"))
        assertFalse(environment.containsKey("LD_LIBRARY_PATH"))
        assertFalse(environment.keys.any { it.startsWith("TERMUX_EXEC__") })
        assertEquals("/tools/bin:/system/bin", environment["PATH"])
        assertEquals("/app/home", environment["HOME"])
        assertEquals("/app/askpass", environment["GIT_ASKPASS"])
    }
}
