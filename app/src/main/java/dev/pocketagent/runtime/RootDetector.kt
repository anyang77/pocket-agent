package dev.pocketagent.runtime

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit

data class RootProbe(
    val granted: Boolean = false,
    val uid: Int? = null,
    val canReadAppData: Boolean = false,
    val chrootAvailable: Boolean = false,
    val mountAvailable: Boolean = false,
    val error: String? = null,
)

class RootDetector {
    suspend fun probe(): RootProbe = withContext(Dispatchers.IO) {
        val su = findSuBinary()
            ?: return@withContext RootProbe(error = "没有找到可用的 su")

        val script = """
            echo UID=${'$'}(id -u)
            if [ -r /data/data ]; then echo DATA=1; else echo DATA=0; fi
            if command -v chroot >/dev/null 2>&1; then echo CHROOT=1; else echo CHROOT=0; fi
            if command -v mount >/dev/null 2>&1; then echo MOUNT=1; else echo MOUNT=0; fi
        """.trimIndent()

        var process: Process? = null
        try {
            process = ProcessBuilder(su.absolutePath, "-c", script)
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()
            val reader = ProcessReader(process, output)
            val finished = process.waitFor(25, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext RootProbe(error = "root 授权超时")
            }
            reader.join(1_000)

            val text = output.toString()
            val uid = Regex("""UID=(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            RootProbe(
                granted = uid == 0,
                uid = uid,
                canReadAppData = text.contains("DATA=1"),
                chrootAvailable = text.contains("CHROOT=1"),
                mountAvailable = text.contains("MOUNT=1"),
                error = if (uid == 0) null else "root 未授权或无输出",
            )
        } catch (error: Exception) {
            RootProbe(error = error.message ?: "root 探测失败")
        } finally {
            process?.let {
                if (it.isAlive) it.destroyForcibly()
            }
        }
    }

    fun findSuBinary(): File? = SU_CANDIDATES
        .asSequence()
        .map(::File)
        .firstOrNull { it.canExecute() }

    private class ProcessReader(
        process: Process,
        private val output: StringBuilder,
    ) {
        private val thread = Thread {
            runCatching {
                BufferedReader(process.inputStream.reader()).useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            output.appendLine(line)
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        fun join(timeoutMs: Long) {
            thread.join(timeoutMs)
        }
    }

    companion object {
        private val SU_CANDIDATES = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add("/debug_ramdisk/su")
            }
            add("/system/bin/su")
            add("/system/xbin/su")
            add("/sbin/su")
            add("/su/bin/su")
            add("/data/adb/ksu/bin/su")
        }
    }
}
