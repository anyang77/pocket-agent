package dev.pocketagent.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SystemPathType {
    FILE,
    DIRECTORY,
}

data class SystemPathDecision(
    val allowed: Boolean,
    val normalizedPath: String,
    val reason: String? = null,
)

object SystemReadPolicy {
    private val noRootAllowedRoots = listOf(
        "/system",
        "/vendor",
        "/product",
        "/odm",
        "/apex",
        "/proc",
        "/sys",
    )

    private val unsafeSpecialFiles = listOf(
        Regex("""^/proc/kcore$"""),
        Regex("""^/proc/\d+/(mem|pagemap)$"""),
    )

    fun check(path: String, rootGranted: Boolean, type: SystemPathType): SystemPathDecision {
        val raw = path.trim()
        if (raw.isEmpty()) {
            return SystemPathDecision(false, raw, "路径不能为空")
        }
        if (!raw.startsWith("/")) {
            return SystemPathDecision(false, raw, "系统路径必须是绝对路径")
        }
        if (raw.indexOf('\u0000') >= 0) {
            return SystemPathDecision(false, raw, "路径包含非法字符")
        }

        val normalized = runCatching {
            File(raw).toPath().normalize().toString()
        }.getOrElse {
            return SystemPathDecision(false, raw, "路径无效")
        }
        if (!normalized.startsWith("/")) {
            return SystemPathDecision(false, normalized, "路径无效")
        }
        if (type == SystemPathType.FILE) {
            if (normalized == "/dev" || normalized.startsWith("/dev/")) {
                return SystemPathDecision(false, normalized, "不能读取设备节点或字符设备")
            }
            if (unsafeSpecialFiles.any { it.matches(normalized) }) {
                return SystemPathDecision(false, normalized, "该伪文件可能阻塞或泄露进程内存")
            }
        }
        if (!rootGranted) {
            val readable = noRootAllowedRoots.any { root ->
                normalized == root || normalized.startsWith("$root/")
            }
            if (!readable) {
                return SystemPathDecision(
                    false,
                    normalized,
                    "无 Root 时只允许读取公共系统目录；该路径需要 Root 授权",
                )
            }
        }
        return SystemPathDecision(true, normalized)
    }
}

data class SystemReadResult(
    val output: String,
    val usedRoot: Boolean,
)

