package dev.pocketagent.data

import java.net.URLEncoder
import java.util.Base64

/** A per-command snapshot. Only [description] may be included in model instructions. */
class GitCommandAuth(
    val environment: Map<String, String> = emptyMap(),
    val description: String = "",
    private val secrets: List<String> = emptyList(),
) {
    fun redact(text: String): String = secrets.fold(text) { output, secret ->
        output.replace(secret, "[已隐藏 Git 凭据]")
    }
}

internal class GitCommandAccount(val host: String, val username: String, val token: String)

internal fun gitCommandAuth(
    accounts: List<GitCommandAccount>,
    askpassPath: String,
    extraEnvironment: Map<String, String> = emptyMap(),
): GitCommandAuth {
    val active = accounts.filter { it.token.isNotBlank() }.distinctBy { it.host }
    val environment = linkedMapOf(
        "GIT_TERMINAL_PROMPT" to "0",
        "GH_PROMPT_DISABLED" to "1",
    )
    val config = linkedMapOf(
        "safe.directory" to "*",
        "core.quotePath" to "false",
    ).toList().toMutableList()
    if (active.isNotEmpty()) {
        // Use an Android-shebang askpass script: Git's inline credential helpers invoke
        // the shell at its original Termux prefix, which is unavailable under the app UID.
        config += "credential.helper" to ""
        environment["GIT_ASKPASS"] = askpassPath
        environment["LC_MESSAGES"] = "C"
        environment["CODEMATE_GIT_ACCOUNT_COUNT"] = active.size.toString()
        active.forEachIndexed { index, account ->
            environment["CODEMATE_GIT_HOST_$index"] = account.host
            environment["CODEMATE_GIT_USERNAME_$index"] = account.username
            environment["CODEMATE_GIT_TOKEN_$index"] = account.token
        }
    }
    config.forEachIndexed { index, (key, value) ->
        environment["GIT_CONFIG_KEY_$index"] = key
        environment["GIT_CONFIG_VALUE_$index"] = value
    }
    environment["GIT_CONFIG_COUNT"] = config.size.toString()
    active.firstOrNull { it.host == "github.com" }?.let {
        environment["GH_TOKEN"] = it.token
        environment["GITHUB_TOKEN"] = it.token
    }
    val secrets = active.flatMap {
        listOf(
            it.token,
            URLEncoder.encode(it.token, "UTF-8"),
            Base64.getEncoder().encodeToString("${it.username}:${it.token}".toByteArray(Charsets.UTF_8)),
        )
    }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
    val description = if (active.isEmpty()) {
        "Git authentication: no saved HTTPS account. Existing SSH accounts, if configured, are available."
    } else {
        "Git authentication: signed-in accounts " + active.joinToString { "${it.username}@${it.host}" } +
            ". run_command automatically authenticates HTTPS Git requests for these hosts and uses the app's SSH configuration. " +
            "For github.com, gh uses GH_TOKEN automatically (install gh in Termux if needed); " +
            "GitHub API requests may use GITHUB_TOKEN from the command environment. " +
            "Use the existing login; do not ask for a token, inspect secret storage, or print credential variables. " +
            "Login shares the account's existing permissions; repository access still depends on those permissions."
    }
    return GitCommandAuth(environment + extraEnvironment, description, secrets)
}

/** No secrets are written here; all values are supplied to the child process environment. */
internal fun gitAskpassScript(shell: String = "/system/bin/sh"): String = """
    #!SHELL
    if [ -n "${'$'}CODEMATE_GIT_ACCOUNT_COUNT" ]; then
      prompt=${'$'}1
      case "${'$'}prompt" in
        *"'https://"*) ;;
        *) exit 1 ;;
      esac
      url=${'$'}{prompt#*\'https://}
      url=${'$'}{url%%\'*}
      authority=${'$'}{url%%/*}
      host=${'$'}{authority##*@}
      index=0
      while [ "${'$'}index" -lt "${'$'}CODEMATE_GIT_ACCOUNT_COUNT" ]; do
        eval "account_host=\${'$'}{CODEMATE_GIT_HOST_${'$'}index}"
        if [ "${'$'}host" = "${'$'}account_host" ]; then
          case "${'$'}prompt" in
            Username*) eval "value=\${'$'}{CODEMATE_GIT_USERNAME_${'$'}index}" ;;
            Password*) eval "value=\${'$'}{CODEMATE_GIT_TOKEN_${'$'}index}" ;;
            *) exit 1 ;;
          esac
          printf '%s\n' "${'$'}value"
          exit 0
        fi
        index=${'$'}((index + 1))
      done
      exit 1
    fi
    case "${'$'}1" in
      *sername*) printf '%s\n' "${'$'}POCKET_GIT_USERNAME" ;;
      *assword*) printf '%s\n' "${'$'}POCKET_GIT_PASSWORD" ;;
      *) exit 1 ;;
    esac
""".trimIndent().replace("#!SHELL", "#!${shell}") + "\n"
