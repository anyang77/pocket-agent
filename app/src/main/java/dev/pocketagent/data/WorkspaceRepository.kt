package dev.pocketagent.data

import android.content.Context
import dev.pocketagent.runtime.CommandRunner
import dev.pocketagent.runtime.RootDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

data class FileEntry(
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

data class DirectoryEntry(
    val name: String,
    val path: String,
)

class WorkspaceRepository(
    context: Context,
    private val rootDetector: RootDetector = RootDetector(),
    private val commandRunner: CommandRunner = CommandRunner(rootDetector = rootDetector),
    private val useRoot: () -> Boolean = { false },
) {
    private val appContext = context.applicationContext
    fun agentPermissions(): dev.pocketagent.agent.AgentPermissions =
        PermissionSettingsRepository(appContext).load()

    val defaultRoot: File = File("/sdcard")
    @Volatile
    var root: File = defaultRoot
        private set
    val displayRoot: String
        get() = displayPath(root)

    suspend fun configure(path: String): File = withContext(Dispatchers.IO) {
        val requested = path.trim()
        val requestedTarget = if (requested.isBlank()) {
            defaultRoot
        } else {
            require(requested.startsWith("/")) { "工作区必须是绝对路径" }
            File(requested).canonicalFile
        }
        val target = requestedTarget.canonicalFile
        require(target != File("/")) { "不能把整个设备根目录作为工作区" }
        val appPrivate = appContext.filesDir.canonicalPath
        val insideAppPrivate = target.path == appPrivate ||
            target.path.startsWith("$appPrivate/")
        if (!insideAppPrivate) {
            val forbidden = listOf(
                "/system",
                "/vendor",
                "/product",
                "/odm",
                "/proc",
                "/sys",
                "/dev",
                "/data",
            )
            require(forbidden.none { target.path == it || target.path.startsWith("$it/") }) {
                "不能在系统只读目录中创建工作区"
            }
        }

        val directReady = runCatching {
            target.mkdirs()
            require(target.isDirectory) { "无法创建目录: ${target.path}" }
            val probe = File(target, ".pocketagent-write-test")
            probe.writeText("ok")
            probe.delete()
        }.isSuccess

        if (!directReady) {
            require(useRoot()) {
                "无法写入 ${target.path}。请选择 App 可写目录，或先完成 Root 授权"
            }
            val probePath = File(target, ".pocketagent-write-test").absolutePath
            val result = runRoot(
                "mkdir -p -- ${shellQuote(target.absolutePath)} && " +
                    "printf ok > ${shellQuote(probePath)} && " +
                    "rm -f -- ${shellQuote(probePath)}",
            )
            require(result.exitCode == 0) {
                result.output.ifBlank { result.error ?: "Root 无法写入该工作区" }
            }
        }

        root = target
        target
    }

    suspend fun ensureWorkspace() = withContext(Dispatchers.IO) {
        if (!root.mkdirs() && !root.isDirectory && useRoot()) {
            val result = runRoot("mkdir -p -- ${shellQuote(root.absolutePath)}")
            require(result.exitCode == 0) {
                result.output.ifBlank { result.error ?: "无法创建工作区" }
            }
        }
        require(root.isDirectory) { "工作区不是目录: ${root.absolutePath}" }
    }

    suspend fun listFiles(): List<FileEntry> = withContext(Dispatchers.IO) {
        if (usesRootWorkspace()) return@withContext listFilesAsRoot()
        runCatching {
            require(root.isDirectory) { "工作区不可访问" }
            require(root.canRead()) { "工作区没有读取权限" }
            root.walkTopDown()
                .onEnter { it == root || !it.name.startsWith(".pocketagent") }
                .maxDepth(6)
                .filter { it != root }
                .take(500)
                .map {
                    FileEntry(
                        relativePath = it.relativeTo(root).invariantSeparatorsPath,
                        isDirectory = it.isDirectory,
                        sizeBytes = if (it.isFile) it.length() else 0,
                    )
                }
                .sortedWith(
                    compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.relativePath },
                )
                .toList()
        }.getOrElse { error ->
            if (!useRoot()) throw error
            listFilesAsRoot()
        }
    }

    suspend fun listChildren(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val directory = resolve(path)
        if (usesRootWorkspace()) {
            return@withContext listChildrenAsRoot(directory)
        }
        runCatching {
            require(directory.isDirectory) { "目录不存在: $path" }
            require(directory.canRead()) { "目录没有读取权限" }
            directory.listFiles()
                ?.asSequence()
                ?.filter { !it.name.startsWith(".pocketagent") }
                ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
                ?.take(500)
                ?.map {
                    FileEntry(
                        relativePath = it.relativeTo(root).invariantSeparatorsPath,
                        isDirectory = it.isDirectory,
                        sizeBytes = if (it.isFile) it.length() else 0,
                    )
                }
                ?.toList()
                ?: emptyList()
        }.getOrElse { error ->
            if (!useRoot()) throw error
            listChildrenAsRoot(directory)
        }
    }

    suspend fun listDirectories(path: String): List<DirectoryEntry> = withContext(Dispatchers.IO) {
        val requested = File(path.trim()).canonicalFile
        require(requested.isAbsolute) { "目录必须是绝对路径" }
        val direct = runCatching {
            require(requested.isDirectory) { "目录不存在" }
            require(requested.canRead()) { "目录没有读取权限" }
            requested.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && !it.name.startsWith(".pocketagent") }
                ?.sortedBy { it.name.lowercase() }
                ?.take(300)
                ?.map { DirectoryEntry(it.name, displayPath(it)) }
                ?.toList()
                ?: emptyList()
        }.getOrElse { error ->
            if (!useRoot()) throw error
            listDirectoriesAsRoot(requested)
        }
        direct
    }

    suspend fun readText(
        path: String,
        maxChars: Int = MAX_FILE_CHARS,
        requireComplete: Boolean = false,
    ): String =
        withContext(Dispatchers.IO) {
            TextFileSupport.check(path)?.let { throw IllegalArgumentException(it) }
            val file = resolve(path)
            val maxBytes = maxChars.toLong() * 4L
            if (usesRootWorkspace()) {
                val result = runRoot(
                    "head -c ${maxBytes + 1} -- ${shellQuote(file.absolutePath)}",
                )
                require(result.exitCode == 0) {
                    result.output.ifBlank { result.error ?: "无法读取文件: $path" }
                }
                require(!result.output.contains('\u0000')) {
                    "这是二进制文件，不支持文本预览"
                }
                if (requireComplete) {
                    require(result.output.length <= maxChars) {
                        "文件过大，无法安全编辑"
                    }
                }
                return@withContext result.output.take(maxChars)
            }
            runCatching {
                require(file.isFile) { "不是文件: $path" }
                if (requireComplete) {
                    require(file.length() <= maxBytes) { "文件过大，无法安全编辑" }
                }
                val output = java.io.ByteArrayOutputStream()
                file.inputStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (output.size() <= maxBytes) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                TextFileSupport.decode(output.toByteArray(), maxChars)
            }.getOrElse { error ->
                if (!useRoot()) throw error
                val result = runRoot(
                    "head -c ${maxBytes + 1} -- ${shellQuote(file.absolutePath)}",
                )
                require(result.exitCode == 0) {
                    result.output.ifBlank { result.error ?: "无法读取文件: $path" }
                }
                require(!result.output.contains('\u0000')) {
                    "这是二进制文件，不支持文本预览"
                }
                result.output.take(maxChars)
            }
        }

    suspend fun writeText(path: String, content: String): Unit = withContext(Dispatchers.IO) {
        val file = resolve(path)
        require(file.path != root.path) { "不能覆盖工作区目录" }
        if (usesRootWorkspace()) {
            writeTextAsRoot(file, content)
            return@withContext
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
        }.getOrElse { error ->
            if (!useRoot()) throw error
            writeTextAsRoot(file, content)
        }
    }

    suspend fun createFile(path: String, content: String = ""): Unit = withContext(Dispatchers.IO) {
        val file = resolve(path)
        require(file.path != root.path) { "不能把工作区目录创建为文件" }
        if (!usesRootWorkspace()) {
            runCatching {
                require(!file.exists()) { "文件已存在: $path" }
                file.parentFile?.mkdirs()
                require(file.createNewFile()) { "无法创建文件: $path" }
                if (content.isNotEmpty()) file.writeText(content)
            }.getOrElse { error ->
                if (!useRoot()) throw error
                createFileAsRoot(file, content)
            }
        } else {
            createFileAsRoot(file, content)
        }
    }

    suspend fun createDirectory(path: String): Unit = withContext(Dispatchers.IO) {
        val directory = resolve(path)
        require(directory.path != root.path) { "目录已存在" }
        if (!usesRootWorkspace()) {
            runCatching {
                require(!directory.exists()) { "目录已存在: $path" }
                require(directory.mkdirs()) { "无法创建目录: $path" }
            }.getOrElse { error ->
                if (!useRoot()) throw error
                val result = runRoot(
                    "mkdir -p -- ${shellQuote(directory.absolutePath)}",
                )
                require(result.exitCode == 0) {
                    result.output.ifBlank { result.error ?: "无法创建目录" }
                }
            }
        } else {
            val result = runRoot("mkdir -p -- ${shellQuote(directory.absolutePath)}")
            require(result.exitCode == 0) {
                result.output.ifBlank { result.error ?: "无法创建目录" }
            }
        }
    }

    suspend fun renameEntry(path: String, newName: String): Unit = withContext(Dispatchers.IO) {
        validateEntryName(newName)
        val source = resolve(path)
        require(source.path != root.path) { "不能重命名工作区根目录" }
        val destination = File(source.parentFile, newName).canonicalFile
        require(destination.parentFile?.canonicalFile == source.parentFile?.canonicalFile) {
            "只能在同一目录内重命名"
        }
        if (!usesRootWorkspace()) {
            runCatching {
                require(source.exists()) { "条目不存在: $path" }
                require(!destination.exists()) { "目标名称已存在: $newName" }
                require(source.renameTo(destination)) { "重命名失败" }
            }.getOrElse { error ->
                if (!useRoot()) throw error
                renameAsRoot(source, destination)
            }
        } else {
            renameAsRoot(source, destination)
        }
    }

    suspend fun deleteEntry(path: String): Unit = withContext(Dispatchers.IO) {
        val target = resolve(path)
        require(target.path != root.path) { "不能删除工作区根目录" }
        if (!usesRootWorkspace()) {
            runCatching {
                require(target.exists()) { "条目不存在: $path" }
                val deleted = if (target.isDirectory) {
                    target.deleteRecursively()
                } else {
                    target.delete()
                }
                require(deleted) { "删除失败: $path" }
            }.getOrElse { error ->
                if (!useRoot()) throw error
                deleteAsRoot(target)
            }
        } else {
            deleteAsRoot(target)
        }
    }

    fun resolve(path: String): File {
        val rootPath = root.canonicalFile
        val requested = path.trim().ifBlank { "." }
        val direct = if (requested.startsWith("/")) {
            File(requested).canonicalFile
        } else {
            File(rootPath, requested).canonicalFile
        }
        val target = if (isInsideWorkspace(direct, rootPath)) {
            direct
        } else {
            // Some document pickers return "storage/emulated/0" without the
            // leading slash. Accept that form only when it resolves back into
            // this workspace; never use it as an escape from the workspace.
            val absoluteLike = File("/" + requested.trimStart('/', '.')).canonicalFile
            require(isInsideWorkspace(absoluteLike, rootPath)) {
                "路径超出工作区: $path"
            }
            absoluteLike
        }
        require(target == rootPath || target.path.startsWith(rootPath.path + File.separator)) {
            "路径超出工作区: $path"
        }
        return target
    }

    fun normalizeWorkspacePath(path: String): String {
        val target = resolve(path)
        val rootPath = root.canonicalFile
        return if (target == rootPath) {
            "."
        } else {
            target.relativeTo(rootPath).invariantSeparatorsPath
        }
    }

    private fun isInsideWorkspace(target: File, rootPath: File): Boolean =
        target == rootPath || target.path.startsWith(rootPath.path + File.separator)

    private fun usesRootWorkspace(): Boolean = useRoot() &&
        runCatching { root.canonicalFile != defaultRoot.canonicalFile }.getOrDefault(true)

    private suspend fun listFilesAsRoot(): List<FileEntry> {
        val script = """
            cd -- ${shellQuote(root.absolutePath)} || exit 1
            find . -maxdepth 6 -mindepth 1 -type d -print | while IFS= read -r p; do
                printf 'D\t%s\n' "${'$'}p"
            done
            find . -maxdepth 6 -mindepth 1 -type f -print | while IFS= read -r p; do
                printf 'F\t%s\n' "${'$'}p"
            done
        """.trimIndent()
        val result = runRoot(script)
        require(result.exitCode == 0) {
            result.output.ifBlank { result.error ?: "Root 无法列出工作区" }
        }
        val entries = result.output.lineSequence()
            .mapNotNull { line ->
                val type = line.firstOrNull() ?: return@mapNotNull null
                val relative = line.drop(2).trim().removePrefix("./")
                if (
                    relative.isBlank() ||
                    relative.startsWith(".pocketagent") ||
                    (type != 'D' && type != 'F')
                ) {
                    null
                } else {
                    relative to (type == 'D')
                }
            }
            .distinctBy { it.first }
            .take(500)
            .toList()
        return entries.map { (relative, isDirectory) ->
            FileEntry(
                relativePath = relative,
                isDirectory = isDirectory,
                sizeBytes = 0,
            )
        }.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.relativePath })
    }

    private suspend fun listDirectoriesAsRoot(directory: File): List<DirectoryEntry> {
        val script = """
            find ${shellQuote(directory.absolutePath)} -mindepth 1 -maxdepth 1 -type d -print
        """.trimIndent()
        val result = runRoot(script)
        require(result.exitCode == 0) {
            result.output.ifBlank { result.error ?: "无法浏览目录" }
        }
        return result.output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("/") }
            .map { File(it) }
            .filter { !it.name.startsWith(".pocketagent") }
            .sortedBy { it.name.lowercase() }
            .take(300)
            .map { DirectoryEntry(it.name, displayPath(it)) }
            .toList()
    }

    private suspend fun listChildrenAsRoot(directory: File): List<FileEntry> {
        val script = """
            find ${shellQuote(directory.absolutePath)} -mindepth 1 -maxdepth 1 -type d -print | while IFS= read -r p; do
                printf 'D\t%s\n' "${'$'}p"
            done
            find ${shellQuote(directory.absolutePath)} -mindepth 1 -maxdepth 1 -type f -print | while IFS= read -r p; do
                printf 'F\t%s\n' "${'$'}p"
            done
        """.trimIndent()
        val result = runRoot(script)
        require(result.exitCode == 0) {
            result.output.ifBlank { result.error ?: "无法列出目录" }
        }
        return result.output.lineSequence()
            .mapNotNull { line ->
                val type = line.firstOrNull() ?: return@mapNotNull null
                val absolute = line.drop(2).trim()
                val file = File(absolute)
                if (
                    absolute.isBlank() ||
                    file.name.startsWith(".pocketagent") ||
                    (type != 'D' && type != 'F')
                ) {
                    null
                } else {
                    FileEntry(
                        relativePath = workspaceRelativePath(file),
                        isDirectory = type == 'D',
                        sizeBytes = if (type == 'F') file.length() else 0,
                    )
                }
            }
            .distinctBy { it.relativePath }
            .sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.relativePath })
            .take(500)
            .toList()
    }

    fun displayPath(file: File): String {
        val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        val primary = runCatching {
            android.os.Environment.getExternalStorageDirectory().canonicalPath
        }.getOrNull().orEmpty()
        return if (primary.isNotBlank() && (canonical == primary || canonical.startsWith("$primary/"))) {
            "/sdcard" + canonical.removePrefix(primary)
        } else {
            file.absolutePath
        }
    }

    private suspend fun writeTextAsRoot(file: File, content: String) {
        val encoded = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
        val script = buildString {
            append("mkdir -p -- ${shellQuote(file.parentFile?.absolutePath.orEmpty())} && ")
            append("printf %s ${shellQuote(encoded)} | base64 -d > ")
            append(shellQuote(file.absolutePath))
        }
        val result = runRoot(script)
        require(result.exitCode == 0) {
            result.output.ifBlank { result.error ?: "Root 无法写入文件" }
        }
    }

    private suspend fun createFileAsRoot(file: File, content: String) {
        val encoded = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
        val script = "test ! -e ${shellQuote(file.absolutePath)} && " +
            "mkdir -p -- ${shellQuote(file.parentFile?.absolutePath.orEmpty())} && " +
            "printf %s ${shellQuote(encoded)} | base64 -d > ${shellQuote(file.absolutePath)}"
        val result = runRoot(script)
        require(result.exitCode == 0) {
            result.output.ifBlank { result.error ?: "文件已存在或无法创建" }
        }
    }

    private suspend fun renameAsRoot(source: File, destination: File) {
        val result = runRoot(
            "test -e ${shellQuote(source.absolutePath)} && " +
                "test ! -e ${shellQuote(destination.absolutePath)} && " +
                "mv -- ${shellQuote(source.absolutePath)} ${shellQuote(destination.absolutePath)}",
        )
        require(result.exitCode == 0) {
            result.output.ifBlank { result.error ?: "无法重命名" }
        }
    }

    private suspend fun deleteAsRoot(target: File) {
        val script = "rm -rf -- ${shellQuote(target.absolutePath)}"
        val result = runRoot(script)
        require(result.exitCode == 0) {
            result.output.ifBlank { result.error ?: "无法删除" }
        }
    }

    private fun validateEntryName(name: String) {
        require(name.isNotBlank()) { "名称不能为空" }
        require('/' !in name && '\\' !in name) { "名称不能包含路径分隔符" }
        require(name != "." && name != "..") { "名称无效" }
    }

    private fun workspaceRelativePath(file: File): String {
        val rootPath = root.canonicalFile
        val canonical = file.canonicalFile
        require(isInsideWorkspace(canonical, rootPath)) {
            "路径超出工作区: ${file.absolutePath}"
        }
        return if (canonical == rootPath) {
            "."
        } else {
            canonical.relativeTo(rootPath).invariantSeparatorsPath
        }
    }

    private suspend fun runRoot(command: String) = commandRunner.run(
        command = command,
        workingDir = File("/"),
        asRoot = true,
        timeoutMs = 30_000,
    )

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    companion object {
        const val MAX_FILE_CHARS = 512_000
    }
}
