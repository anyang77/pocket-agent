package dev.pocketagent.extensions

import dev.pocketagent.agent.ToolDefinition
import org.json.JSONArray
import org.json.JSONObject

data class SkillInfo(val id: String, val name: String, val description: String, val path: String,
    val content: String, val builtIn: Boolean = false, val enabled: Boolean = true,
    val source: String = "", val revision: String = "", val license: String = "")

data class McpServerConfig(
    val id: String, val name: String, val transport: String = "http", val url: String = "",
    val command: String = "", val args: List<String> = emptyList(), val enabled: Boolean = true,
    val headers: Map<String, String> = emptyMap(), val env: Map<String, String> = emptyMap(),
) {
    fun publicJson() = JSONObject().put("id", id).put("name", name).put("transport", transport)
        .put("url", url).put("command", command).put("args", JSONArray(args)).put("enabled", enabled)
}

data class McpServerState(val id: String, val status: String = "未连接", val toolCount: Int = 0,
    val tools: List<String> = emptyList(), val error: String? = null, val connecting: Boolean = false)

data class ExtensionSettings(val subagentsEnabled: Boolean = true, val maxSubagents: Int = 3)

data class SubagentTask(
    val id: String, val title: String, val task: String, val status: String = "等待中",
    val running: Boolean = true, val result: String = "", val detail: String = "",
    val tokens: Int = 0, val startedAt: Long = System.currentTimeMillis(), val endedAt: Long? = null,
)

data class ExtensionToolResult(val content: String, val success: Boolean = true, val preview: String = "")

data class McpTool(val serverId: String, val remoteName: String, val definition: ToolDefinition,
    val readOnly: Boolean = false)

internal fun JSONObject.stringMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }
internal fun JSONArray.stringList(): List<String> = List(length()) { getString(it) }
internal fun toolSchema(vararg properties: Pair<String, JSONObject>, required: List<String> = emptyList()) = JSONObject()
    .put("type", "object").put("properties", JSONObject().apply { properties.forEach { put(it.first, it.second) } })
    .put("required", JSONArray(required)).put("additionalProperties", false)
internal fun textProperty(description: String) = JSONObject().put("type", "string").put("description", description)
