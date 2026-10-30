package dev.pocketagent.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ManagedRepository(
    val path: String,
    val lastOpenedAt: Long,
)

class ManagedRepositoryStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("pocket_agent_repositories", Context.MODE_PRIVATE)

    fun load(): List<ManagedRepository> {
        val raw = runCatching { preferences.getString("repositories", null) }.getOrNull() ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val path = item.optString("path")
                    if (path.isBlank()) continue
                    add(ManagedRepository(path, item.optLong("last_opened_at")))
                }
            }.distinctBy { it.path }.sortedByDescending { it.lastOpenedAt }
        }.getOrDefault(emptyList())
    }

    fun touch(path: String): List<ManagedRepository> {
        val now = System.currentTimeMillis()
        val updated = (load().filterNot { it.path == path } +
            ManagedRepository(path, now))
            .sortedByDescending { it.lastOpenedAt }
            .take(30)
        save(updated)
        return updated
    }

    fun remove(path: String): List<ManagedRepository> {
        val updated = load().filterNot { it.path == path }
        save(updated)
        return updated
    }

    private fun save(repositories: List<ManagedRepository>) {
        preferences.edit()
            .putString(
                "repositories",
                JSONArray().apply {
                    repositories.forEach { repository ->
                        put(
                            JSONObject().apply {
                                put("path", repository.path)
                                put("last_opened_at", repository.lastOpenedAt)
                            },
                        )
                    }
                }.toString(),
            )
            .apply()
    }
}
