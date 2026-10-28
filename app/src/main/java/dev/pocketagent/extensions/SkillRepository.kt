package dev.pocketagent.extensions

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

class SkillRepository(private val context: Context, private val workspaceRoot: () -> File) {
    private val directory = File(context.filesDir, "skills").apply { mkdirs() }
    init {
        directory.listFiles().orEmpty().filter { it.name.startsWith(".backup-online-") }.forEach { backup ->
            val target = File(directory, backup.name.removePrefix(".backup-"))
            if (target.exists()) backup.deleteRecursively() else backup.renameTo(target)
        }
        directory.listFiles().orEmpty().filter { it.name.startsWith(".install-") }.forEach { it.deleteRecursively() }
    }
    private val prefs = context.getSharedPreferences("codemate_skills", Context.MODE_PRIVATE)
    @Synchronized fun list(): List<SkillInfo> = buildList {
        listOf("android-build", "termux-environment", "code-review").forEach { id ->
            val path = "skills/$id/SKILL.md"
            val text = context.assets.open(path).bufferedReader().use { it.readText() }
            add(parseSkill("builtin:$id", "内置", text, true))
        }
        val project = workspaceRoot().canonicalFile
        val roots = listOf(directory, File(project, ".agents/skills"), File(project, ".codemate/skills"))
        roots.forEach { root ->
            root.listFiles().orEmpty().filterNot { it.name.startsWith(".") }.sortedBy { it.name }.take(100).forEach { folder ->
                val file = File(folder, "SKILL.md")
                if (file.isFile && file.length() <= 128_000 && file.canonicalPath.startsWith(root.canonicalPath + "/")) {
                    runCatching {
                        val id = "file:" + MessageDigest.getInstance("SHA-256").digest(file.canonicalPath.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
                        val source = runCatching { org.json.JSONObject(File(folder, ".codemate-source.json").readText()) }.getOrNull()
                        add(parseSkill(id, file.path, file.readText(), false).copy(source = source?.optString("repo").orEmpty(), revision = source?.optString("revision").orEmpty()))
                    }
                }
            }
        }
    }.map { it.copy(enabled = !prefs.getStringSet("disabled", emptySet()).orEmpty().contains(it.id)) }

    fun setEnabled(id: String, enabled: Boolean) {
        val disabled = prefs.getStringSet("disabled", emptySet()).orEmpty().toMutableSet()
        if (enabled) disabled.remove(id) else disabled.add(id)
        prefs.edit().putStringSet("disabled", disabled).apply()
    }
    @Synchronized fun save(name: String, description: String, instructions: String, existingId: String? = null) {
        require(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}").matches(name)) { "技能名称使用 1–64 位字母、数字、短横线或下划线" }
        require(description.isNotBlank() && instructions.isNotBlank()) { "请填写用途和技能内容" }
        require(description.length <= 500 && instructions.length <= 64_000) { "技能内容过长" }
        val existing = existingId?.let { id -> list().firstOrNull { it.id == id } }
        require(existing == null || (!existing.builtIn && File(existing.path).canonicalPath.startsWith(directory.canonicalPath + "/"))) { "工作区技能请在文件编辑器中修改" }
        val target = existing?.let { File(it.path) } ?: File(File(directory, name).apply { mkdirs() }, "SKILL.md")
        require(existing != null || !target.exists()) { "同名技能已存在" }
        val data = "---\nname: $name\ndescription: ${description.replace('\n', ' ').replace('\r', ' ')}\n---\n\n$instructions\n"
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try { output.write(data.toByteArray()); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    @Synchronized fun onlineVersions(): Map<String, String> = directory.listFiles().orEmpty().mapNotNull { folder ->
        runCatching {
            val source = org.json.JSONObject(File(folder, ".codemate-source.json").readText())
            "${source.getString("repo")}/${source.getString("path")}" to source.getString("revision")
        }.getOrNull()
    }.toMap()

    @Synchronized fun installOnline(item: CatalogSkill, catalog: OnlineCatalog) {
        val target = File(directory, item.folder)
        val stage = File(directory, ".install-${java.util.UUID.randomUUID()}")
        val backup = File(directory, ".backup-${item.folder}")
        try {
            require(!backup.exists()) { "请重新打开应用以恢复上次安装" }
            catalog.download(item, stage)
            if (target.exists()) require(target.renameTo(backup)) { "无法备份原技能" }
            if (!stage.renameTo(target)) {
                backup.renameTo(target)
                error("技能安装失败，原版本已保留")
            }
            backup.deleteRecursively()
        } finally { stage.deleteRecursively() }
    }

    fun readResource(id: String, path: String): String {
        val skill = list().firstOrNull { it.id == id && it.enabled } ?: error("技能不存在或已停用")
        require(!skill.builtIn) { "该技能没有附加文件" }
        val root = File(skill.path).parentFile!!.canonicalFile
        val file = File(root, path).canonicalFile
        require(file.path.startsWith(root.path + "/") && file.isFile && file.length() <= 128_000) { "文件不存在、超出技能目录或内容过大" }
        return file.readText()
    }

    @Synchronized fun remove(id: String) {
        val item = list().firstOrNull { it.id == id } ?: return
        val file = File(item.path).canonicalFile
        require(!item.builtIn && file.path.startsWith(directory.canonicalPath + "/")) { "仅可删除在应用内创建的技能" }
        val folder = file.parentFile!!
        if (File(folder, ".codemate-source.json").isFile) require(folder.deleteRecursively()) { "删除失败" }
        else { require(file.delete()) { "删除失败" }; folder.delete() }
    }
}

internal fun parseSkill(id: String, path: String, text: String, builtIn: Boolean = false): SkillInfo {
    val normalized = text.replace("\r\n", "\n")
    val front = if (normalized.startsWith("---\n")) normalized.substringAfter("---\n").substringBefore("\n---") else ""
    fun field(name: String) = front.lines().firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim()?.trim('"', '\'').orEmpty()
    val body = if (front.isNotEmpty()) normalized.substringAfter("\n---", "").trim() else normalized.trim()
    val name = field("name").ifBlank { File(path).parentFile?.name ?: id }
    val description = field("description").takeUnless { it in listOf("|", ">", "|-", ">-") }.orEmpty().ifBlank {
        front.lines().dropWhile { !it.startsWith("description:") }.drop(1).takeWhile { it.startsWith(" ") }.joinToString(" ") { it.trim() }
    }.ifBlank { body.lineSequence().firstOrNull { it.isNotBlank() }?.removePrefix("# ") ?: name }
    require(body.isNotBlank()) { "技能内容为空" }
    return SkillInfo(id, name.take(100), description.take(500), path, body.take(64_000), builtIn, license = field("license"))
}
