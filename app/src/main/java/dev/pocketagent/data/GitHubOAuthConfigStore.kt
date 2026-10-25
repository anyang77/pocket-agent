package dev.pocketagent.data

import android.content.Context

class GitHubOAuthConfigStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("pocket_agent_github_oauth", Context.MODE_PRIVATE)

    fun clientId(): String =
        preferences.getString("client_id", null).orEmpty().trim()

    fun saveClientId(clientId: String) {
        preferences.edit().putString("client_id", clientId.trim()).apply()
    }
}
