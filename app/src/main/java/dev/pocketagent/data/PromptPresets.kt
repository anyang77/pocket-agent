package dev.pocketagent.data

import org.json.JSONArray
import org.json.JSONObject

/** Global presets, independent of model profiles and conversations. Null means built-in only. */
data class PromptPreset(val id: String, val name: String, val content: String, val avatarPath: String = "")

data class PromptPresets(
    val items: List<PromptPreset> = emptyList(),
    val activeId: String? = null,
    val loadError: String? = null,
) {
    val active: PromptPreset? get() = items.firstOrNull { it.id == activeId }
    val effectivePrompt: String get() = active?.let {
        "User-selected agent name: ${JSONObject.quote(it.name)}. Use this display identity while retaining the built-in rules and tool permissions.\n\n${it.content}"
    }.orEmpty()

    fun select(id: String?): PromptPresets {
        require(id == null || items.any { it.id == id }) { "提示词不存在，请重新选择" }
        return copy(activeId = id)
    }

    fun save(id: String, name: String, content: String, avatarPath: String = items.firstOrNull { it.id == id }?.avatarPath.orEmpty()): PromptPresets {
        require(id.isNotBlank()) { "提示词 ID 不能为空" }
        require(name.trim().isNotEmpty() && name.trim().length <= MAX_NAME_LENGTH) { "名称需为 1–$MAX_NAME_LENGTH 字符" }
        require(content.length <= MAX_CONTENT_LENGTH) { "提示词不能超过 $MAX_CONTENT_LENGTH 字符" }
        require(content.isNotBlank()) { "请填写提示词内容；不使用自定义提示词时请选择默认" }
        val preset = PromptPreset(id, name.trim(), content.trim(), avatarPath)
        return copy(items = if (items.any { it.id == id }) items.map { if (it.id == id) preset else it } else items + preset)
    }

    fun delete(id: String): PromptPresets = copy(
        items = items.filterNot { it.id == id },
        activeId = activeId.takeUnless { it == id },
    )

    fun encode(): String = JSONObject().apply {
        put("items", JSONArray().apply {
            items.forEach { preset -> put(JSONObject().apply {
                put("id", preset.id); put("name", preset.name); put("content", preset.content); put("avatar_path", preset.avatarPath)
            }) }
        })
        put("active_id", activeId ?: JSONObject.NULL)
    }.toString()

    companion object {
        const val MAX_NAME_LENGTH = 60
        const val MAX_CONTENT_LENGTH = 16_000

        fun loadSafely(raw: String?, legacyPrompt: String = ""): PromptPresets = try {
            decode(raw, legacyPrompt)
        } catch (_: Exception) {
            PromptPresets(loadError = "提示词数据读取失败，原始数据已保留。暂用默认提示词，请先备份并修复配置。")
        }

        fun decode(raw: String?, legacyPrompt: String = ""): PromptPresets {
            // Legacy data is read without writing during startup. The first mutation persists
            // the whole collection atomically; an explicit empty collection never re-migrates.
            if (raw == null) {
                if (legacyPrompt.isBlank()) return PromptPresets()
                val migrated = PromptPreset("legacy-custom-prompt", "我的提示词", legacyPrompt.trim())
                return PromptPresets(listOf(migrated), migrated.id)
            }
            val json = JSONObject(raw)
            val array = json.getJSONArray("items")
            val items = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                PromptPreset(item.getString("id"), item.getString("name"), item.getString("content"), item.optString("avatar_path", ""))
            }
            require(items.all { it.id.isNotBlank() } && items.map { it.id }.distinct().size == items.size) {
                "提示词数据损坏，未覆盖原始数据"
            }
            val activeId = if (json.isNull("active_id")) null else json.getString("active_id")
            return PromptPresets(items, activeId?.takeIf { id -> items.any { it.id == id } })
        }
    }
}
