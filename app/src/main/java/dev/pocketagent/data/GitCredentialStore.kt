package dev.pocketagent.data

import android.content.Context
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

data class GitAccount(
    val id: String,
    val host: String,
    val username: String,
    val label: String,
)

class GitCredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext
        .getSharedPreferences("pocket_agent_git_credentials", Context.MODE_PRIVATE)
    private val secrets = SecretStore(appContext)
    private val askpass = File(appContext.filesDir, "git-askpass.sh").apply {
        writeText(
            """
            #!/system/bin/sh
            case "${'$'}1" in
              *sername*) printf '%s\n' "${'$'}POCKET_GIT_USERNAME" ;;
              *assword*) printf '%s\n' "${'$'}POCKET_GIT_PASSWORD" ;;
              *) printf '\n' ;;
            esac
            """.trimIndent() + "\n",
        )
        runCatching { Os.chmod(absolutePath, 0x1ED) }
    }

    @Synchronized
    fun accounts(): List<GitAccount> {
        migrateLegacyIfNeeded()
        val raw = preferences.getString("accounts", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    add(
                        GitAccount(
                            id = id,
                            host = item.optString("host"),
                            username = item.optString("username"),
                            label = item.optString("label", item.optString("username")),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(
        url: String,
        username: String,
        token: String,
        label: String = username,
    ): GitAccount? {
        if (token.isBlank()) return null
        val host = hostOf(url) ?: return null
        val resolvedUsername = username.trim().ifBlank {
            when {
                host.contains("github", ignoreCase = true) -> "x-access-token"
                host.contains("gitlab", ignoreCase = true) -> "oauth2"
                else -> "git"
            }
        }
        val account = GitAccount(
            id = "$host::$resolvedUsername",
            host = host,
            username = resolvedUsername,
            label = label.trim().ifBlank { resolvedUsername },
        )
        saveAccounts(accounts().filterNot { it.id == account.id } + account)
        secrets.putApiKey(secretKey(account.id), token.trim())
        preferences.edit().putString(activeKey(host), account.id).apply()
        return account
    }

    fun activeAccount(host: String): GitAccount? {
        val hostAccounts = accounts().filter { it.host == host }
        val activeId = preferences.getString(activeKey(host), null)
        return hostAccounts.firstOrNull { it.id == activeId } ?: hostAccounts.firstOrNull()
    }

    fun setActiveAccount(id: String) {
        val account = accounts().firstOrNull { it.id == id } ?: return
        preferences.edit().putString(activeKey(account.host), id).apply()
    }

    fun removeAccount(id: String) {
        val account = accounts().firstOrNull { it.id == id } ?: return
        val remaining = accounts().filterNot { it.id == id }
        saveAccounts(remaining)
        secrets.putApiKey(secretKey(id), "")
        if (preferences.getString(activeKey(account.host), null) == id) {
            val next = remaining.firstOrNull { it.host == account.host }
            preferences.edit().apply {
                if (next == null) remove(activeKey(account.host))
                else putString(activeKey(account.host), next.id)
            }.apply()
        }
    }

    fun environmentFor(url: String): Map<String, String> {
        val host = hostOf(url) ?: return emptyMap()
        val account = activeAccount(host) ?: return emptyMap()
        val token = secrets.getApiKey(secretKey(account.id))
        if (token.isBlank()) return emptyMap()
        return mapOf(
            "GIT_ASKPASS" to askpass.absolutePath,
            "GIT_TERMINAL_PROMPT" to "0",
            "POCKET_GIT_USERNAME" to account.username,
            "POCKET_GIT_PASSWORD" to token,
        )
    }

    fun accessToken(host: String): String? {
        val account = activeAccount(host) ?: return null
        return secrets.getApiKey(secretKey(account.id)).takeIf { it.isNotBlank() }
    }

    fun hostOf(url: String): String? = runCatching {
        URI(url.trim()).host?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun saveGithubAccount(token: String, login: String, displayName: String): GitAccount? {
        if (token.isBlank() || login.isBlank()) return null
        val account = GitAccount(
            id = "github.com::$login",
            host = "github.com",
            username = login,
            label = displayName.trim().ifBlank { login },
        )
        val previous = accounts()
        previous.filter { it.host == "github.com" }
            .filterNot { it.id == account.id }
            .forEach { secrets.putApiKey(secretKey(it.id), "") }
        saveAccounts(previous.filterNot { it.host == "github.com" } + account)
        secrets.putApiKey(secretKey(account.id), token.trim())
        preferences.edit().putString(activeKey("github.com"), account.id).apply()
        return account
    }

    fun removeActiveGithubAccount() {
        activeAccount("github.com")?.let { removeAccount(it.id) }
    }

    private fun saveAccounts(accounts: List<GitAccount>) {
        preferences.edit()
            .putString(
                "accounts",
                JSONArray().apply {
                    accounts.forEach { account ->
                        put(
                            JSONObject().apply {
                                put("id", account.id)
                                put("host", account.host)
                                put("username", account.username)
                                put("label", account.label)
                            },
                        )
                    }
                }.toString(),
            )
            .apply()
    }

    private fun migrateLegacyIfNeeded() {
        if (preferences.contains("accounts")) return
        val username = preferences.getString("username-github.com", null) ?: return
        val token = secrets.getApiKey("git-token-github.com")
        if (token.isBlank()) return
        val account = GitAccount(
            id = "github.com::$username",
            host = "github.com",
            username = username,
            label = username,
        )
        saveAccounts(listOf(account))
        secrets.putApiKey(secretKey(account.id), token)
        preferences.edit()
            .putString(activeKey("github.com"), account.id)
            .remove("username-github.com")
            .apply()
        secrets.putApiKey("git-token-github.com", "")
    }

    private fun secretKey(accountId: String): String = "git-account-token-$accountId"

    private fun activeKey(host: String): String = "active-account-$host"
}
