package dev.pocketagent.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ModelProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val contextWindow: Int,
    val reasoningEffort: String,
)

data class AiSettings(
    val baseUrl: String = "https://api.deepseek.com/v1",
    val model: String = "deepseek-chat",
    val apiKey: String = "",
    val contextWindow: Int = 128_000,
    val workspacePath: String = "",
    val reasoningEffort: String = "auto",
)

class SettingsRepository(
    context: Context,
    private val secretStore: SecretStore = SecretStore(context),
) {
    private val appContext = context.applicationContext
    private val preferences = appContext
        .getSharedPreferences("pocket_agent_settings", Context.MODE_PRIVATE)

    fun load(): AiSettings {
        val profiles = loadProfilesInternal()
        val active = profiles.firstOrNull { it.id == activeProfileId() }
            ?: profiles.first()
        return active.toSettings(loadWorkspacePath())
    }

    fun loadProfiles(): List<ModelProfile> = loadProfilesInternal()

    fun activeProfileId(): String {
        val profiles = loadProfilesInternal()
        val stored = preferences.getString("active_profile_id", null)
        return profiles.firstOrNull { it.id == stored }?.id ?: profiles.first().id
    }

    fun save(settings: AiSettings) {
        val profiles = loadProfilesInternal().toMutableList()
        val id = activeProfileId()
        val index = profiles.indexOfFirst { it.id == id }.coerceAtLeast(0)
        val previous = profiles[index]
        val updated = previous.copy(
            baseUrl = settings.baseUrl.trim(),
            model = settings.model.trim(),
            apiKey = settings.apiKey.trim(),
            contextWindow = settings.contextWindow.coerceIn(MIN_CONTEXT_WINDOW, MAX_CONTEXT_WINDOW),
            reasoningEffort = settings.reasoningEffort.trim().ifBlank { "auto" },
        )
        profiles[index] = updated
        saveProfilesInternal(profiles, updated.id)
        saveWorkspacePath(settings.workspacePath)
    }

    fun createProfile(
        name: String,
        baseUrl: String,
        apiKey: String,
        contextWindow: Int,
    ): ModelProfile {
        val profiles = loadProfilesInternal().toMutableList()
        val profile = ModelProfile(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "模型配置 ${profiles.size + 1}" },
            baseUrl = baseUrl.trim().ifBlank { "https://api.deepseek.com/v1" },
            model = "deepseek-chat",
            apiKey = apiKey.trim(),
            contextWindow = contextWindow.coerceIn(MIN_CONTEXT_WINDOW, MAX_CONTEXT_WINDOW),
            reasoningEffort = "auto",
        )
        profiles += profile
        saveProfilesInternal(profiles, profile.id)
        return profile
    }

    fun deleteProfile(id: String) {
        val profiles = loadProfilesInternal().toMutableList()
        require(profiles.size > 1) { "至少保留一个模型配置" }
        profiles.removeAll { it.id == id }
        secretStore.removeApiKey(id)
        val nextActive = if (activeProfileId() == id) profiles.first().id else activeProfileId()
        saveProfilesInternal(profiles, nextActive)
    }

    fun setActiveProfile(id: String) {
        val profiles = loadProfilesInternal()
        require(profiles.any { it.id == id }) { "模型配置不存在" }
        preferences.edit().putString("active_profile_id", id).apply()
    }

    fun renameProfile(id: String, name: String) {
        val profiles = loadProfilesInternal().toMutableList()
        val index = profiles.indexOfFirst { it.id == id }
        require(index >= 0) { "模型配置不存在" }
        profiles[index] = profiles[index].copy(name = name.trim().ifBlank { profiles[index].name })
        saveProfilesInternal(profiles, activeProfileId())
    }

    private fun loadProfilesInternal(): MutableList<ModelProfile> {
        val raw = preferences.getString("model_profiles", null)
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val id = item.optString("id")
                        if (id.isBlank()) continue
                        add(
                            ModelProfile(
                                id = id,
                                name = item.optString("name", "模型配置"),
                                baseUrl = item.optString(
                                    "base_url",
                                    "https://api.deepseek.com/v1",
                                ),
                                model = item.optString("model", "deepseek-chat"),
                                apiKey = secretStore.getApiKey(id),
                                contextWindow = item.optInt("context_window", 128_000)
                                    .coerceIn(MIN_CONTEXT_WINDOW, MAX_CONTEXT_WINDOW),
                                reasoningEffort = item.optString("reasoning_effort", "auto"),
                            ),
                        )
                    }
                }.toMutableList()
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }

        val legacyBase = preferences.getString("base_url", null)
            ?.ifBlank { null }
            ?: "https://api.deepseek.com/v1"
        val legacyModel = preferences.getString("model", null)
            ?.ifBlank { null }
            ?: "deepseek-chat"
        val migrated = ModelProfile(
            id = "profile-default",
            name = if (legacyBase.contains("deepseek", ignoreCase = true)) {
                "DeepSeek"
            } else {
                "默认配置"
            },
            baseUrl = legacyBase,
            model = legacyModel,
            apiKey = secretStore.getApiKey(),
            contextWindow = preferences.getInt("context_window", 128_000)
                .coerceIn(MIN_CONTEXT_WINDOW, MAX_CONTEXT_WINDOW),
            reasoningEffort = preferences.getString("reasoning_effort", "auto")
                .orEmpty()
                .ifBlank { "auto" },
        ).let { profile ->
            if (profile.apiKey.isNotBlank()) {
                secretStore.putApiKey(profile.id, profile.apiKey)
            }
            profile
        }
        saveProfilesInternal(mutableListOf(migrated), migrated.id)
        return mutableListOf(migrated)
    }

    private fun saveProfilesInternal(profiles: List<ModelProfile>, activeId: String) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject().apply {
                    put("id", profile.id)
                    put("name", profile.name)
                    put("base_url", profile.baseUrl)
                    put("model", profile.model)
                    put("context_window", profile.contextWindow)
                    put("reasoning_effort", profile.reasoningEffort)
                },
            )
            secretStore.putApiKey(profile.id, profile.apiKey)
        }
        preferences.edit()
            .putString("model_profiles", array.toString())
            .putString("active_profile_id", activeId)
            .apply()
    }

    private fun loadWorkspacePath(): String {
        val stored = preferences.getString("workspace_path", "").orEmpty()
        val legacy = File(appContext.filesDir, "workspace").absolutePath
        return if (stored == legacy) "" else stored
    }

    private fun saveWorkspacePath(path: String) {
        preferences.edit().putString("workspace_path", path.trim()).apply()
    }

    private fun ModelProfile.toSettings(workspacePath: String): AiSettings = AiSettings(
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
        contextWindow = contextWindow,
        workspacePath = workspacePath,
        reasoningEffort = reasoningEffort,
    )

    companion object {
        const val MIN_CONTEXT_WINDOW = 128_000
        const val MAX_CONTEXT_WINDOW = 1_000_000
    }
}
