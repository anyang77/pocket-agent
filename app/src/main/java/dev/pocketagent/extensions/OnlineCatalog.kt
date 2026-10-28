package dev.pocketagent.extensions

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

data class CatalogSkill(val repo: String, val revision: String, val path: String, val blob: String, val title: String = "", val description: String = "") {
    val key get() = "$repo/$path"
    val name get() = path.substringAfterLast('/').ifBlank { repo.substringAfter('/') }
    val folder get() = "online-" + MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
    val sourceUrl get() = "https://github.com/$repo/tree/$revision/$path"
}
data class CatalogMcp(val name: String, val title: String, val description: String, val version: String, val repository: String, val options: List<CatalogMcpOption>, val notes: String = "", val category: String = "")
data class CatalogMcpPage(val items: List<CatalogMcp>, val cursor: String)
data class CatalogInput(val key: String, val label: String, val description: String = "", val required: Boolean = false, val secret: Boolean = false, val default: String = "", val choices: List<String> = emptyList())
data class CatalogMcpOption(val label: String, val json: String, val local: Boolean) {
    fun inputs(): List<CatalogInput> = catalogInputs(JSONObject(json))
    fun config(name: String, values: Map<String, String>): McpServerConfig = compileCatalogMcp(name, JSONObject(json), values)
}
object CatalogSources {
    val skills = listOf("laolaoshiren/claude-code-skills-zh", "jnMetaCode/superpowers-zh", "anthropics/skills", "vercel-labs/agent-skills")
    const val chineseMcp = "yzfly/Awesome-MCP-ZH"
    const val officialMcp = "official"
    val labels = mapOf(
        "laolaoshiren/claude-code-skills-zh" to "中文技能精选",
        "jnMetaCode/superpowers-zh" to "Superpowers 中文版",
        "anthropics/skills" to "Anthropic 官方 · 英文",
        "vercel-labs/agent-skills" to "Vercel 官方 · 英文",
        chineseMcp to "MCP 中文资源库",
        officialMcp to "MCP 官方 Registry · 英文",
    )
}
data class CatalogDocument(val repo: String, val path: String, val content: String)
data class OnlineCatalogState(
    val kind: String = "", val source: String = CatalogSources.skills.first(), val query: String = "", val loading: Boolean = false,
    val error: String? = null, val skills: List<CatalogSkill> = emptyList(), val servers: List<CatalogMcp> = emptyList(), val cursor: String = "",
    val busy: String? = null, val detail: SkillInfo? = null, val selectedSkill: CatalogSkill? = null,
    val selectedServer: CatalogMcp? = null, val serverLoading: Boolean = false, val serverError: String? = null,
    val document: CatalogDocument? = null, val documentLoading: Boolean = false, val documentError: String? = null, val documentRepo: String? = null,
    val sources: List<String> = CatalogSources.skills, val installed: Map<String, String> = emptyMap(),
)

