package dev.pocketagent.agent

import java.io.File

/** Matches command positions and concrete destructive targets, not words in output/source code.
 * This is a static guardrail, not a shell interpreter or filesystem sandbox.
 */
internal object CriticalCommandPolicy {
    private val protected = setOf("/", "/data", "/system", "/vendor", "/product", "/storage", "/sdcard",
        "/storage/emulated/0", "/data/user", "/data/user/0", "/data/data", "/metadata", "/boot", "/efs", "/persist")
    private const val DELETE = "删除根目录或设备级数据目录可能导致系统损坏或全部数据丢失"
    private const val DISK = "原始磁盘写入、格式化或修改分区表可能导致数据永久丢失"
    private const val RESET = "恢复出厂设置或擦除存储会永久删除数据"
    private data class Token(val text: String, val operator: Boolean = false)

    fun reason(command: String, cwd: File): String? = inspect(command, cwd, 0)

    private fun inspect(command: String, initialCwd: File, depth: Int): String? {
        if (depth > 8) return null
        var cwd = initialCwd
        val segment = mutableListOf<Token>()
        val substitutions = mutableListOf<String>()
        val tokens = lex(command, substitutions)
        for (substitution in substitutions) inspect(substitution, cwd, depth + 1)?.let { return it }
        for (token in tokens + Token(";", true)) {
            if (token.operator && token.text in setOf(";", "&", "&&", "||", "|", "(", ")")) {
                if (segment.isNotEmpty()) {
                    inspectSegment(segment, cwd, depth)?.let { return it }
                    val words = segment.filterNot { it.operator }.map { it.text }
                    if (words.firstOrNull() == "cd" && words.size == 2 && !dynamic(words[1])) {
                        cwd = resolve(cwd, words[1])
                    }
                    segment.clear()
                }
            } else segment += token
        }
        return null
    }

