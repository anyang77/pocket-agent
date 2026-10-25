package dev.pocketagent.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class ProcessCancellationTest {
    @Test
    fun killsShellAndDescendantProcesses() {
        val process = ProcessBuilder("sh", "-c", "sleep 30 & wait").start()
        Thread.sleep(250)
        val processPid = process.pidOrNull()
        assertNotNull(processPid)
        val descendantPid = ProcessBuilder("pgrep", "-P", processPid.toString())
            .start()
            .inputStream
            .bufferedReader()
            .readLine()
            ?.trim()
            ?.toLongOrNull()

        terminateProcessTree(process, null, File("/bin/sh"))

        assertTrue(process.waitFor(2, TimeUnit.SECONDS))
        assertFalse(process.isAlive)
        if (descendantPid != null) {
            repeat(20) {
                val alive = ProcessBuilder("kill", "-0", descendantPid.toString())
                    .start()
                    .waitFor()
                if (alive != 0) return@repeat
                Thread.sleep(50)
            }
            val stillAlive = ProcessBuilder("kill", "-0", descendantPid.toString())
                .start()
                .waitFor()
            assertFalse(stillAlive == 0)
        }
    }
}