class OnlineCatalog(private val cache: File) {
    private data class Archive(val repo: String, val revision: String, val file: File)
    private val archives = java.util.concurrent.ConcurrentHashMap<String, Archive>()
    init {
        cache.mkdirs()
        cache.listFiles().orEmpty().filter { it.lastModified() < System.currentTimeMillis() - 7L * 86400000 }.forEach { it.delete() }
    }
    private fun archive(repo: String, refresh: Boolean = false): Archive {
        if (!refresh) archives[repo]?.takeIf { it.file.isFile }?.let { return it }
        val data = bytes("https://codeload.github.com/${normalizeSkillRepository(repo)}/zip/HEAD", 40_000_000)
        val temp = File.createTempFile("catalog-", ".zip", cache)
        try {
            temp.writeBytes(data)
            val revision = ZipFile(temp).use { it.comment.orEmpty() }
            require(Regex("[0-9a-f]{40}").matches(revision)) { "仓库未提供有效的版本信息" }
            val name = MessageDigest.getInstance("SHA-256").digest(repo.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
            val target = File(cache, "$name-$revision.zip")
            if (!target.exists()) require(temp.renameTo(target)) { "仓库缓存保存失败" }
            target.setLastModified(System.currentTimeMillis())
            cache.listFiles().orEmpty().filter { it != target && it.extension == "zip" }.sortedByDescending { it.lastModified() }.drop(8).forEach { it.delete() }
            return Archive(repo, revision, target).also { archives[repo] = it }
        } finally { temp.delete() }
    }
    private fun archiveText(file: File, relative: String): String? = ZipFile(file).use { zip ->
        val entry = zip.entries().asSequence().firstOrNull { !it.isDirectory && it.name.substringAfter('/', "") == relative } ?: return@use null
        require(entry.size in 0..1_500_000) { "仓库文档过大" }
        zip.getInputStream(entry).bufferedReader().use { it.readText() }
    }
    fun document(repo: String): CatalogDocument {
        val bundle = archive(normalizeSkillRepository(repo))
        val names = listOf("README.zh-CN.md", "README.zh_CN.md", "README.zh.md", "README_CN.md", "README-zh.md", "README.md", "readme.md")
        names.forEach { name -> archiveText(bundle.file, name)?.let { return CatalogDocument(repo, name, it) } }
        error("仓库没有可读取的 README 文档")
    }
    fun chineseServers(refresh: Boolean = false): CatalogMcpPage {
        val bundle = archive(CatalogSources.chineseMcp, refresh)
        val text = archiveText(bundle.file, "README.md") ?: error("中文仓库缺少 README.md")
        return CatalogMcpPage(parseChineseMcpCatalog(text), "")
    }
    fun resolveServer(item: CatalogMcp): CatalogMcp {
        val repo = item.repository.removePrefix("https://github.com/").split('/').take(2).joinToString("/")
        val subfolder = item.repository.substringAfter("/tree/", "").substringAfter('/', "")
        val search = if (subfolder.isNotEmpty()) subfolder.substringAfterLast('/') else repo.substringAfter('/')
        val matches = servers(search).items.filter { candidate ->
            candidate.repository.removeSuffix(".git").removeSuffix("/").equals("https://github.com/$repo", true) &&
                (subfolder.isEmpty() || candidate.name.substringAfterLast('/').removePrefix("server-") == search)
        }
        if (matches.isNotEmpty()) {
            val selected = matches.first()
            return selected.copy(title = item.title, description = item.description, repository = item.repository, notes = item.notes, category = item.category,
                options = matches.flatMap { it.options }.distinctBy { it.json })
        }
        return item
    }

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()
    internal fun bytes(url: String, limit: Int = 8_000_000): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", "CodeMate").header("Accept", "application/json").build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 403 || response.code == 429) error("仓库请求过于频繁，请稍后重试")
                error("仓库请求失败（HTTP ${response.code}）")
            }
            val body = response.body ?: error("仓库返回为空")
            require(body.contentLength() <= limit) { "下载内容过大" }
            body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16_384)
                while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= limit) { "下载内容过大" }; output.write(buffer, 0, count) }
                output.toByteArray()
            }
        }
    }
    private fun json(url: String) = JSONObject(bytes(url).toString(Charsets.UTF_8))
    fun servers(query: String, cursor: String = ""): CatalogMcpPage {
        val url = "https://registry.modelcontextprotocol.io/v0.1/servers".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "30").addQueryParameter("version", "latest")
        if (query.isNotBlank()) url.addQueryParameter("search", query.trim())
        if (cursor.isNotEmpty()) url.addQueryParameter("cursor", cursor)
        return parseMcpCatalog(json(url.build().toString()))
    }
    fun skills(source: String, refresh: Boolean = false): List<CatalogSkill> {
        val repo = normalizeSkillRepository(source)
        val bundle = archive(repo, refresh)
        return ZipFile(bundle.file).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith("/SKILL.md") && it.size in 1..128_000 }.take(300).map { entry ->
                val relative = entry.name.substringAfter('/', "")
                val folder = if (relative == "SKILL.md") "" else relative.removeSuffix("/SKILL.md")
                val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                val parsed = parseSkill("", relative, text)
                val heading = parsed.content.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim().orEmpty()
                CatalogSkill(repo, bundle.revision, folder, "", heading.ifBlank { parsed.name }, parsed.description)
            }.toList().sortedBy { it.path }
        }
    }
    fun detail(item: CatalogSkill): SkillInfo {
        val bundle = archives[item.repo]?.takeIf { it.revision == item.revision } ?: archive(item.repo)
        require(bundle.revision == item.revision) { "仓库已更新，请刷新列表后查看" }
        val relative = if (item.path.isEmpty()) "SKILL.md" else "${item.path}/SKILL.md"
        val text = archiveText(bundle.file, relative) ?: error("技能文档不存在")
        return parseSkill(item.key, item.sourceUrl, text)
    }
    fun download(item: CatalogSkill, target: File) {
        require(Regex("[0-9a-f]{40}").matches(item.revision)) { "仓库版本无效" }
        val archive = archives[item.repo]?.takeIf { it.revision == item.revision && it.file.isFile }?.file?.readBytes()
            ?: bytes("https://codeload.github.com/${normalizeSkillRepository(item.repo)}/zip/${item.revision}", 40_000_000)
        extractSkillBundle(archive, item.path, target)
        parseSkill(item.key, File(target, "SKILL.md").path, File(target, "SKILL.md").readText())
        File(target, ".codemate-source.json").writeText(JSONObject().put("repo", item.repo).put("path", item.path).put("revision", item.revision).toString())
    }
}

