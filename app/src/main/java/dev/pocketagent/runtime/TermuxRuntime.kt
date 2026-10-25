package dev.pocketagent.runtime

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * A self-contained Termux-style Android/bionic userland.
 *
 * It uses the bootstrap from termux-packages, relocates scripts and symlinks
 * to this app's private prefix, and never starts or depends on the Termux app.
 */
class TermuxRuntime(context: Context) {
    private val appContext = context.applicationContext
    val runtimeRoot = File(appContext.filesDir, "termux")
    private val root = runtimeRoot
    val prefix = File(root, "usr")
    val home = File(root, "home")
    val bash = File(prefix, "bin/bash")
    private val systemLinker = sequenceOf(
        File("/system/bin/linker64"),
        File("/system/bin/linker"),
    ).firstOrNull { it.isFile && it.canExecute() }
    private val needsSystemLinker: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            appContext.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.Q &&
            systemLinker != null
    private val execPreload = File(prefix, "lib/libtermux-exec-linker-ld-preload.so")
    private val marker = File(root, ".installed")
    private val toolchainsMarker = File(root, ".toolchains-apt-v1")

    @Volatile
    private var activeProcess: Process? = null
    private val installMutex = Mutex()

    fun isBaseReady(): Boolean =
        marker.isFile &&
            marker.readText().trim().let {
                it == RUNTIME_VERSION || it.startsWith(LEGACY_RUNTIME_PREFIX)
            } &&
            isUsableProgram(bash)

    fun isToolchainsReady(): Boolean =
        isBaseReady() && requiredToolchains().all(::isUsableProgram)

    fun isGitReady(): Boolean =
        isBaseReady() && requiredGitTools().all(::isUsableProgram)

    fun isReady(): Boolean = isBaseReady() && isToolchainsReady()

