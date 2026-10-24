package dev.pocketagent.data

import android.content.Context
import dev.pocketagent.runtime.CommandRunner
import java.io.File
import java.net.URI

data class GitChange(
    val path: String,
    val status: String,
    val staged: Boolean,
    val untracked: Boolean = false,
)

data class GitCommit(
    val hash: String,
    val author: String,
    val date: String,
    val subject: String,
)

data class GitState(
    val isRepository: Boolean = false,
    val branch: String = "",
    val ahead: Int = 0,
    val behind: Int = 0,
    val staged: List<GitChange> = emptyList(),
    val changes: List<GitChange> = emptyList(),
    val commits: List<GitCommit> = emptyList(),
    val branches: List<String> = emptyList(),
    val error: String? = null,
)

data class GitOperationResult(
    val success: Boolean,
    val message: String,
)

data class ParsedGitStatus(
    val branch: String,
    val ahead: Int,
    val behind: Int,
    val staged: List<GitChange>,
    val changes: List<GitChange>,
)

object GitStatusParser {
    fun parse(output: String): ParsedGitStatus {
        if (output.contains('\u0000')) return parseNullSeparated(output)
        val lines = output.lines()
        val header = lines.firstOrNull { it.startsWith("## ") }.orEmpty()
        val staged = mutableListOf<GitChange>()
        val changes = mutableListOf<GitChange>()
        lines.filter { it.isNotBlank() && !it.startsWith("## ") }.forEach { line ->
            if (line.length < 3) return@forEach
            appendChange(line.drop(2).removePrefix(" "), line[0], line[1], staged, changes)
        }
        return result(header, staged, changes)
    }

    private fun parseNullSeparated(output: String): ParsedGitStatus {
        val entries = output.split('\u0000')
        val header = entries.firstOrNull { it.trimStart().startsWith("## ") }.orEmpty()
        val staged = mutableListOf<GitChange>()
        val changes = mutableListOf<GitChange>()
        var index = 0
        while (index < entries.size) {
            val entry = entries[index].trimStart('\n', '\r')
            if (entry.isBlank() || entry.startsWith("## ") || entry.length < 3) {
                index++
                continue
            }
            val x = entry[0]
            val y = entry[1]
            val path = entry.drop(2).removePrefix(" ")
            appendChange(path, x, y, staged, changes)
            // With -z, rename/copy status is followed by the original path.
            if (x == 'R' || x == 'C' || y == 'R' || y == 'C') index++
            index++
        }
        return result(header, staged, changes)
    }

    private fun appendChange(
        path: String,
        x: Char,
        y: Char,
        staged: MutableList<GitChange>,
        changes: MutableList<GitChange>,
    ) {
        if (path.isBlank()) return
        if (x == '?' && y == '?') {
            changes += GitChange(path, "U", staged = false, untracked = true)
            return
        }
        if (x == '!') return
        if (x != ' ') staged += GitChange(path, label(x), staged = true)
        if (y != ' ') changes += GitChange(path, label(y), staged = false)
    }

    private fun result(
        rawHeader: String,
        staged: List<GitChange>,
        changes: List<GitChange>,
    ): ParsedGitStatus {
        val header = rawHeader.trim().removePrefix("## ")
        val branch = when {
            header.startsWith("No commits yet on ") ->
                header.removePrefix("No commits yet on ").substringBefore(' ')
            header.startsWith("HEAD ") || header.isBlank() -> "(detached)"
            else -> header.substringBefore("...").substringBefore(' ').substringBefore('\u0000')
        }.ifBlank { "(detached)" }
        val ahead = Regex("""ahead (\d+)""").find(header)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val behind = Regex("""behind (\d+)""").find(header)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return ParsedGitStatus(
            branch = branch,
            ahead = ahead,
            behind = behind,
            staged = staged.distinctBy { it.path to it.status },
            changes = changes.distinctBy { it.path to it.status },
        )
    }

    private fun label(status: Char): String = when (status) {
        'M' -> "M"
        'A' -> "A"
        'D' -> "D"
        'R' -> "R"
        'C' -> "C"
        'U' -> "!"
        else -> status.toString()
    }
}

