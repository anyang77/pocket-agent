package dev.pocketagent.agent

enum class CommandRisk {
    SAFE,
    NETWORK,
    DESTRUCTIVE,
    UNKNOWN,
    BLOCKED,
}

data class CommandAssessment(
    val risk: CommandRisk,
    val reason: String? = null,
)

data class PolicyDecision(
    val allowed: Boolean,
    val reason: String? = null,
)

object CommandPolicy {
    private val networkPatterns = listOf(
        Regex("""\b(curl|wget|nc|netcat)\b"""),
        Regex("""\b(pip|pip3)\s+(install|uninstall)\b"""),
        Regex("""\b(npm|pnpm|yarn)\s+(install|add|remove|uninstall|publish)\b"""),
        Regex("""\b(apt|apt-get|pkg)\s+(install|update|upgrade|remove|uninstall)\b"""),
        Regex("""\b(git\s+(clone|pull|fetch)|cargo\s+install|go\s+install)\b"""),
    )

    private val destructivePatterns = listOf(
        Regex("""(^|[\s/])rm\b"""),
        Regex("""\bgit\s+(reset|checkout|clean|push|remote|config)\b"""),
        Regex("""\b(dd|mkfs|fdisk|parted|wipe|format)\b"""),
        Regex("""\b(reboot|shutdown|poweroff|halt)\b"""),
        Regex("""\b(mount|umount|setenforce|chroot|insmod|rmmod)\b"""),
        Regex("""\bpm\s+(uninstall|clear|disable|disable-user|force-stop)\b"""),
        Regex("""\bcmd\s+(power|package|activity|device_policy)\b"""),
        Regex("""\bsettings\s+(put|delete|reset)\b"""),
        Regex("""\b(systemctl|service|killall|pkill)\b"""),
    )

    private val allowedPrefixes = listOf(
        "pwd", "ls", "find", "cat", "head", "tail", "wc", "grep", "rg", "file",
        "git status", "git diff", "git log", "git show", "git ls-files",
        "python", "python3", "pytest", "node",
        "npm test", "npm run", "pnpm test", "pnpm run", "yarn test", "yarn run",
        "go test", "go build", "go vet",
        "cargo test", "cargo build", "cargo check", "rustc",
        "gcc", "clang", "cc", "make", "cmake", "javac", "gradle", "./gradlew",
        "mkdir", "touch", "cp", "mv",
    )

    fun assess(command: String): CommandAssessment {
        val normalized = command.trim()
        if (normalized.isBlank()) {
            return CommandAssessment(CommandRisk.BLOCKED, "命令为空")
        }
        if (normalized.indexOf('\u0000') >= 0) {
            return CommandAssessment(CommandRisk.BLOCKED, "命令包含非法空字符")
        }
        destructivePatterns.firstOrNull { it.containsMatchIn(normalized) }?.let {
            return CommandAssessment(CommandRisk.DESTRUCTIVE, "命令可能修改系统、包或设备状态")
        }
        networkPatterns.firstOrNull { it.containsMatchIn(normalized) }?.let {
            return CommandAssessment(CommandRisk.NETWORK, "命令需要网络或安装依赖")
        }
        val allowed = allowedPrefixes.any { prefix ->
            normalized == prefix || normalized.startsWith("$prefix ")
        }
        return if (allowed) {
            CommandAssessment(CommandRisk.SAFE)
        } else {
            CommandAssessment(CommandRisk.UNKNOWN, "命令不在内置白名单中")
        }
    }

    fun check(command: String): PolicyDecision {
        val assessment = assess(command)
        return PolicyDecision(
            allowed = assessment.risk != CommandRisk.BLOCKED,
            reason = assessment.reason,
        )
    }

    fun isSafeRead(command: String): Boolean {
        val normalized = command.trim()
        return normalized.startsWith("cat ") ||
            normalized.startsWith("head ") ||
            normalized.startsWith("tail ") ||
            normalized.startsWith("grep ") ||
            normalized.startsWith("rg ")
    }
}
