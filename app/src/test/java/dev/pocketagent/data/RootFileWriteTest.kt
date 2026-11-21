package dev.pocketagent.data

import dev.pocketagent.runtime.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class RootFileWriteTest {
    @get:Rule val temp = TemporaryFolder()
    private fun execute(command: String): CommandResult {
        val sh = if (File("/system/bin/sh").canExecute()) "/system/bin/sh" else "/bin/sh"
        val process = ProcessBuilder(sh, "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor(10, TimeUnit.SECONDS))
        return CommandResult(process.exitValue(), output, false, 0)
    }

    @Test fun largeUnicodeContentAndQuotedPathsNeverEnterCommandArguments() = runTest {
        val cache = temp.newFolder("private cache")
        val target = File(temp.root, "dir with 'quotes'/source.kt")
        val content = ("中文 ' \" shell \\$() ; & | < >\n".repeat(30000) + "last line").toByteArray(Charsets.UTF_8)
        var commandLength = 0
        writeStagedRootFile(cache, target, content) { command ->
            commandLength = command.toByteArray().size
            execute(command)
        }
        assertTrue(content.size > 131072)
        assertTrue("No payload should appear in argv", commandLength < 4096)
        assertArrayEquals(content, target.readBytes())
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test fun overwritePreservesModeAndEmptyContentIsSupported() = runTest {
        val cache = temp.newFolder()
        val target = temp.newFile("source.kt").apply { writeText("before") }
        val mode = Files.getPosixFilePermissions(target.toPath())
        writeStagedRootFile(cache, target, byteArrayOf(), execute = ::execute)
        assertEquals(0L, target.length())
        assertEquals(mode, Files.getPosixFilePermissions(target.toPath()))
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test fun createOnlyCannotOverwriteExistingFile() = runTest {
        val cache = temp.newFolder()
        val target = temp.newFile().apply { writeText("keep original") }
        try {
            writeStagedRootFile(cache, target, "replacement".toByteArray(), createOnly = true, execute = ::execute)
            fail("Existing files must not be overwritten")
        } catch (_: IllegalStateException) { }
        assertEquals("keep original", target.readText())
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test fun createOnlyWritesNewFilesButRejectsSymlinks() = runTest {
        val cache = temp.newFolder()
        val target = File(temp.root, "new-file")
        writeStagedRootFile(cache, target, "new".toByteArray(), createOnly = true, execute = ::execute)
        val link = File(temp.root, "linked-file")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        try {
            writeStagedRootFile(cache, link, "bad".toByteArray(), createOnly = true, execute = ::execute)
            fail("Symlinks must not be overwritten in create-only mode")
        } catch (_: IllegalStateException) { }
        assertEquals("new", target.readText())
    }

    @Test fun missingStagingNeverTruncatesTheOriginal() {
        val target = temp.newFile().apply { writeText("keep") }
        val command = rootFileWriteCommand(File(temp.root, "missing-stage"), target, false)
        assertNotEquals(0, execute(command).exitCode)
        assertEquals("keep", target.readText())
    }

    @Test fun failureKeepsTheOriginalErrorAndAlwaysRemovesStaging() = runTest {
        val cache = temp.newFolder()
        val target = temp.newFile().apply { writeText("keep") }
        var attempts = 0
        try {
            writeStagedRootFile(cache, target, "new".toByteArray()) {
                attempts++
                CommandResult(-1, "denied detail", false, 0, "su start failed: EACCES")
            }
            fail("Launch failures must propagate")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("EACCES"))
            assertTrue(error.message.orEmpty().contains("denied detail"))
        }
        assertEquals(1, attempts)
        assertEquals("keep", target.readText())
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellationCleansStagingWithoutReportingSuccess() = runTest {
        val cache = temp.newFolder()
        val target = temp.newFile().apply { writeText("keep") }
        try {
            writeStagedRootFile(cache, target, "new".toByteArray()) { throw CancellationException("stop") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals("keep", target.readText())
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }
}