internal fun normalizeSkillRepository(value: String): String {
    val repo = value.trim().removePrefix("https://github.com/").removeSuffix("/").removeSuffix(".git")
    require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repo) && repo.split('/').none { it in listOf(".", "..") }) { "请输入 owner/repo 或 GitHub 仓库地址" }
    return repo
}
internal fun extractSkillBundle(archive: ByteArray, path: String, target: File) {
    require(path.split('/').none { it == ".." || it == "." } && !path.startsWith('/')) { "技能目录无效" }
    target.mkdirs()
    var total = 0L; var files = 0
    val archiveFile = File.createTempFile("codemate-skill-", ".zip", target.parentFile)
    try {
        archiveFile.writeBytes(archive)
        ZipFile(archiveFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val relative = entry.name.substringAfter('/', "")
                val prefix = if (path.isEmpty()) "" else "$path/"
                val rootLicense = !relative.contains('/') && (relative.startsWith("LICENSE", true) || relative.startsWith("NOTICE", true) || relative == "THIRD_PARTY_NOTICES.md")
                val outputPath = when {
                    relative.startsWith(prefix) -> relative.removePrefix(prefix)
                    rootLicense -> "SOURCE-$relative"
                    relative == "README.md" -> "SOURCE-README.md"
                    else -> continue
                }
                if (entry.isDirectory || outputPath.isEmpty()) continue
                require(++files <= 512) { "技能文件数量过多" }
                val file = File(target, outputPath).canonicalFile
                require(file.path.startsWith(target.canonicalPath + "/") && !outputPath.contains('\\')) { "技能文件路径无效" }
                require(outputPath != ".codemate-source.json") { "技能包含保留文件名" }
                file.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 20_000_000) { "技能解压后过大" }; output.write(buffer, 0, count) }
                } }
                if (file.extension in listOf("sh", "py", "js")) file.setExecutable(true, true)
            }
        }
    } finally { archiveFile.delete() }

    require(File(target, "SKILL.md").isFile && File(target, "SKILL.md").length() <= 128_000) { "仓库中未找到有效的 SKILL.md" }
}

