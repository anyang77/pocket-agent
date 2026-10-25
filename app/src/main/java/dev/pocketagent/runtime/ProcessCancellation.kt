package dev.pocketagent.runtime

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal fun terminateProcessTreeAsync(
    process: Process,
    suBinary: File?,
) {
    thread(isDaemon = true, name = "terminate-process-tree") {
        terminateProcessTree(process, suBinary)
    }
}

internal fun terminateProcessTree(
    process: Process,
    suBinary: File?,
    systemShell: File = File("/system/bin/sh"),
) {
    if (!process.isAlive) return
    val pid = process.pidOrNull() ?: run {
        process.destroy()
        if (process.isAlive) process.destroyForcibly()
        return
    }
    val binDir = systemShell.absoluteFile.parentFile?.absolutePath ?: "/system/bin"
    val script = """
        kill_tree() {
            for child in $($binDir/pgrep -P "${'$'}1" 2>/dev/null); do
                kill_tree "${'$'}child"
            done
            $binDir/kill -9 "${'$'}1" 2>/dev/null || true
        }
        kill_tree $pid
    """.trimIndent()
    val killer = runCatching {
        val builder = if (suBinary != null && suBinary.isFile) {
            ProcessBuilder(suBinary.absolutePath, "-c", script)
        } else {
            ProcessBuilder(systemShell.absolutePath, "-c", script)
        }
        builder.start()
    }.getOrNull()
    runCatching { killer?.waitFor(2, TimeUnit.SECONDS) }
    if (process.isAlive) {
        process.destroy()
        if (process.isAlive) process.destroyForcibly()
    }
}

internal fun Process.pidOrNull(): Long? {
    Regex("""pid=(\d+)""").find(toString())
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()
        ?.let { return it }
    var type: Class<*>? = javaClass
    while (type != null) {
        type.declaredFields.firstOrNull { it.name == "pid" || it.name == "mPid" }
            ?.let { field ->
                return runCatching {
                    field.isAccessible = true
                    (field.get(this) as? Number)?.toLong()
                }.getOrNull()
            }
        type = type.superclass
    }
    return null
}
