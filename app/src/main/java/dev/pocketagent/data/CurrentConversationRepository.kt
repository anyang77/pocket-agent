package dev.pocketagent.data

import android.content.Context

class CurrentConversationRepository(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("pocket_agent_current_conversation", Context.MODE_PRIVATE)

    fun load(): Long? = preferences.getLong("id", 0L).takeIf { it > 0L }

    fun save(id: Long) {
        preferences.edit().putLong("id", id).apply()
    }

    fun clear() {
        preferences.edit().remove("id").apply()
    }
}