internal fun parseMcpCatalog(root: JSONObject): CatalogMcpPage {
    val array = root.optJSONArray("servers") ?: JSONArray()
    val items = (0 until array.length()).mapNotNull { index ->
        val wrapper = array.getJSONObject(index)
        val status = wrapper.optJSONObject("_meta")?.optJSONObject("io.modelcontextprotocol.registry/official")?.optString("status")
        if (status != null && status != "active") return@mapNotNull null
        val server = wrapper.getJSONObject("server")
        val options = buildList {
            server.optJSONArray("remotes")?.let { list -> (0 until list.length()).forEach {
                val item = list.getJSONObject(it)
                if (item.optString("type") == "streamable-http") add(CatalogMcpOption("HTTP · ${item.optString("url")}", item.toString(), false))
            } }
            server.optJSONArray("packages")?.let { list -> (0 until list.length()).forEach {
                val item = list.getJSONObject(it)
                if (item.optJSONObject("transport")?.optString("type") == "stdio" && item.optString("registryType") in listOf("npm", "pypi") &&
                    item.optString("runtimeHint") in listOf("", "npx", "uvx")) add(CatalogMcpOption("${item.optString("registryType")} · ${item.optString("identifier")}", item.toString(), true))
            } }
        }
        CatalogMcp(server.getString("name"), server.optString("title").ifBlank { server.getString("name").substringAfterLast('/') },
            server.optString("description"), server.optString("version"), server.optJSONObject("repository")?.optString("url").orEmpty(), options)
    }.distinctBy { it.name }
    return CatalogMcpPage(items, root.optJSONObject("metadata")?.optString("nextCursor").orEmpty())
}

