package dev.pocketagent.agent

import java.io.File

/** Agent-entry checks, not an OS sandbox. cwd alone does not confine a shell. */
object CommandSafety {
    fun criticalReason(command: String, cwd: File): String? =
        CriticalCommandPolicy.reason(command, cwd)

    fun isWorkspacePath(file: File, workspace: File): Boolean =
        file.canonicalFile.toPath().startsWith(workspace.canonicalFile.toPath())

    fun isWithinWorkspace(command: String, cwd: File, workspace: File, asRoot: Boolean): Boolean {
        fun inside(file: File): Boolean = isWorkspacePath(file, workspace)
        if (asRoot || !inside(cwd)) return false
        // Dynamic expansion / inline code can conceal external file operations.
        if (command.any { it in "$`" } || Regex("""(?:^|[\s;&|])(?:eval|exec|source|su|sudo|xargs)\b|(?:^|[;&|]\s*)\.\s|\b(?:python[\d.]*|node|ruby|perl|bash|sh|zsh)\s+.*(?:-[a-z]*[ce]|<<)""").containsMatchIn(command)) return false
        val tokens = Regex("""'([^']*)'|"([^"]*)"|[^\s;|&<>]+""").findAll(command).map { it.value.trim('\'', '"') }.toList()
        for (token in tokens) {
            if (token.startsWith("~")) return false
            val value = token.substringAfter('=', token)
            if (value.contains("://")) continue
            // A bare filename can itself be a symlink to an external file/directory.
            if (value.isNotBlank() && !value.startsWith('-') && !inside(File(cwd, value))) return false
            if (value.startsWith('/') || value.contains("../") || value == ".." || value.contains('/')) {
                val prefix = value.takeWhile { it !in "*?[" }
                val file = when {
                    prefix.isEmpty() && value.startsWith('/') -> File("/")
                    prefix.startsWith('/') -> File(prefix)
                    else -> File(cwd, prefix)
                }
                if (!inside(file)) return false
            }
        }
        if (Regex("""(?:^|[\s;&|/])(?:pm|am|cmd|settings|mount|umount|reboot|shutdown|setenforce|insmod|rmmod)\b""").containsMatchIn(command)) return false
        return true
    }
}
