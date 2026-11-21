package dev.pocketagent.agent

import java.io.File

/** Agent-entry checks, not an OS sandbox. cwd alone does not confine a shell. */
object CommandSafety {
    fun criticalReason(command: String, cwd: File): String? {
        val text = command.replace("'", "").replace("\"", "").replace("\\", "")
        if (Regex("""\b(mkfs(?:\.[\w-]+)?|mke2fs|newfs|fdisk|sfdisk|cfdisk|parted|sgdisk|gdisk|wipefs|blkdiscard|fastboot|flash_erase|flash_image|nandwrite)\b""").containsMatchIn(text)) {
            return "格式化、刷写或修改分区表可能导致设备无法启动或数据永久丢失"
        }
        val rawDevice = Regex("""/dev/(?:block|disk|sd[a-z]|mmcblk|nvme|mapper|mtd)""").containsMatchIn(text)
        if ((rawDevice && Regex("""(?:>|\bof=|\b(?:cp|mv|tee|shred|flash|erase)\b)""").containsMatchIn(text)) ||
            Regex("""\bshred\b.*\s/(?:\s|$)""").containsMatchIn(text)) {
            return "原始磁盘/分区操作可能不可恢复"
        }
        if (Regex("""\b(?:recovery\s+--wipe|master_clear|factory_reset|wipe_data|sm\s+(?:partition|format))\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "恢复出厂设置或擦除存储会永久删除数据"
        }
        val protected = setOf("/", "/data", "/system", "/vendor", "/product", "/storage", "/sdcard", "/storage/emulated/0", "/data/user", "/data/user/0", "/data/data", "/metadata", "/boot", "/efs", "/persist")
        if (Regex("""\bfind\s+/(?:\s|\*)[^;\n]*(?:-delete|-exec\s+rm)""").containsMatchIn(text)) {
            return "递归删除整个设备的文件可能导致系统损坏"
        }
        val changesToProtectedDir = Regex("""\bcd\s+([^;|&\n]+)""").findAll(text).any {
            val path = it.groupValues[1].trim()
            (if (path.startsWith('/')) File(path) else File(cwd, path)).canonicalPath in protected
        }
        if (changesToProtectedDir && Regex("""\brm\s+[^;\n]*(?:\*|\s\.\.?\s*$)""").containsMatchIn(text)) {
            return "切换到设备级目录后批量删除文件可能导致全部数据丢失"
        }
        val deletions = Regex("""(?:^|[\s;|&])(?:rm|/\S*/rm)\s+([^;|&\n]+)""")
        for (match in deletions.findAll(text)) {
            for (arg in match.groupValues[1].split(Regex("""\s+"""))) {
                if (arg.isBlank() || arg.startsWith("-")) continue
                if (arg.any { it in "$`{}" } || arg.startsWith("~")) return "删除目标动态生成，可能展开为根目录或设备数据目录"
                val prefix = arg.takeWhile { it !in "*?[" }.trimEnd('/')
                val target = when {
                    prefix.isEmpty() && arg.startsWith('/') -> File("/")
                    prefix.isEmpty() -> cwd.canonicalFile
                    prefix.startsWith('/') -> File(prefix).canonicalFile
                    else -> File(cwd, prefix).canonicalFile
                }
                if (target.path in protected) {
                    return "删除根目录或设备级数据目录可能导致系统损坏或全部数据丢失"
                }
            }
        }
        return null
    }

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