private fun metadataGroups(item: JSONObject): List<Pair<String, JSONObject>> = buildList {
    listOf("headers", "environmentVariables", "runtimeArguments", "packageArguments").forEach { group ->
        item.optJSONArray(group)?.let { list -> (0 until list.length()).forEach { add("$group:$it" to list.getJSONObject(it)) } }
    }
}
internal fun catalogInputs(item: JSONObject): List<CatalogInput> = buildList {
    fun variables(prefix: String, template: String, obj: JSONObject) {
        val vars = obj.optJSONObject("variables") ?: JSONObject()
        (vars.keys().asSequence().toList() + Regex("\\{([^{}]+)}").findAll(template).map { it.groupValues[1] }.toList()).distinct().forEach { name ->
            val v = vars.optJSONObject(name) ?: JSONObject()
            add(CatalogInput("$prefix:$name", name, v.optString("description"), true, v.optBoolean("isSecret", true), v.optString("default"), v.optJSONArray("choices")?.stringList().orEmpty()))
        }
    }
    variables("url", item.optString("url"), item)
    metadataGroups(item).forEach { (key, value) ->
        val fixed = value.optString("value")
        if (fixed.isEmpty()) add(CatalogInput(key, value.optString("name").ifBlank { value.optString("valueHint").ifBlank { key } },
            value.optString("description"), value.optBoolean("isRequired"), value.optBoolean("isSecret") || key.startsWith("headers:"), value.optString("default"), value.optJSONArray("choices")?.stringList().orEmpty()))
        else variables(key, fixed, value)
    }
}.distinctBy { it.key }
internal fun compileCatalogMcp(name: String, item: JSONObject, values: Map<String, String>): McpServerConfig {
    val inputs = catalogInputs(item)
    val effective = inputs.associate { it.key to (values[it.key] ?: it.default) }
    inputs.forEach { field ->
        require(!field.required || !effective[field.key].isNullOrBlank()) { "请填写 ${field.label}" }
        require(effective[field.key].isNullOrBlank() || field.choices.isEmpty() || effective[field.key] in field.choices) { "${field.label} 的取值无效" }
    }
    fun resolve(key: String, template: String): String = Regex("\\{([^{}]+)}").replace(template) { match -> effective["$key:${match.groupValues[1]}"] ?: error("缺少 ${match.groupValues[1]}") }
    fun value(key: String, obj: JSONObject) = if (obj.optString("value").isNotEmpty()) resolve(key, obj.getString("value")) else effective[key].orEmpty()
    val groups = metadataGroups(item)
    val id = "registry-" + MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
    val config = if (item.optString("type") == "streamable-http") {
        McpServerConfig(id, name, url = resolve("url", item.getString("url")), headers = groups.filter { it.first.startsWith("headers:") }.mapNotNull { (key, obj) -> value(key, obj).takeIf { it.isNotEmpty() }?.let { obj.getString("name") to it } }.toMap())
    } else {
        val kind = item.getString("registryType")
        require(kind in listOf("npm", "pypi")) { "暂不支持此安装方式" }
        val registry = item.optString("registryBaseUrl").trimEnd('/')
        require(registry.isEmpty() || registry == if (kind == "npm") "https://registry.npmjs.org" else "https://pypi.org") { "暂不支持自定义包注册表，请手动配置" }
        val identifier = item.getString("identifier")
        require(if (kind == "npm") Regex("(@[A-Za-z0-9_.-]+/)?[A-Za-z0-9][A-Za-z0-9_.-]*").matches(identifier) else Regex("[A-Za-z0-9][A-Za-z0-9_.-]*").matches(identifier)) { "包名无效" }
        val version = item.optString("version")
        require(version.isNotBlank() && Regex("[A-Za-z0-9_.+~-]+").matches(version)) { "缺少有效的固定版本" }
        fun arguments(group: String) = groups.filter { it.first.startsWith("$group:") }.flatMap { (key, obj) ->
            val v = value(key, obj)
            if (v.isEmpty()) emptyList() else if (obj.optString("type") == "named") listOf(obj.getString("name") + "=" + v) else listOf(v)
        }
        val runtime = if (kind == "npm") "npx" else "uvx"
        require(item.optString("runtimeHint").ifBlank { runtime } == runtime) { "包运行环境不受支持" }
        McpServerConfig(id, name, "stdio", command = runtime,
            args = (if (kind == "npm") listOf("-y") else emptyList()) + arguments("runtimeArguments") +
                (identifier + (if (kind == "npm") "@" else "==") + version) + arguments("packageArguments"),
            env = groups.filter { it.first.startsWith("environmentVariables:") }.mapNotNull { (key, obj) -> value(key, obj).takeIf { it.isNotEmpty() }?.let { obj.getString("name") to it } }.toMap())
    }
    validateMcpServer(config)
    return config
}

/** Reads the published Chinese table; descriptions remain the repository's original text. */
internal fun parseChineseMcpCatalog(text: String): List<CatalogMcp> {
    var category = ""
    val links = Regex("\\[([^]]+)]\\((https://github\\.com/[^)]+)\\)")
    return buildList {
        text.substringAfter("## MCP 服务器精选列表", text).substringBefore("\n## MCP 更多玩法").lineSequence().forEach { line ->
            if (line.startsWith("### ")) category = line.removePrefix("### ").trim()
            if (!line.trimStart().startsWith('|')) return@forEach
            val cells = line.trim().trim('|').split(Regex("(?<!\\\\)\\|")).map { it.trim().replace("\\|", "|") }
            if (cells.size < 2) return@forEach
            val match = links.find(cells[0]) ?: return@forEach
            val title = match.groupValues[1]
            val url = match.groupValues[2].removeSuffix("/")
            val repo = url.removePrefix("https://github.com/").split('/').take(2).joinToString("/")
            if (!Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repo)) return@forEach
            val description = cells[1].removeSurrounding("**")
            if (description.isBlank()) return@forEach
            add(CatalogMcp("zh:$repo:$title", title, description, "", url, emptyList(), cells.getOrNull(2).orEmpty(), category))
        }
    }.distinctBy { it.name }
}
