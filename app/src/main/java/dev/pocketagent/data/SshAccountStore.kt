package dev.pocketagent.data

import android.content.Context
import android.system.Os
import dev.pocketagent.runtime.CommandRunner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SshAccount(
    val id: String,
    val label: String,
    val alias: String,
    val keyPath: String,
    val publicKey: String,
)

class SshAccountStore(
    context: Context,
    private val commandRunner: CommandRunner,
    private val runtimeHome: File,
) {
    private val preferences = context.applicationContext
        .getSharedPreferences("pocket_agent_ssh_accounts", Context.MODE_PRIVATE)
    private val sshDir = File(runtimeHome, ".ssh").apply {
        mkdirs()
        runCatching { Os.chmod(absolutePath, 0x1C0) }
    }
    private val configFile = File(sshDir, "config")

    fun accounts(): List<SshAccount> {
        val raw = preferences.getString("accounts", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val keyPath = item.optString("key_path")
                    val publicKey = File("$keyPath.pub").takeIf { it.isFile }?.readText()
                        ?.trim()
                        ?: item.optString("public_key")
                    if (keyPath.isBlank() || publicKey.isBlank()) continue
                    add(
                        SshAccount(
                            id = item.optString("id"),
                            label = item.optString("label", "GitHub"),
                            alias = item.optString("alias"),
                            keyPath = keyPath,
                            publicKey = publicKey,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun generate(label: String, onStatus: (String) -> Unit = {}): SshAccount {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val alias = "github-$id"
        val keyPath = File(sshDir, "pocket_$id")
        val comment = "Pocket Agent ${label.ifBlank { alias }}"
        onStatus("生成 SSH Key")
        val result = commandRunner.run(
            command = "ssh-keygen -q -t ed25519 -N '' -C ${shellQuote(comment)} " +
                "-f ${shellQuote(keyPath.absolutePath)}",
            workingDir = runtimeHome,
            timeoutMs = 60_000,
        )
        check(result.exitCode == 0) {
            result.output.ifBlank { result.error ?: "ssh-keygen 执行失败" }
        }
        runCatching { Os.chmod(keyPath.absolutePath, 0x180) }
        val publicKey = File("${keyPath.absolutePath}.pub").readText().trim()
        val account = SshAccount(
            id = id,
            label = label.trim().ifBlank { "GitHub $id" },
            alias = alias,
            keyPath = keyPath.absolutePath,
            publicKey = publicKey,
        )
        saveAccounts(accounts() + account)
        writeConfig(accounts())
        onStatus("SSH Key 已生成")
        return account
    }

    fun remove(id: String) {
        val account = accounts().firstOrNull { it.id == id } ?: return
        File(account.keyPath).delete()
        File("${account.keyPath}.pub").delete()
        val remaining = accounts().filterNot { it.id == id }
        saveAccounts(remaining)
        writeConfig(remaining)
    }

    fun remoteUrlFor(url: String, accountId: String): String {
        val account = accounts().firstOrNull { it.id == accountId }
            ?: throw IllegalArgumentException("SSH 账号不存在")
        val trimmed = url.trim()
        val path = when {
            trimmed.startsWith("git@github.com:") ->
                trimmed.removePrefix("git@github.com:")
            trimmed.startsWith("ssh://git@github.com/") ->
                trimmed.removePrefix("ssh://git@github.com/")
            trimmed.startsWith("https://github.com/") ->
                trimmed.removePrefix("https://github.com/")
            trimmed.startsWith("http://github.com/") ->
                trimmed.removePrefix("http://github.com/")
            else -> throw IllegalArgumentException("SSH 账号目前仅支持 github.com 仓库")
        }.trim('/')
        require(path.contains('/')) { "仓库 URL 必须包含 owner/repo" }
        return "git@${account.alias}:$path"
    }

    private fun writeConfig(accounts: List<SshAccount>) {
        configFile.writeText(
            buildString {
                appendLine("# Managed by Pocket Agent")
                accounts.forEach { account ->
                    appendLine("Host ${account.alias}")
                    appendLine("  HostName github.com")
                    appendLine("  User git")
                    appendLine("  IdentityFile ${account.keyPath}")
                    appendLine("  IdentitiesOnly yes")
                    appendLine("  StrictHostKeyChecking accept-new")
                    appendLine()
                }
            },
        )
        runCatching { Os.chmod(configFile.absolutePath, 0x180) }
    }

    private fun saveAccounts(accounts: List<SshAccount>) {
        preferences.edit()
            .putString(
                "accounts",
                JSONArray().apply {
                    accounts.forEach { account ->
                        put(
                            JSONObject().apply {
                                put("id", account.id)
                                put("label", account.label)
                                put("alias", account.alias)
                                put("key_path", account.keyPath)
                                put("public_key", account.publicKey)
                            },
                        )
                    }
                }.toString(),
            )
            .apply()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
