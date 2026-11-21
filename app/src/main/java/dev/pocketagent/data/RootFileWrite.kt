package dev.pocketagent.data

import dev.pocketagent.runtime.CommandResult
import dev.pocketagent.runtime.UnifiedTerminalShell
import java.io.File
import java.io.FileOutputStream

/** Payload travels as bytes in app-private storage, never as a su -c argument. */
internal suspend fun writeStagedRootFile(
    cacheDir: File,
    destination: File,
    bytes: ByteArray,
    createOnly: Boolean = false,
    execute: suspend (String) -> CommandResult,
) {
    val staging = File.createTempFile("root-write-", ".bin", cacheDir)
    try {
        FileOutputStream(staging).use { stream -> stream.write(bytes); stream.fd.sync() }
        val result = execute(rootFileWriteCommand(staging, destination, createOnly))
        check(result.exitCode == 0 && !result.timedOut && result.error == null) {
            listOfNotNull("Root 文件写入失败：${destination.absolutePath}", result.error,
                result.output.takeIf { it.isNotBlank() }).joinToString(" ")
        }
    } finally {
        staging.delete()
    }
}

internal fun rootFileWriteCommand(staging: File, destination: File, createOnly: Boolean): String {
    val source = UnifiedTerminalShell.quote(staging.absolutePath)
    val target = UnifiedTerminalShell.quote(destination.absolutePath)
    val parent = UnifiedTerminalShell.quote(requireNotNull(destination.absoluteFile.parentFile).absolutePath)
    return buildString {
        append("test -f $source && test -r $source || exit 1; ")
        append("mkdir -p -- $parent || exit 1; ")
        // Shell noclobber prevents overwriting an existing entry, including symlinks.
        if (createOnly) append("set -C; ")
        // Write in place: existing ownership, mode and SELinux label stay intact.
        append("cat -- $source > $target || exit 1; ")
        append("cmp -s -- $source $target")
    }
}
