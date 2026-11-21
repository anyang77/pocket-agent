package dev.pocketagent.runtime

/** Startup failure is not proof that Root permission was revoked. Never replay it. */
internal fun commandStartFailure(error: Exception, asRoot: Boolean, durationMs: Long): CommandResult {
    val detail = generateSequence<Throwable>(error) { it.cause }.take(8)
        .mapNotNull { it.message }.distinct().joinToString("; ").ifBlank { error.javaClass.simpleName }
    val hint = when {
        detail.contains("error=7") || detail.contains("E2BIG", ignoreCase = true) ||
            detail.contains("Argument list too long", ignoreCase = true) ->
            "命令参数过长（E2BIG）；请通过暂存文件或标准输入传递大段内容。这不是 Root 未授权。"
        detail.contains("error=13") || detail.contains("EACCES", ignoreCase = true) ||
            detail.contains("Permission denied", ignoreCase = true) ->
            "系统拒绝启动进程，请检查执行权限、SELinux 和 Root 管理器授权。"
        else -> "请检查可执行文件、运行环境和系统限制。"
    }
    return CommandResult(exitCode = -1, output = "", timedOut = false, durationMs = durationMs,
        error = (if (asRoot) "Root 命令启动失败；未降级、未重试。" else "命令启动失败。") +
            " $hint 原始错误：$detail")
}

/** Do not inject app-private loaders into su or the Android system shell. */
internal fun sanitizeRootLauncherEnvironment(environment: MutableMap<String, String>) {
    environment.keys.filter { it == "LD_PRELOAD" || it == "LD_LIBRARY_PATH" || it.startsWith("TERMUX_EXEC__") }
        .forEach(environment::remove)
}
