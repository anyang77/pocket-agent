package dev.pocketagent.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit

data class CommandResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
    val durationMs: Long,
    val error: String? = null,
)

class CommandRunner(
    private val rootDetector: RootDetector = RootDetector(),
    private val internalRuntime: TermuxRuntime? = null,
) {
    @Volatile
    private var activeProcess: Process? = null
    @Volatile
    private var activeSuBinary: File? = null

    suspend fun run(
        command: String,
        workingDir: File,
        asRoot: Boolean = false,
        useInternalRuntime: Boolean = true,
        timeoutMs: Long = 120_000,
        extraEnv: Map<String, String> = emptyMap(),
        onOutput: (String) -> Unit = {},
    ): CommandResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val effectiveRoot = asRoot
        if (!effectiveRoot && useInternalRuntime && internalRuntime?.isBaseReady() == true) {
            return@withContext internalRuntime.run(
                command = command,
                workingDir = workingDir,
                timeoutMs = timeoutMs,
                extraEnv = extraEnv,
                onOutput = onOutput,
            )
        }
        val su = if (effectiveRoot) {
            rootDetector.findSuBinary()
                ?: return@withContext CommandResult(
                    exitCode = -1,
                    output = "",
                    timedOut = false,
                    durationMs = 0,
                    error = "没有找到可用的 su",
                )
        } else {
            null
        }

        val processBuilder = if (effectiveRoot) {
            ProcessBuilder(
                su!!.absolutePath,
                "-c",
                buildRootCommand(command, workingDir, extraEnv, useInternalRuntime),
            )
        } else {
            ProcessBuilder("/system/bin/sh", "-lc", command)
        }

        val processDirectory = if (
            workingDir.isDirectory && (workingDir.canRead() || !effectiveRoot)
        ) {
            workingDir
        } else {
            internalRuntime?.home ?: File("/")
        }
        processBuilder
            .directory(processDirectory)
            .redirectErrorStream(true)

        processBuilder.environment().apply {
            put("HOME", internalRuntime?.home?.absolutePath ?: workingDir.absolutePath)
            put("TERM", "xterm-256color")
            if (get("PATH") == null) {
                put("PATH", "/product/bin:/apex/com.android.runtime/bin:/system/bin:/system/xbin")
            }
            putAll(extraEnv)
        }

        val process = try {
            processBuilder.start()
        } catch (error: Exception) {
            if (effectiveRoot && internalRuntime?.isReady() == true) {
                val fallback = internalRuntime.run(
                    command = command,
                    workingDir = workingDir,
                    timeoutMs = timeoutMs,
                    extraEnv = extraEnv,
                    onOutput = onOutput,
                )
                return@withContext fallback.copy(
                    output = "[Root 启动失败，已自动降级为普通 UID]\n" + fallback.output,
                    error = fallback.error,
                )
            }
            return@withContext CommandResult(
                exitCode = -1,
                output = "",
                timedOut = false,
                durationMs = System.currentTimeMillis() - start,
                error = error.message ?: "无法启动命令",
            )
        }

        activeProcess = process
        activeSuBinary = if (effectiveRoot) su else null
        val captured = StringBuilder()
        try {
            coroutineScope {
                val reader = async {
                    BufferedReader(process.inputStream.reader()).useLines { lines ->
                        lines.forEach { line ->
                            if (captured.length < MAX_CAPTURE_CHARS) {
                                captured.appendLine(line)
                            }
                            runCatching { onOutput("$line\n") }
                        }
                    }
                }

                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroy()
                    process.waitFor(1, TimeUnit.SECONDS)
                    if (process.isAlive) process.destroyForcibly()
                }
                reader.await()

                CommandResult(
                    exitCode = if (finished) process.exitValue() else -1,
                    output = captured.toString(),
                    timedOut = !finished,
                    durationMs = System.currentTimeMillis() - start,
                    error = if (finished) null else "命令执行超时",
                )
            }
        } finally {
            if (activeProcess === process) {
                activeProcess = null
                activeSuBinary = null
            }
            if (process.isAlive) process.destroyForcibly()
        }
    }

    fun cancel() {
        internalRuntime?.cancel()
        activeProcess?.let {
            terminateProcessTreeAsync(it, activeSuBinary)
        }
    }

    private fun buildRootCommand(
        command: String,
        workingDir: File,
        extraEnv: Map<String, String>,
        useInternalRuntime: Boolean,
    ): String {
        val runtime = internalRuntime?.takeIf { useInternalRuntime } ?: return command
        if (!runtime.isReady()) return command
        val environment = (runtime.environmentArray() + extraEnv.map { "${it.key}=${it.value}" })
            .joinToString(" ") { shellQuote(it) }
        val rootedCommand = "cd -- ${shellQuote(workingDir.absolutePath)} && " +
            "exec ${runtime.prefix.absolutePath}/bin/env -i $environment " +
            runtime.shellInvocation(command)
        return "exec ${runtime.shellInvocation(rootedCommand)}"
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    companion object {
        private const val MAX_CAPTURE_CHARS = 1_048_576
    }
}