object GitNameStatusParser {
    fun parse(output: String, staged: Boolean): List<GitChange> {
        val fields = output.split('\u0000')
        val changes = mutableListOf<GitChange>()
        var index = 0
        while (index < fields.size) {
            val rawStatus = fields[index].trim()
            index++
            if (rawStatus.isBlank()) continue
            val status = rawStatus.first()
            if (status == 'R' || status == 'C') {
                if (index + 1 >= fields.size) break
                index++ // Original path; the destination is what Source Control shows.
                val destination = fields[index].trim()
                index++
                if (destination.isNotBlank()) {
                    changes += GitChange(destination, status.toString(), staged = staged)
                }
                continue
            }
            if (index >= fields.size) break
            val path = fields[index].trim()
            index++
            if (path.isNotBlank()) {
                changes += GitChange(
                    path = path,
                    status = when (status) {
                        'A', 'M', 'D', 'T', 'U' -> status.toString()
                        else -> status.toString()
                    },
                    staged = staged,
                    untracked = status == '?',
                )
            }
        }
        return changes
    }
}

class GitRepository(
    context: Context,
    private val workspace: WorkspaceRepository,
    private val commandRunner: CommandRunner,
    runtimeHome: File,
) {
    private val credentials = GitCredentialStore(context)
    private val sshAccounts = SshAccountStore(context, commandRunner, runtimeHome)
    private val sshConfig = File(runtimeHome, ".ssh/config")
    private val sshKnownHosts = File(runtimeHome, ".ssh/known_hosts")

    fun accounts(): List<GitAccount> = credentials.accounts()

    fun activeGithubAccount(): GitAccount? = credentials.activeAccount("github.com")

    fun setActiveAccount(id: String) {
        credentials.setActiveAccount(id)
    }

    fun removeAccount(id: String) {
        credentials.removeAccount(id)
    }

    fun sshAccounts(): List<SshAccount> = sshAccounts.accounts()

    suspend fun generateSshAccount(
        label: String,
        onStatus: (String) -> Unit = {},
    ): SshAccount = sshAccounts.generate(label, onStatus)

    fun removeSshAccount(id: String) {
        sshAccounts.remove(id)
    }
    suspend fun state(): GitState {
        val check = run("git rev-parse --is-inside-work-tree", 20_000)
        if (check.exitCode != 0) {
            return GitState(
                error = if (check.output.contains("not a git repository", ignoreCase = true)) {
                    null
                } else {
                    check.output.trim().takeIf { it.isNotBlank() }
                },
            )
        }
        val status = run(
            "git status --porcelain=v1 -z --branch --untracked-files=all",
            30_000,
        )
        if (status.exitCode != 0) {
            return GitState(isRepository = true, error = status.output.trim())
        }
        val parsed = GitStatusParser.parse(status.output)
        val untracked = readUntrackedChanges()
        val fallbackStaged = if (parsed.staged.isEmpty() && parsed.changes.isEmpty()) {
            readDiffChanges(staged = true)
        } else {
            emptyList()
        }
        val fallbackUnstaged = if (parsed.staged.isEmpty() && parsed.changes.isEmpty()) {
            readDiffChanges(staged = false)
        } else {
            emptyList()
        }
        val stagedChanges = (parsed.staged + fallbackStaged)
            .distinctBy { it.path to it.status }
        val workingChanges = (parsed.changes + fallbackUnstaged + untracked)
            .distinctBy { it.path to it.status }
        val log = run(
            "git log -n 40 --date=format:%m-%d%20%H:%M " +
                "--pretty=format:%h%x1f%an%x1f%ad%x1f%s",
            30_000,
        )
        val commits = if (log.exitCode == 0) {
            log.output.lineSequence().mapNotNull { line ->
                val parts = line.split('\u001f', limit = 4)
                if (parts.size < 4) null else GitCommit(
                    hash = parts[0],
                    author = parts[1],
                    date = parts[2],
                    subject = parts[3],
                )
            }.toList()
        } else {
            emptyList()
        }
        val branchResult = run(
            "git branch --no-color --format=${shellQuote("%(refname:short)")}",
            20_000,
        )
        val branches = if (branchResult.exitCode == 0) {
            branchResult.output.lineSequence().filter { it.isNotBlank() }.sorted().toList()
        } else {
            listOf(parsed.branch)
        }
        return GitState(
            isRepository = true,
            branch = parsed.branch,
            ahead = parsed.ahead,
            behind = parsed.behind,
            staged = stagedChanges,
            changes = workingChanges,
            commits = commits,
            branches = branches,
        )
    }

    private suspend fun readUntrackedChanges(): List<GitChange> {
        val result = run(
            "git -c core.quotePath=false ls-files --others --exclude-standard -z",
            30_000,
        )
        if (result.exitCode != 0) return emptyList()
        return result.output
            .split('\u0000')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { GitChange(it, "U", staged = false, untracked = true) }
    }

    private suspend fun readDiffChanges(staged: Boolean): List<GitChange> {
        val cached = if (staged) "--cached " else ""
        val result = run(
            "git -c core.quotePath=false diff $cached--name-status -z --",
            30_000,
        )
        if (result.exitCode != 0) return emptyList()
        return GitNameStatusParser.parse(result.output, staged)
    }

    suspend fun diff(change: GitChange): String {
        val path = shellQuote(change.path)
        val command = when {
            change.untracked ->
                "git diff --no-color --no-index -- /dev/null $path"
            change.staged ->
                "git diff --cached --no-color -- $path"
            else ->
                "git diff --no-color -- $path"
        }
        val result = run(command, 30_000)
        if (result.exitCode !in 0..1 && result.output.isBlank()) {
            throw IllegalStateException(result.error ?: "无法读取 Git diff")
        }
        val body = result.output.ifBlank { "(没有可显示的差异)" }
        return if (change.untracked) {
            "未跟踪文件：${change.path}\n\n$body"
        } else {
            body
        }
    }

    suspend fun init(): GitOperationResult =
        operation("git init", "Git 仓库已初始化")

    suspend fun stage(change: GitChange): GitOperationResult =
        operation("git add -- ${shellQuote(change.path)}", "已暂存 ${change.path}")

    suspend fun stageAll(): GitOperationResult =
        operation("git add -A", "所有改动已暂存")

    suspend fun unstage(change: GitChange): GitOperationResult =
        operation("git restore --staged -- ${shellQuote(change.path)}", "已取消暂存 ${change.path}")

    suspend fun commit(message: String): GitOperationResult {
        require(message.isNotBlank()) { "提交说明不能为空" }
        return operation(
            "git -c user.name=Pocket\\ Agent -c user.email=pocket-agent@localhost " +
                "commit -m ${shellQuote(message.trim())}",
            "提交成功",
            120_000,
        )
    }

    suspend fun switchBranch(branch: String, create: Boolean): GitOperationResult {
        require(branch.isNotBlank()) { "分支名不能为空" }
        val command = if (create) {
            "git switch -c ${shellQuote(branch.trim())}"
        } else {
            "git switch ${shellQuote(branch.trim())}"
        }
        return operation(command, if (create) "已创建并切换分支" else "已切换分支")
    }

    suspend fun pull(): GitOperationResult {
        val remote = remoteName() ?: return GitOperationResult(
            false,
            "当前仓库没有配置远程地址，请先添加 origin",
        )
        val url = remoteUrl(remote)
        val env = credentials.environmentFor(url)
        val upstream = run(
            "git rev-parse --abbrev-ref --symbolic-full-name '@{u}'",
            20_000,
            env,
        )
        if (upstream.exitCode == 0) {
            return operation("git pull --ff-only", "拉取完成", 180_000, env)
        }

        val fetch = run("git fetch ${shellQuote(remote)}", 180_000, env)
        if (fetch.exitCode != 0) {
            return GitOperationResult(
                false,
                fetch.output.trim().ifBlank { fetch.error ?: "无法从远程仓库拉取" },
            )
        }
        val currentBranch = currentBranch()
        val remoteHead = run(
            "git ls-remote --symref ${shellQuote(remote)} HEAD",
            60_000,
            env,
        ).output
        val defaultBranch = Regex("""ref:\s+refs/heads/(\S+)\s+HEAD""")
            .find(remoteHead)
            ?.groupValues
            ?.get(1)
        val targetBranch = listOfNotNull(defaultBranch, currentBranch, "main", "master")
            .distinct()
            .firstOrNull { candidate ->
                run(
                    "git show-ref --verify --quiet " +
                        "refs/remotes/${shellQuote(remote)}/${shellQuote(candidate)}",
                    20_000,
                    env,
                ).exitCode == 0
            }
            ?: return GitOperationResult(
                false,
                "无法确定远程默认分支，请手动设置 upstream",
            )

        val pull = operation(
            "git pull --ff-only ${shellQuote(remote)} ${shellQuote(targetBranch)}",
            "拉取完成",
            180_000,
            env,
        )
        if (pull.success) {
            run(
                "git branch --set-upstream-to=" +
                    "${shellQuote("$remote/$targetBranch")} ${shellQuote(currentBranch)}",
                30_000,
                env,
            )
        }
        return pull
    }

    suspend fun push(): GitOperationResult {
        val remote = remoteName() ?: return GitOperationResult(
            false,
            "当前仓库没有配置远程地址，请先添加 origin",
        )
        val url = remoteUrl(remote)
        val env = credentials.environmentFor(url)
        val upstream = run(
            "git rev-parse --abbrev-ref --symbolic-full-name '@{u}'",
            20_000,
            env,
        )
        return if (upstream.exitCode == 0) {
            operation("git push", "推送完成", 180_000, env)
        } else {
            val branch = currentBranch()
            operation(
                "git push -u ${shellQuote(remote)} ${shellQuote(branch)}",
                "推送完成，并已设置 upstream",
                180_000,
                env,
            )
        }
    }

    suspend fun clone(
        url: String,
        folder: String,
        username: String,
        token: String,
        sshAccountId: String?,
    ): GitOperationResult {
        val effectiveUrl = if (sshAccountId != null) {
            sshAccounts.remoteUrlFor(url, sshAccountId)
        } else {
            validateCloneUrl(url)
            url.trim()
        }
        require(folder.isNotBlank()) { "目标目录不能为空" }
        val target = File(workspace.root, folder).canonicalFile
        require(target.path.startsWith(workspace.root.canonicalPath + File.separator)) {
            "目标目录必须位于工作区内"
        }
        if (sshAccountId == null) credentials.save(url, username, token)
        return operation(
            "git clone -- ${shellQuote(effectiveUrl)} " +
                shellQuote(target.absolutePath),
            "仓库已克隆到 ${target.absolutePath}",
            10 * 60_000L,
            if (sshAccountId == null) credentials.environmentFor(url) else emptyMap(),
        )
    }

    private suspend fun operation(
        command: String,
        successMessage: String,
        timeoutMs: Long = 60_000,
        extraEnv: Map<String, String> = emptyMap(),
    ): GitOperationResult {
        val result = run(command, timeoutMs, extraEnv)
        return if (result.exitCode == 0 && !result.timedOut) {
            GitOperationResult(true, successMessage)
        } else {
            GitOperationResult(
                false,
                result.output.trim().ifBlank { result.error ?: "Git 操作失败" },
            )
        }
    }

    private suspend fun run(
        command: String,
        timeoutMs: Long,
        extraEnv: Map<String, String> = emptyMap(),
    ): dev.pocketagent.runtime.CommandResult {
        return commandRunner.run(
        command = command,
        workingDir = workspace.root,
        timeoutMs = timeoutMs,
        extraEnv = extraEnv + mapOf(
            "GIT_TERMINAL_PROMPT" to "0",
            "GIT_SSH_COMMAND" to buildString {
                append("ssh -F ${shellQuote(sshConfig.absolutePath)}")
                append(" -o BatchMode=yes")
                append(" -o IdentitiesOnly=yes")
                append(" -o StrictHostKeyChecking=accept-new")
                append(" -o UserKnownHostsFile=${shellQuote(sshKnownHosts.absolutePath)}")
                append(" -o ConnectTimeout=20")
            },
            "GIT_CONFIG_SYSTEM" to "/dev/null",
            "GIT_CONFIG_COUNT" to "2",
            "GIT_CONFIG_KEY_0" to "safe.directory",
            "GIT_CONFIG_VALUE_0" to "*",
            "GIT_CONFIG_KEY_1" to "core.quotePath",
            "GIT_CONFIG_VALUE_1" to "false",
        ),
        )
    }

    private suspend fun remoteName(): String? {
        val names = run("git remote", 20_000).output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        return "origin".takeIf { it in names } ?: names.firstOrNull()
    }

    private suspend fun remoteUrl(remote: String): String =
        run(
            "git remote get-url ${shellQuote(remote)}",
            20_000,
        ).output.trim()

    private suspend fun currentBranch(): String =
        run("git rev-parse --abbrev-ref HEAD", 20_000).output.trim()
            .takeIf { it.isNotBlank() && it != "HEAD" }
            ?: "master"

    private fun validateCloneUrl(url: String) {
        require(url.isNotBlank()) { "仓库地址不能为空" }
        if (url.startsWith("git@") || url.startsWith("ssh://")) return
        val uri = runCatching { URI(url.trim()) }
            .getOrElse { throw IllegalArgumentException("仓库 URL 格式无效") }
        require(uri.scheme == "https" || uri.scheme == "http") {
            "仅支持 HTTP(S)、SSH 或 git@host:owner/repo 格式"
        }
        require(!uri.host.isNullOrBlank()) { "仓库 URL 缺少域名" }
        val path = uri.path.orEmpty().trim('/')
        require(path.contains('/')) {
            "仓库 URL 必须包含 owner/repo，例如 https://github.com/user/project.git"
        }
    }

    private fun label(status: Char): String = when (status) {
        'M' -> "M"
        'A' -> "A"
        'D' -> "D"
        'R' -> "R"
        'C' -> "C"
        'U' -> "!"
        else -> status.toString()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
