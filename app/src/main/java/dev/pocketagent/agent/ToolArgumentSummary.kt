package dev.pocketagent.agent

import org.json.JSONObject

/** Keep complete shell input for inspection, including while execution has no output. */
internal fun summarizeToolArguments(raw: String): String = runCatching {
    val json = JSONObject(raw)
    when {
        json.has("command") -> "environment=${json.optString("environment", "termux")}\ncwd=${json.optString("cwd", ".")}\n${json.optString("command")}"
        json.has("path") -> json.optString("path").take(180)
        else -> raw.take(180)
    }
}.getOrDefault(raw.take(180))