    suspend fun ensureInstalled(
        onProgress: (String, Float) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isBaseReady()) {
            onProgress("解压基础运行环境", 0.03f)
            root.mkdirs()
            home.mkdirs()
            prefix.mkdirs()

            val staging = File(root, "usr.new")
            staging.deleteRecursively()
            staging.mkdirs()
            try {
                extractBootstrap(staging, onProgress)
                relocateTextAndSymlinks(staging)
                prefix.deleteRecursively()
                staging.renameTo(prefix).also { moved ->
                    check(moved) { "无法切换到新的运行环境" }
                }
                marker.writeText(RUNTIME_VERSION)
                onProgress("基础运行环境已解压", 0.34f)
            } catch (error: Exception) {
                Log.e(TAG, "Termux bootstrap install failed", error)
                staging.deleteRecursively()
                return@withContext false
            }
        } else if (marker.readText().trim() != RUNTIME_VERSION) {
            // Existing installs already contain the old bundled runtimes.
            marker.writeText(RUNTIME_VERSION)
        }
        installToolchains(onProgress = onProgress)
    }

    suspend fun installToolchains(
        force: Boolean = false,
        onProgress: (String, Float) -> Unit = { _, _ -> },
    ): Boolean = installMutex.withLock {
        withContext(Dispatchers.IO) {
        if (!isBaseReady()) return@withContext false
        ensureAptConfig()
        if (!force && isToolchainsReady()) {
            toolchainsMarker.writeText(TOOLCHAIN_VERSION)
            onProgress("Python、Node、Java、clang、ShellCheck 已就绪", 1f)
            return@withContext true
        }

        onProgress("准备国内软件源", 0.35f)
        var downloadedBytes = 0L
        var packageCount = 0
        val mirrorArray = APT_MIRRORS.joinToString(" ") { shellQuote(it.url) }
        val mirrorNames = APT_MIRRORS.joinToString(" ") { shellQuote(it.label) }
        val result = run(
            command = """
                set -e
                export DEBIAN_FRONTEND=noninteractive
                mirrors=($mirrorArray)
                mirror_names=($mirrorNames)
                selected=0
                for index in "${'$'}{!mirrors[@]}"; do
                    printf '__APT_MIRROR__:%s\n' "${'$'}{mirror_names[${'$'}index]}"
                    printf 'deb %s stable main\n' "${'$'}{mirrors[${'$'}index]}" \
                        > ${shellQuote(File(prefix, "etc/apt/sources.list").absolutePath)}
                    if apt-get update -y \
                        -o Acquire::Retries=2 \
                        -o Acquire::http::Timeout=20 \
                        -o Acquire::https::Timeout=20; then
                        selected=1
                        break
                    fi
                done
                [ "${'$'}selected" -eq 1 ] || {
                    echo "所有软件源均连接失败" >&2
                    exit 1
                }
                apt-get install --download-only -y --no-install-recommends python python-pip npm nodejs clang make git openssh shellcheck openjdk-17
                total=${'$'}(ls ${prefix.absolutePath}/var/cache/apt/archives/*.deb | wc -l)
                index=0
                for deb in ${prefix.absolutePath}/var/cache/apt/archives/*.deb; do
                    [ -f "${'$'}deb" ] || continue
                    index=${'$'}((index + 1))
                    echo "__PKG_EXTRACTING__:${'$'}index:${'$'}total:${'$'}(basename "${'$'}deb")"
                    dpkg-deb --fsys-tarfile "${'$'}deb" | tar -x --strip-components=6 -C ${shellQuote(prefix.absolutePath)}
                    echo "__PKG_EXTRACTED__:${'$'}index:${'$'}total:${'$'}(basename "${'$'}deb")"
                done
            """.trimIndent(),
            workingDir = home,
            timeoutMs = 15 * 60_000L,
            onOutput = { line ->
                when {
                    line.startsWith("__APT_MIRROR__:") -> onProgress(
                        "连接软件源 · ${line.substringAfter("__APT_MIRROR__:").trim()}",
                        0.36f,
                    )
                    line.startsWith("Get:") -> {
                        packageCount++
                        downloadedBytes += parseAptSize(line)
                        onProgress(
                            "下载工具链 · ${formatBytes(downloadedBytes)} · 第 $packageCount 个包",
                            (0.38f + packageCount * 0.016f).coerceAtMost(0.69f),
                        )
                    }
                    line.contains("Fetched ") -> onProgress(
                        "工具链下载完成 · ${formatBytes(downloadedBytes)}",
                        0.70f,
                    )
                    line.startsWith("__PKG_EXTRACTING__:") -> {
                        val parts = line.split(':')
                        val index = parts.getOrNull(1)?.toIntOrNull() ?: 0
                        val total = parts.getOrNull(2)?.toIntOrNull() ?: 1
                        val name = parts.getOrNull(3).orEmpty()
                        onProgress(
                            "解压 $name · $index/$total",
                            (0.70f + 0.22f * (index - 1).coerceAtLeast(0) / total)
                                .coerceIn(0.70f, 0.92f),
                        )
                    }
                    line.startsWith("__PKG_EXTRACTED__:") -> {
                        val parts = line.split(':')
                        val index = parts.getOrNull(1)?.toIntOrNull() ?: 0
                        val total = parts.getOrNull(2)?.toIntOrNull() ?: 1
                        onProgress(
                            "工具链解压完成 · $index/$total",
                            (0.70f + 0.22f * index / total).coerceIn(0.70f, 0.92f),
                        )
                    }
                }
            },
        )
        if (result.exitCode != 0) {
            Log.e(
                TAG,
                "apt toolchain extraction reported errors: ${result.error.orEmpty()} " +
                    result.output.takeLast(2_000),
            )
        }
        onProgress("迁移工具链运行路径", 0.94f)
        relocateTextAndSymlinks(prefix)
        ensureJavaLinks()
        if (!isToolchainsReady()) {
            Log.e(TAG, "toolchain validation failed after relocation")
            onProgress("开发工具链安装失败，可重试", 0f)
            return@withContext false
        }
        onProgress("验证 Python、Node、Java、clang、ShellCheck", 0.98f)
        toolchainsMarker.writeText(TOOLCHAIN_VERSION)
        onProgress("Python、Node、Java、clang、ShellCheck、make 和 git 已安装", 1f)
        true
        }
    }

    suspend fun run(
        command: String,
        workingDir: File,
        timeoutMs: Long = 120_000,
        extraEnv: Map<String, String> = emptyMap(),
        onOutput: (String) -> Unit = {},
    ): CommandResult = withContext(Dispatchers.IO) {
        if (!isBaseReady()) {
            return@withContext CommandResult(
                exitCode = -1,
                output = "",
                timedOut = false,
                durationMs = 0,
                error = "内置开发环境尚未安装",
            )
        }
        ensureAptConfig()

        val start = System.currentTimeMillis()
        val process = try {
            ProcessBuilder(shellCommand(command))
                .directory(workingDir)
                .redirectErrorStream(true)
                .apply {
                    environment().apply {
                        put("PREFIX", prefix.absolutePath)
                        put("TERMUX_PREFIX", prefix.absolutePath)
                        put("TERMUX__PREFIX", prefix.absolutePath)
                        put("TERMUX__ROOTFS", root.absolutePath)
                        put("TERMUX_APP__DATA_DIR", appContext.filesDir.absolutePath)
                        put("HOME", home.absolutePath)
                        put("TMPDIR", File(prefix, "tmp").apply { mkdirs() }.absolutePath)
                        put(
                            "PATH",
                            "${prefix.absolutePath}/bin:/system/bin:/system/xbin",
                        )
                        put("LD_LIBRARY_PATH", File(prefix, "lib").absolutePath)
                        put("APT_CONFIG", File(prefix, "etc/apt/apt.conf").absolutePath)
                        put("SSL_CERT_FILE", File(prefix, "etc/tls/cert.pem").absolutePath)
                        put("OPENSSL_CONF", File(prefix, "etc/tls/openssl.cnf").absolutePath)
                        put("GIT_EXEC_PATH", File(prefix, "libexec/git-core").absolutePath)
                        put("GIT_TEMPLATE_DIR", File(prefix, "share/git-core/templates").absolutePath)
                        put("GIT_SSL_CAINFO", File(prefix, "etc/tls/cert.pem").absolutePath)
                        put("TERM", "xterm-256color")
                        put("LANG", "C.UTF-8")
                        put("PWD", workingDir.absolutePath)
                        if (needsSystemLinker && execPreload.isFile) {
                            put("LD_PRELOAD", execPreload.absolutePath)
                            put("TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE", "force")
                            put(
                                "ANDROID__BUILD_VERSION_SDK",
                                Build.VERSION.SDK_INT.toString(),
                            )
                        }
                        putAll(extraEnv)
                    }
                }
                .start()
        } catch (error: Exception) {
            return@withContext CommandResult(
                exitCode = -1,
                output = "",
                timedOut = false,
                durationMs = System.currentTimeMillis() - start,
                error = error.message ?: "无法启动内置开发环境",
            )
        }

        activeProcess = process
        val captured = StringBuilder()
        try {
            coroutineScope {
                val reader = async {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (captured.length < MAX_CAPTURE_CHARS) captured.appendLine(line)
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
                    error = if (finished) null else "内置命令执行超时",
                )
            }
        } finally {
            if (activeProcess === process) activeProcess = null
            if (process.isAlive) process.destroyForcibly()
        }
    }

    fun environmentArray(): Array<String> {
        val tmp = File(prefix, "tmp").apply { mkdirs() }
        return buildList {
            addAll(arrayOf(
            "PREFIX=${prefix.absolutePath}",
            "TERMUX_PREFIX=${prefix.absolutePath}",
            "TERMUX__PREFIX=${prefix.absolutePath}",
            "TERMUX__ROOTFS=${root.absolutePath}",
            "TERMUX_APP__DATA_DIR=${appContext.filesDir.absolutePath}",
            "HOME=${home.absolutePath}",
            "TMPDIR=${tmp.absolutePath}",
            "PATH=${prefix.absolutePath}/bin:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH=${prefix.absolutePath}/lib",
            "APT_CONFIG=${prefix.absolutePath}/etc/apt/apt.conf",
            "SSL_CERT_FILE=${prefix.absolutePath}/etc/tls/cert.pem",
            "OPENSSL_CONF=${prefix.absolutePath}/etc/tls/openssl.cnf",
            "GIT_EXEC_PATH=${prefix.absolutePath}/libexec/git-core",
            "GIT_TEMPLATE_DIR=${prefix.absolutePath}/share/git-core/templates",
            "GIT_SSL_CAINFO=${prefix.absolutePath}/etc/tls/cert.pem",
            "TERMUX_APP_PACKAGE_MANAGER=apt",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            ))
            if (needsSystemLinker && execPreload.isFile) {
                add("LD_PRELOAD=${execPreload.absolutePath}")
                add("TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE=force")
                add("ANDROID__BUILD_VERSION_SDK=${Build.VERSION.SDK_INT}")
            }
        }.toTypedArray()
    }

    fun shellCommand(command: String): List<String> =
        if (needsSystemLinker) {
            listOf(systemLinker!!.absolutePath, bash.absolutePath, "-c", command)
        } else {
            listOf(bash.absolutePath, "-c", command)
        }

    fun terminalShell(): Pair<String, Array<String>> =
        if (needsSystemLinker) {
            systemLinker!!.absolutePath to arrayOf(
                bash.absolutePath,
                bash.absolutePath,
                "--norc",
                "--noprofile",
                "-i",
            )
        } else {
            bash.absolutePath to arrayOf(
                "bash",
                "--norc",
                "--noprofile",
                "-i",
            )
        }

    fun shellInvocation(command: String): String =
        shellCommand(command).joinToString(" ") { shellQuote(it) }

    fun cancel() {
        activeProcess?.let {
            terminateProcessTreeAsync(it, null)
        }
    }

    private fun extractBootstrap(
        destination: File,
        onProgress: (String, Float) -> Unit,
    ) {
        extractTarAsset("runtime/termux-bootstrap.tar", destination, onProgress)
    }

    private fun extractTarAsset(
        assetName: String,
        destination: File,
        onProgress: (String, Float) -> Unit,
    ) {
        // AGP/AAPT automatically stores gzip assets with the .gz suffix
        // removed, so the packaged asset is the uncompressed tar.
        appContext.assets.open(assetName).use { input ->
            TarArchiveInputStream(input).use { tar ->
                var count = 0
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val relative = entry.name.removePrefix("usr/").trimStart('/')
                    if (relative.isBlank()) continue
                    val output = File(destination, relative).canonicalFile
                    check(output.path.startsWith(destination.canonicalPath + File.separator)) {
                        "运行时包含非法路径: ${entry.name}"
                    }
                    when {
                        entry.isDirectory -> {
                            output.mkdirs()
                            chmod(output, entry.mode)
                        }
                        entry.isSymbolicLink -> {
                            output.parentFile?.mkdirs()
                            val link = remapLink(entry.linkName)
                            runCatching { Files.createSymbolicLink(output.toPath(), File(link).toPath()) }
                        }
                        entry.isFile -> {
                            output.parentFile?.mkdirs()
                            FileOutputStream(output).use { out ->
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    val read = tar.read(buffer)
                                    if (read <= 0) break
                                    out.write(buffer, 0, read)
                                }
                            }
                            chmod(output, entry.mode)
                        }
                    }
                    count++
                    if (count % 200 == 0) {
                        onProgress(
                            "解压核心系统 · $count 个文件",
                            (0.05f + count / 2_000f).coerceAtMost(0.32f),
                        )
                    }
                }
            }
        }
    }

    private fun relocateTextAndSymlinks(staging: File) {
        val oldPrefix = "/data/data/com.termux/files/usr"
        staging.walkTopDown().forEach { file ->
            if (file.isFile && file.length() < 2_000_000) {
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
                if (bytes.any { it == 0.toByte() }) return@forEach
                val text = bytes.toString(Charsets.UTF_8)
                if (text.contains(oldPrefix)) {
                    file.writeText(text.replace(oldPrefix, prefix.absolutePath))
                }
            }
        }
    }

    private fun ensureJavaLinks() {
        val javaBin = File(prefix, "lib/jvm/java-17-openjdk/bin")
        listOf("java", "javac").forEach { name ->
            val target = File(javaBin, name)
            if (!target.isFile) return@forEach
            val link = File(prefix, "bin/$name")
            link.delete()
            link.parentFile?.mkdirs()
            Files.createSymbolicLink(link.toPath(), target.toPath())
        }
    }

    private fun ensureAptConfig() {
        val aptDir = File(prefix, "etc/apt").apply { mkdirs() }
        val listsPartial = File(prefix, "var/lib/apt/lists/partial").apply { mkdirs() }
        val cachePartial = File(prefix, "var/cache/apt/archives/partial").apply { mkdirs() }
        val config = File(aptDir, "apt.conf")
        config.writeText(
            """
                Dir "/";
                Dir::State::status "${prefix.absolutePath}/var/lib/dpkg/status";
                Dir::Cache "${prefix.absolutePath}/var/cache/apt";
                Dir::State::lists "${prefix.absolutePath}/var/lib/apt/lists";
                Dir::Etc::sourcelist "${prefix.absolutePath}/etc/apt/sources.list";
                Dir::Etc::sourceparts "${prefix.absolutePath}/etc/apt/sources.list.d";
                Dir::Etc::parts "${prefix.absolutePath}/etc/apt/apt.conf.d";
                Dir::Etc::preferences "${prefix.absolutePath}/etc/apt/preferences";
                Dir::Etc::preferencesparts "${prefix.absolutePath}/etc/apt/preferences.d";
                Dir::Etc::trusted "${prefix.absolutePath}/etc/apt/trusted.gpg";
                Dir::Etc::trustedparts "${prefix.absolutePath}/etc/apt/trusted.gpg.d";
                Dir::Bin::methods "${prefix.absolutePath}/lib/apt/methods";
                Dir::Bin::dpkg "${prefix.absolutePath}/bin/dpkg";
                Dir::Bin::gpgv "${prefix.absolutePath}/bin/gpgv";
                Dir::Bin::apt-key "${prefix.absolutePath}/bin/apt-key";
                Dir::Temp "${prefix.absolutePath}/tmp";
                Acquire::https::CaInfo "${prefix.absolutePath}/etc/tls/cert.pem";
                """.trimIndent() + "\n",
        )
        val sources = File(aptDir, "sources.list")
        if (
            !sources.isFile ||
            sources.readText().contains("packages.termux.dev") ||
            sources.readText().contains("packages-cf.termux.dev")
        ) {
            sources.writeText(
                "# Pocket Agent: domestic Termux mirror with automatic fallback\n" +
                    "deb ${APT_MIRRORS.first().url} stable main\n",
            )
        }
        listOf(listsPartial, cachePartial).forEach {
            chmod(it, 0x1ED)
        }
    }

    private fun remapLink(link: String): String =
        if (link.startsWith("/data/data/com.termux/files/usr/")) {
            prefix.absolutePath + link.removePrefix("/data/data/com.termux/files/usr")
        } else {
            link
        }

    private fun chmod(file: File, mode: Int) {
        runCatching { Os.chmod(file.absolutePath, mode and 0x1FF) }
    }

    private fun isUsableProgram(file: File): Boolean =
        file.isFile && file.length() > 0

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun parseAptSize(line: String): Long {
        val token = Regex("""\[([0-9.]+)\s*([kKmMgG]?B)]""").find(line) ?: return 0
        val value = token.groupValues[1].toDoubleOrNull() ?: return 0
        return when (token.groupValues[2].uppercase()) {
            "KB" -> (value * 1_024).toLong()
            "MB" -> (value * 1_024 * 1_024).toLong()
            "GB" -> (value * 1_024 * 1_024 * 1_024).toLong()
            else -> value.toLong()
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1_024.0)
        else -> "$bytes B"
    }

    private fun requiredToolchains(): List<File> = listOf(
        File(prefix, "bin/python3"),
        File(prefix, "bin/pip3"),
        File(prefix, "bin/node"),
        File(prefix, "bin/npm"),
        File(prefix, "bin/java"),
        File(prefix, "bin/javac"),
        File(prefix, "bin/clang"),
        File(prefix, "bin/shellcheck"),
        File(prefix, "bin/make"),
    ) + requiredGitTools()

    private fun requiredGitTools(): List<File> = listOf(
        File(prefix, "bin/git"),
        File(prefix, "bin/ssh"),
        File(prefix, "bin/ssh-keygen"),
    )

    companion object {
        private const val TAG = "PocketAgentInternalTermux"
        private const val MAX_CAPTURE_CHARS = 256_000
        private const val RUNTIME_VERSION = "termux-bootstrap-dynamic-v6"
        private const val LEGACY_RUNTIME_PREFIX = "termux-bootstrap-python-pip-node-clang"
        private const val TOOLCHAIN_VERSION = "apt-python-node-java-clang-shellcheck-v3"
        private data class AptMirror(val label: String, val url: String)

        private val APT_MIRRORS = listOf(
            AptMirror(
                "清华 TUNA",
                "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
            ),
            AptMirror(
                "北外 BFSU",
                "https://mirrors.bfsu.edu.cn/termux/apt/termux-main",
            ),
            AptMirror(
                "中科大 USTC",
                "https://mirrors.ustc.edu.cn/termux/apt/termux-main",
            ),
            AptMirror(
                "南京大学 NJU",
                "https://mirrors.nju.edu.cn/termux/apt/termux-main",
            ),
            AptMirror(
                "官方镜像",
                "https://packages-cf.termux.dev/apt/termux-main",
            ),
        )
    }
}
