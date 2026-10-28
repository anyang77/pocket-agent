package dev.pocketagent.extensions

import android.content.Context
import dev.pocketagent.data.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ExtensionRepository(context: Context) {
    private val prefs = context.getSharedPreferences("codemate_extensions", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    @Synchronized fun settings() = ExtensionSettings(prefs.getBoolean("subagents", true), prefs.getInt("parallelism", 3).coerceIn(1, 3))
    @Synchronized fun saveSettings(value: ExtensionSettings) {
        prefs.edit().putBoolean("subagents", value.subagentsEnabled).putInt("parallelism", value.maxSubagents.coerceIn(1, 3)).apply()
    }
    @Synchronized fun servers(): List<McpServerConfig> = runCatching {
        val array = JSONArray(prefs.getString("servers", "[]"))
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            val secret = runCatching { JSONObject(secrets.getApiKey("mcp_$id").ifBlank { "{}" }) }.getOrDefault(JSONObject())
            McpServerConfig(id, item.getString("name"), item.optString("transport", "http"), item.optString("url"),
                item.optString("command"), item.optJSONArray("args")?.stringList().orEmpty(), item.optBoolean("enabled", true),
                secret.optJSONObject("headers")?.stringMap().orEmpty(), secret.optJSONObject("env")?.stringMap().orEmpty())
        }
    }.getOrDefault(emptyList())
    @Synchronized fun saveServer(config: McpServerConfig) {
        validateMcpServer(config)
        val all = servers().filterNot { it.id == config.id } + config
        require(all.size <= 16) { "最多配置 16 个 MCP 服务" }
        secrets.putApiKey("mcp_${config.id}", JSONObject().put("headers", JSONObject(config.headers)).put("env", JSONObject(config.env)).toString())
        prefs.edit().putString("servers", JSONArray().apply { all.forEach { put(it.publicJson()) } }.toString()).apply()
    }
    @Synchronized fun removeServer(id: String) {
        prefs.edit().putString("servers", JSONArray().apply { servers().filterNot { it.id == id }.forEach { put(it.publicJson()) } }.toString()).apply()
        secrets.removeApiKey("mcp_$id")
    }
    @Synchronized fun importServers(text: String) {
        val configs = parseMcpConfig(text)
        val old = servers()
        require(old.size + configs.count { c -> old.none { it.name == c.name } } <= 16) { "最多配置 16 个 MCP 服务" }
        configs.forEach { config -> saveServer(config.copy(id = old.firstOrNull { it.name == config.name }?.id ?: config.id)) }
    }
}

internal fun validateMcpServer(config: McpServerConfig) {
    require(config.name.isNotBlank()) { "请填写服务名称" }
    require(config.transport in listOf("http", "stdio")) { "仅支持 Streamable HTTP 或 stdio" }
    if (config.transport == "http") {
        val uri = java.net.URI(config.url)
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "请输入有效的 HTTP(S) 服务地址" }
    } else require(config.command.isNotBlank() && '\n' !in config.command && '\u0000' !in config.command) { "请填写单个可执行命令，参数放在参数列表中" }
    require(config.args.none { '\u0000' in it }) { "命令参数无效" }
    require(config.env.keys.all { Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it) }) { "环境变量名称无效" }
    require(config.headers.none { (key, value) -> key.isBlank() || '\n' in key || '\r' in key || '\n' in value || '\r' in value }) { "HTTP 请求头无效" }
}

/** Accepts the configuration format used by desktop MCP clients. Secrets never enter public preferences. */
internal fun parseMcpConfig(text: String): List<McpServerConfig> {
    require(text.length <= 128_000) { "配置过大" }
    val root = JSONObject(text)
    val servers = root.optJSONObject("mcpServers") ?: error("配置应包含 mcpServers 对象")
    require(servers.length() in 1..16) { "配置需要包含 1–16 个服务" }
    return servers.keys().asSequence().map { name ->
        val item = servers.getJSONObject(name)
        require(!item.has("type") || item.optString("type") in listOf("stdio", "http", "streamable-http")) { "$name 的传输类型不受支持" }
        McpServerConfig(UUID.randomUUID().toString(), name,
            transport = if (item.has("command")) "stdio" else "http", url = item.optString("url"), command = item.optString("command"),
            args = item.optJSONArray("args")?.stringList().orEmpty(), enabled = !item.optBoolean("disabled", false),
            headers = item.optJSONObject("headers")?.stringMap().orEmpty(), env = item.optJSONObject("env")?.stringMap().orEmpty(),
        ).also(::validateMcpServer)
    }.toList()
}