    private fun inspectSegment(tokens: List<Token>, cwd: File, depth: Int): String? {
        val words = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.operator && token.text in setOf(">", ">>", ">|", "<", "<<", "<<-", "<<<", "<>", ">&", "<&")) {
                val target = tokens.getOrNull(i + 1)?.text.orEmpty()
                if (token.text in setOf(">", ">>", ">|", "<>") && raw(target, cwd)) return DISK
                i += 2
            } else {
                if (!token.operator) words += token.text
                i++
            }
        }
        while (words.firstOrNull()?.matches(Regex("[A-Za-z_][A-Za-z0-9_]*=.*")) == true) words.removeAt(0)
        // Unwrap known launchers. Quoted data is otherwise never treated as a command.
        while (words.firstOrNull()?.substringAfterLast('/') in setOf("env", "command", "builtin", "exec", "busybox", "toybox", "sudo", "su")) {
            val wrapper = words.removeAt(0).substringAfterLast('/')
            if (wrapper == "command" && words.firstOrNull() in setOf("-v", "-V")) return null
            if (wrapper == "su" && words.any { it == "-c" || it == "--command" }) {
                val index = words.indexOfFirst { it == "-c" || it == "--command" }
                return words.getOrNull(index + 1)?.let { inspect(it, cwd, depth + 1) }
            }
            while (words.firstOrNull()?.startsWith('-') == true ||
                words.firstOrNull()?.matches(Regex("[A-Za-z_][A-Za-z0-9_]*=.*")) == true ||
                (wrapper == "su" && words.firstOrNull() in setOf("0", "root"))) words.removeAt(0)
        }
        val name = words.firstOrNull()?.substringAfterLast('/') ?: return null
        val args = words.drop(1)
        if (name in setOf("sh", "bash", "dash", "zsh", "ksh")) {
            val index = args.indexOfFirst { it.startsWith('-') && !it.startsWith("--") && 'c' in it }
            if (index >= 0) return args.getOrNull(index + 1)?.let { inspect(it, cwd, depth + 1) }
        }
        if (name == "rm" || name == "shred") {
            var options = true
            for (arg in args) {
                if (options && arg == "--") { options = false; continue }
                if (options && arg.startsWith('-')) continue
                if (!dynamic(arg) && deviceWide(arg, cwd)) return DELETE
            }
        }
        if (name == "find" && args.any { it == "-delete" || it == "-exec" || it == "-execdir" } &&
            ("-delete" in args || args.windowed(2).any { it[0] in setOf("-exec", "-execdir") && it[1].substringAfterLast('/') == "rm" }) &&
            args.takeWhile { !it.startsWith('-') }.any { deviceWide(it, cwd) }) return DELETE
        if (name == "dd" && args.any { it.startsWith("of=") && raw(it.substringAfter('='), cwd) }) return DISK
        if (name in setOf("cp", "mv", "install", "tee", "cat", "shred")) {
            val targets = args.filterNot { it.startsWith('-') }
            val writesRaw = when (name) {
                "cat" -> false // Output redirections were checked separately.
                "tee", "shred" -> targets.any { raw(it, cwd) }
                else -> targets.lastOrNull()?.let { raw(it, cwd) } == true ||
                    args.windowed(2).any { it[0] in setOf("-t", "--target-directory") && raw(it[1], cwd) }
            }
            if (writesRaw) return DISK
        }
        val rawTarget = args.any { raw(it, cwd) }
        val help = args.any { it in setOf("--help", "-h", "--version", "-V") }
        if (!help) {
            if ((name.startsWith("mkfs") || name in setOf("mke2fs", "newfs", "blkdiscard", "flash_erase", "flash_image", "nandwrite")) &&
                rawTarget && "-n" !in args) return DISK
            if (name in setOf("fdisk", "sfdisk", "cfdisk", "gdisk", "sgdisk", "parted", "wipefs") && rawTarget) {
                val readOnly = when (name) {
                    "fdisk" -> args.any { it in setOf("-l", "--list", "-s", "--getsz") }
                    "sfdisk" -> args.any { it in setOf("-l", "--list", "-d", "--dump", "--verify", "--json") }
                    "sgdisk" -> args.filterNot { raw(it, cwd) }.all {
                        it in setOf("-p", "--print", "-v", "--verify") || it.startsWith("--info=")
                    }
                    "parted" -> args.lastOrNull() in setOf("print", "devices", "free") &&
                        args.none { it in setOf("mklabel", "mktable", "mkpart", "rm", "resizepart", "rescue", "set", "name") }
                    "wipefs" -> args.any { it in setOf("-n", "--no-act") } || args.none { it in setOf("-a", "--all", "-o", "--offset") || it.startsWith("--offset=") }
                    else -> false
                }
                if (!readOnly) return DISK
            }
            if (name == "fastboot" && args.any { it in setOf("flash", "flashall", "erase", "format", "update", "-w", "boot", "unlock", "lock") || it.startsWith("format:") }) return DISK
            if (name == "sm" && args.firstOrNull() in setOf("format", "partition")) return RESET
            if (name == "recovery" && args.any { it.startsWith("--wipe") }) return RESET
            if (name in setOf("am", "cmd") && args.any {
                    it.uppercase() in setOf("ANDROID.INTENT.ACTION.MASTER_CLEAR", "ANDROID.INTENT.ACTION.FACTORY_RESET")
                }) return RESET
        }
        return null
    }

    private fun dynamic(path: String) = path.any { it in "$`" }
    private fun resolve(cwd: File, path: String): File =
        (if (path.startsWith('/')) File(path) else File(cwd, path)).canonicalFile
    private fun deviceWide(path: String, cwd: File): Boolean {
        if (dynamic(path)) return false
        val prefix = path.takeWhile { it !in "*?[" }.trimEnd('/')
        return resolve(cwd, prefix.ifEmpty { if (path.startsWith('/')) "/" else "." }).path in protected
    }
    private fun raw(path: String, cwd: File): Boolean {
        if (path.isEmpty() || dynamic(path) || path.startsWith('-')) return false
        val target = resolve(cwd, path).path
        return target == "/dev/block" || target.startsWith("/dev/block/") ||
            target.startsWith("/dev/mapper/") || Regex("/dev/(?:sd[a-z]|hd[a-z]|mmcblk|nvme|mtd|disk/).*").matches(target)
    }

    /** Small lexical scanner: quotes/comments/heredoc data are not executable command positions. */
    private fun lex(text: String, substitutions: MutableList<String>): List<Token> {
        val result = mutableListOf<Token>()
        val word = StringBuilder()
        var started = false
        var quote: Char? = null
        var i = 0
        val heredocs = mutableListOf<Pair<String, Boolean>>()
        var expectHeredoc: Boolean? = null
        fun flush() {
            if (started) {
                val value = word.toString()
                result += Token(value)
                expectHeredoc?.let { heredocs += value to it; expectHeredoc = null }
                word.setLength(0); started = false
            }
        }
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && quote != '\'' && i + 1 < text.length) {
                if (text[i + 1] != '\n') { word.append(text[i + 1]); started = true }
                i += 2; continue
            }
            // Command substitution executes even inside double quotes, but not single quotes.
            if (quote != '\'' && (c == '`' || (c == '$' && text.getOrNull(i + 1) == '(' && text.getOrNull(i + 2) != '('))) {
                val start = i + if (c == '`') 1 else 2
                var end = start
                var nesting = 1
                var innerQuote: Char? = null
                while (end < text.length) {
                    val ch = text[end]
                    if (ch == '\\' && innerQuote != '\'') { end += 2; continue }
                    if (c == '`') { if (ch == '`') break }
                    else if (innerQuote != null) { if (ch == innerQuote) innerQuote = null }
                    else if (ch == '\'' || ch == '"') innerQuote = ch
                    else if (ch == '(') nesting++
                    else if (ch == ')' && --nesting == 0) break
                    end++
                }
                if (end < text.length) {
                    substitutions += text.substring(start, end)
                    word.append(text.substring(i, end + 1)); started = true
                    i = end + 1; continue
                }
            }
            if (quote != null) {
                if (c == quote) quote = null else word.append(c)
                i++; continue
            }
            if (c == '\'' || c == '"') { quote = c; started = true; i++; continue }
            if (c == '#' && !started) {
                while (i < text.length && text[i] != '\n') i++
                continue
            }
            if (c.isWhitespace()) {
                flush(); i++
                if (c == '\n') {
                    result += Token(";", true)
                    for ((delimiter, stripTabs) in heredocs) {
                        while (i < text.length) {
                            val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                            val line = text.substring(i, end)
                            i = (end + 1).coerceAtMost(text.length)
                            if ((if (stripTabs) line.trimStart('\t') else line) == delimiter) break
                        }
                    }
                    heredocs.clear()
                }
                continue
            }
            if (c in ";&|()<>") {
                flush()
                val op = listOf("<<-", "<<<", ">>", "<<", ">|", ">&", "<&", "<>", "&&", "||").firstOrNull { text.startsWith(it, i) } ?: c.toString()
                result += Token(op, true)
                if (op == "<<" || op == "<<-") expectHeredoc = op == "<<-"
                i += op.length; continue
            }
            word.append(c); started = true; i++
        }
        flush()
        return result
    }
}