class SystemAccessRepository(
    context: Context,
    private val commandRunner: CommandRunner,
    private val useRoot: () -> Boolean,
) {
    private val appContext = context.applicationContext

    suspend fun deviceInfo(): String = withContext(Dispatchers.IO) {
        val runtime = Runtime.getRuntime()
        val activityManager = appContext.getSystemService(ActivityManager::class.java)
        val batteryManager = appContext.getSystemService(BatteryManager::class.java)
        val dataStat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val appStat = runCatching { StatFs(appContext.filesDir.path) }.getOrNull()
        val metrics = appContext.resources.displayMetrics
        val memInfo = readSmallText("/proc/meminfo")
        val cpuInfo = readSmallText("/proc/cpuinfo")
        val kernelVersion = readSmallText("/proc/version").lineSequence().firstOrNull().orEmpty()
        val selinux = readSmallText("/sys/fs/selinux/enforce").trim().let {
            when (it) {
                "1" -> "enforcing"
                "0" -> "permissive"
                else -> "未知"
            }
        }

        buildString {
            appendLine("设备")
            appendLine("manufacturer: ${Build.MANUFACTURER}")
            appendLine("brand: ${Build.BRAND}")
            appendLine("model: ${Build.MODEL}")
            appendLine("device: ${Build.DEVICE}")
            appendLine("product: ${Build.PRODUCT}")
            appendLine("board: ${Build.BOARD}")
            appendLine("hardware: ${Build.HARDWARE}")
            appendLine()
            appendLine("Android")
            appendLine("release: ${Build.VERSION.RELEASE}")
            appendLine("sdk: ${Build.VERSION.SDK_INT}")
            appendLine("security_patch: ${Build.VERSION.SECURITY_PATCH.ifBlank { "未知" }}")
            appendLine("build_id: ${Build.ID}")
            appendLine("build_type: ${Build.TYPE}")
            appendLine("fingerprint: ${Build.FINGERPRINT}")
            appendLine()
            appendLine("处理器与内存")
            appendLine("abis: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("cpu_cores: ${runtime.availableProcessors()}")
            appendLine("kernel: ${kernelVersion.ifBlank { System.getProperty("os.version").orEmpty() }}")
            appendLine("memory_class_mb: ${activityManager?.memoryClass ?: -1}")
            appendLine("large_memory_class_mb: ${activityManager?.largeMemoryClass ?: -1}")
            appendLine(
                "runtime_memory_mb: used=${(runtime.totalMemory() - runtime.freeMemory()) / MIB} " +
                    "total=${runtime.totalMemory() / MIB} max=${runtime.maxMemory() / MIB}",
            )
            fieldValue(memInfo, "MemTotal")?.let { appendLine("mem_total: $it") }
            fieldValue(memInfo, "MemAvailable")?.let { appendLine("mem_available: $it") }
            fieldValue(memInfo, "SwapTotal")?.let { appendLine("swap_total: $it") }
            cpuInfo.lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("Hardware", ignoreCase = true) }
                ?.let { appendLine("cpu_hardware: ${it.substringAfter(':').trim()}") }
            appendLine()
            appendLine("存储")
            dataStat?.let {
                appendLine(
                    "data: total=${formatBytes(it.totalBytes)} available=${formatBytes(it.availableBytes)}",
                )
            }
            appStat?.let {
                appendLine(
                    "app_storage: total=${formatBytes(it.totalBytes)} available=${formatBytes(it.availableBytes)}",
                )
            }
            appendLine()
            appendLine("显示与电源")
            appendLine(
                "display: ${metrics.widthPixels}x${metrics.heightPixels} " +
                    "density=${metrics.density} scaled_density=${metrics.scaledDensity}",
            )
            if (batteryManager != null) {
                val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (battery in 0..100) appendLine("battery_percent: $battery")
            }
            appendLine()
            appendLine("运行环境")
            appendLine("package: ${appContext.packageName}")
            appendLine("process_uid: ${android.os.Process.myUid()}")
            appendLine("root_granted: ${useRoot()}")
            appendLine("selinux: $selinux")
            appendLine("locale: ${Locale.getDefault().toLanguageTag()}")
            appendLine("timezone: ${java.util.TimeZone.getDefault().id}")
            appendLine(
                "time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}",
            )
        }.trimEnd()
    }

    suspend fun list(path: String, rootGranted: Boolean): SystemReadResult {
        val decision = SystemReadPolicy.check(path, rootGranted, SystemPathType.DIRECTORY)
        require(decision.allowed) { decision.reason ?: "不能读取该目录" }
        if (rootGranted) {
            val result = commandRunner.run(
                command = "ls -la -- ${shellQuote(decision.normalizedPath)}",
                workingDir = appContext.filesDir,
                asRoot = true,
                timeoutMs = 20_000,
            )
            require(!result.timedOut) { "读取系统目录超时" }
            require(result.exitCode == 0) {
                result.output.ifBlank { result.error ?: "读取系统目录失败" }
            }
            return SystemReadResult(result.output.take(MAX_OUTPUT_CHARS), usedRoot = true)
        }

        val directory = File(decision.normalizedPath)
        require(directory.isDirectory) { "目录不存在: ${decision.normalizedPath}" }
        require(directory.canRead()) { "系统拒绝读取该目录" }
        val files = directory.listFiles()
            ?: throw IllegalStateException("系统拒绝列出该目录")
        val output = files
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
            .take(MAX_LIST_ENTRIES)
            .joinToString("\n") { file ->
                val type = when {
                    file.isDirectory -> "dir "
                    file.isFile -> "file"
                    else -> "link"
                }
                "$type ${file.length().toString().padStart(12)} ${file.name}"
            }
        return SystemReadResult(output.ifBlank { "(目录为空)" }, usedRoot = false)
    }

    suspend fun read(path: String, rootGranted: Boolean): SystemReadResult {
        val decision = SystemReadPolicy.check(path, rootGranted, SystemPathType.FILE)
        require(decision.allowed) { decision.reason ?: "不能读取该文件" }
        if (rootGranted) {
            val result = commandRunner.run(
                command = "head -c ${MAX_READ_BYTES + 1} -- ${shellQuote(decision.normalizedPath)}",
                workingDir = appContext.filesDir,
                asRoot = true,
                timeoutMs = 20_000,
            )
            require(!result.timedOut) { "读取系统文件超时" }
            require(result.exitCode == 0) {
                result.output.ifBlank { result.error ?: "读取系统文件失败" }
            }
            require(result.output.length <= MAX_OUTPUT_CHARS) {
                "文件大于 ${MAX_OUTPUT_CHARS} 字符，已停止读取"
            }
            require(!result.output.contains('\u0000')) { "这是二进制文件，不能作为文本读取" }
            return SystemReadResult(result.output, usedRoot = true)
        }

        val file = File(decision.normalizedPath)
        require(file.isFile) { "文件不存在: ${decision.normalizedPath}" }
        require(file.canRead()) { "系统拒绝读取该文件" }
        val bytes = ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (bytes.size() <= MAX_READ_BYTES) {
                val count = input.read(buffer)
                if (count < 0) break
                bytes.write(buffer, 0, count)
            }
        }
        val raw = bytes.toByteArray()
        require(raw.size <= MAX_READ_BYTES) {
            "文件大于 $MAX_READ_BYTES 字节，已停止读取"
        }
        require(raw.none { it == 0.toByte() }) { "这是二进制文件，不能作为文本读取" }
        return SystemReadResult(
            raw.toString(StandardCharsets.UTF_8),
            usedRoot = false,
        )
    }

    private fun readSmallText(path: String): String = runCatching {
        File(path).takeIf { it.isFile && it.canRead() }
            ?.readText()
            ?.take(MAX_INFO_SOURCE_CHARS)
            .orEmpty()
    }.getOrDefault("")

    private fun fieldValue(text: String, key: String): String? =
        text.lineSequence()
            .firstOrNull { it.startsWith("$key:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun formatBytes(bytes: Long): String {
        val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.US, "%.2f GiB", gib)
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    companion object {
        private const val MIB = 1024L * 1024L
        private const val MAX_READ_BYTES = 160 * 1024
        private const val MAX_OUTPUT_CHARS = 80_000
        private const val MAX_INFO_SOURCE_CHARS = 64_000
        private const val MAX_LIST_ENTRIES = 300
    }
}
