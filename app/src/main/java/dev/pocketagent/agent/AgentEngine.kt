package dev.pocketagent.agent

import dev.pocketagent.data.AiSettings
import dev.pocketagent.data.AuditLog
import dev.pocketagent.data.WorkspaceRepository
import dev.pocketagent.runtime.CommandRunner
import dev.pocketagent.runtime.SystemAccessRepository
import dev.pocketagent.runtime.SystemPathType
import dev.pocketagent.runtime.SystemReadPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class AgentEngine(
    private val workspace: WorkspaceRepository,
    private val commandRunner: CommandRunner,
    private val systemAccess: SystemAccessRepository,
    private val rootAvailable: () -> Boolean = { false },
    private val aiClient: AiClient = AiClient(),
    private val auditLog: AuditLog,
) {
    private val conversation = mutableListOf<AgentMessage>()

    fun reset() {
        conversation.clear()
    }

    fun loadHistory(history: List<AgentMessage>) {
        conversation.clear()
        conversation += AgentMessage(role = "system", content = buildSystemPrompt())
        conversation += history
            .filter { it.role == "user" || it.role == "assistant" }
            .filter { !it.content.isNullOrBlank() }
            .takeLast(MAX_HISTORY_MESSAGES)
    }

    fun loadConversation(history: List<AgentMessage>) {
        conversation.clear()
        conversation += AgentMessage(role = "system", content = buildSystemPrompt())
        val hasLegacyToolCalls = history.any {
            it.role == "assistant" &&
                it.toolCalls.isNotEmpty() &&
                it.reasoningContent.isNullOrBlank()
        }
        val safeHistory = if (hasLegacyToolCalls) {
            auditLog.append(
                "conversation",
                "迁移旧思考记录",
                "移除缺少 reasoning_content 的历史工具轮次",
                "compatible",
            )
            history.filter { message ->
                message.role != "tool" &&
                    !(message.role == "assistant" && message.toolCalls.isNotEmpty())
            }
        } else {
            history
        }
        conversation += safeHistory.filter {
            it.role == "user" || it.role == "assistant" || it.role == "tool"
        }
    }

    fun snapshotHistory(): List<AgentMessage> =
        conversation.filter { it.role != "system" }.toList()

    suspend fun run(
        userText: String,
        settings: AiSettings,
        authorize: suspend (PermissionRequest) -> PermissionDecision,
        onEvent: (AgentEvent) -> Unit,
    ) {
        ensureSystemMessage()
        conversation += AgentMessage(role = "user", content = userText)
        auditLog.append("chat", "用户消息", userText, "received")

        var step = 0
        var lastRetryCount = 0
        try {
            var lastPromptTokens = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val contextLimit = ModelContextBudget.limitFor(
                    settings.model,
                    settings.contextWindow,
                )
                val effectivePromptTokens = lastPromptTokens.takeIf { it > 0 }
                    ?: estimateConversationTokens()
                if (effectivePromptTokens >= contextLimit * CONTEXT_COMPACT_RATIO) {
                    val usage = compactConversation(
                        settings = settings,
                        originalUserText = userText,
                        beforeTokens = effectivePromptTokens,
                        contextLimit = contextLimit,
                        onEvent = onEvent,
                    )
                    lastPromptTokens = 0
                    usage?.let { onEvent(AgentEvent.Usage(it)) }
                }
                step++
                onEvent(
                    AgentEvent.State(
                        if (step == 1) {
                            "正在分析项目"
                        } else {
                            "正在继续处理 · 第 $step 轮"
                        },
                    ),
                )
                auditLog.append(
                    "model",
                    "请求模型",
                    "model=${settings.model} step=$step messages=${conversation.size}",
                )
                onEvent(
                    AgentEvent.ModelRequestStarted(
                        model = settings.model,
                        round = step,
                        messageCount = conversation.size,
                    ),
                )
                val requestStartedAt = System.currentTimeMillis()
                lastRetryCount = 0
                var streamedChars = 0
                var reasoningChars = 0
                val pendingDelta = StringBuilder()
                var lastDeltaAt = 0L
                fun flushStreamText() {
                    if (pendingDelta.isEmpty()) return
                    onEvent(AgentEvent.AssistantDelta(pendingDelta.toString()))
                    pendingDelta.clear()
                    lastDeltaAt = System.nanoTime()
                }
                val onDelta: (String) -> Unit = { delta ->
                    streamedChars += delta.length
                    pendingDelta.append(delta)
                    val now = System.nanoTime()
                    if (
                        pendingDelta.length >= 80 ||
                        now - lastDeltaAt >= 50_000_000L
                    ) {
                        flushStreamText()
                    }
                }
                val onReasoningDelta: (String) -> Unit = { delta ->
                    val before = reasoningChars / 200
                    reasoningChars += delta.length
                    if (reasoningChars / 200 != before) {
                        onEvent(AgentEvent.ModelReasoningProgress(reasoningChars))
                    }
                }
                val turn = try {
                    aiClient.complete(
                        settings,
                        conversation,
                        TOOLS,
                        onRetry = { notice ->
                            lastRetryCount = notice.attempt
                            onEvent(AgentEvent.RetryScheduled(notice))
                        },
                        onDelta = onDelta,
                        onReasoningDelta = onReasoningDelta,
                    )
                } catch (error: AgentException) {
                    currentCoroutineContext().ensureActive()
                    if (!error.message.orEmpty().contains("reasoning_content", ignoreCase = true)) {
                        throw error
                    }
                    auditLog.append(
                        "conversation",
                        "修复思考上下文",
                        "移除缺少 reasoning_content 的旧工具轮次后重试",
                        "retry",
                    )
                    sanitizeLegacyReasoningHistory()
                    onEvent(AgentEvent.State("思考上下文不兼容，已清理旧记录并重试"))
                    aiClient.complete(
                        settings,
                        conversation,
                        TOOLS,
                        onRetry = { notice ->
                            lastRetryCount = notice.attempt
                            onEvent(AgentEvent.RetryScheduled(notice))
                        },
                        onDelta = onDelta,
                        onReasoningDelta = onReasoningDelta,
                    )
                }
                flushStreamText()
                onEvent(
                    AgentEvent.ModelRequestFinished(
                        round = step,
                        elapsedMs = System.currentTimeMillis() - requestStartedAt,
                        contentChars = turn.content?.length ?: streamedChars,
                        toolCallCount = turn.toolCalls.size,
                        retryCount = lastRetryCount,
                    ),
                )
                auditLog.append(
                    "model",
                    "模型响应",
                    "content=${turn.content?.take(1_500).orEmpty()}",
                    buildString {
                        append("tool_calls=${turn.toolCalls.size}")
                        turn.usage?.let {
                            append(" prompt=${it.promptTokens}")
                            append(" cache_hit=${it.cacheHitTokens}")
                            append(" completion=${it.completionTokens}")
                        }
                    },
                )
                turn.usage?.let { onEvent(AgentEvent.Usage(it)) }
                lastPromptTokens = turn.usage?.promptTokens ?: estimateConversationTokens()
                conversation += AgentMessage(
                    role = "assistant",
                    content = turn.content,
                    reasoningContent = turn.reasoningContent,
                    toolCalls = turn.toolCalls,
                )
                if (turn.toolCalls.isEmpty()) {
                    if (!turn.contentWasStreamed) {
                        turn.content?.takeIf { it.isNotBlank() }?.let {
                            onEvent(AgentEvent.AssistantText(it))
                        }
                    }
                    auditLog.append("agent", "任务完成", "模型未再请求工具", "success")
                    onEvent(AgentEvent.Completed)
                    return
                }
                turn.content?.takeIf { it.isNotBlank() }?.let {
                    onEvent(
                        AgentEvent.State(
                            it.lineSequence()
                                .firstOrNull { line -> line.isNotBlank() }
                                ?.trim()
                                ?.take(100)
                                ?: "正在继续处理",
                        ),
                    )
                }

                for (call in turn.toolCalls) {
                    currentCoroutineContext().ensureActive()
                    val summary = summarizeArguments(call.arguments)
                    auditLog.append("tool", "调用 ${call.name}", summary)
                    onEvent(AgentEvent.ToolStarted(call.name, summary))
                    val result = try {
                        executeTool(call, authorize)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        ToolResult(
                            content = "TOOL_ERROR: ${error.message ?: error.javaClass.simpleName}",
                            success = false,
                            preview = "工具调用失败",
                        )
                    }
                    conversation += AgentMessage(
                        role = "tool",
                        content = result.content.take(MAX_TOOL_RESULT_CHARS),
                        name = call.name,
                        toolCallId = call.id,
                    )
                    onEvent(
                        AgentEvent.ToolFinished(
                            name = call.name,
                            summary = result.preview,
                            success = result.success,
                        ),
                    )
                    result.plan?.let { onEvent(AgentEvent.PlanUpdated(it)) }
                }
            }
        } catch (error: CancellationException) {
            auditLog.append("agent", "任务已停止", "用户主动停止")
            onEvent(AgentEvent.Stopped("已停止当前任务"))
            throw error
        } catch (error: Exception) {
            if (!currentCoroutineContext().isActive) {
                auditLog.append("agent", "任务已停止", "取消信号已结束执行")
                onEvent(AgentEvent.Stopped("已停止当前任务"))
                throw CancellationException("已停止当前任务")
            }
            auditLog.append("agent", "任务失败", error.message ?: error.javaClass.simpleName)
            onEvent(
                AgentEvent.Failed(
                    message = error.message ?: "Agent 执行失败",
                    retryCount = lastRetryCount,
                ),
            )
        }
    }

    private suspend fun compactConversation(
        settings: AiSettings,
        originalUserText: String,
        beforeTokens: Int,
        contextLimit: Int,
        onEvent: (AgentEvent) -> Unit,
    ): TokenUsage? {
        val startedAt = System.currentTimeMillis()
        auditLog.append(
            "agent",
            "自动压缩上下文",
            "before=$beforeTokens limit=$contextLimit messages=${conversation.size}",
            "started",
        )
        onEvent(
            AgentEvent.ContextCompactionStarted(
                beforeTokens = beforeTokens,
                contextLimit = contextLimit,
                messageCount = conversation.size,
            ),
        )
        onEvent(
            AgentEvent.State(
                "正在压缩上下文 · ${contextPercent(beforeTokens, contextLimit)}% · 可随时停止",
            ),
        )
        val compactionRequest = AgentMessage(
            role = "user",
            content = """
                [Pocket Agent runtime notice]
                You are compacting the active coding task so it can continue without losing state.
                Produce a dense checkpoint, not a user-facing answer. Preserve:
                - the user's original goal and constraints,
                - decisions and files changed, with exact paths,
                - commands/tests already run and their exact outcomes (especially failures),
                - important tool findings and errors,
                - unfinished work and the next concrete actions.
                Do not invent results. Keep the checkpoint under 4,000 words.
                Use the same language as the user's task.

                Original user request:
                $originalUserText
            """.trimIndent(),
        )
        val contextSnapshot = conversation.toList()
        conversation += compactionRequest
        val turn = try {
            try {
                aiClient.complete(
                    settings,
                    conversation,
                    emptyList(),
                    onRetry = { notice ->
                        onEvent(
                            AgentEvent.State(
                                "压缩请求：${notice.reason}，" +
                                    "${notice.delaySeconds} 秒后重试",
                            ),
                        )
                    },
                )
            } catch (error: AgentException) {
                if (!error.message.orEmpty().contains("reasoning_content", ignoreCase = true)) {
                    throw error
                }
                auditLog.append(
                    "conversation",
                    "修复压缩上下文",
                    "移除缺少 reasoning_content 的旧消息后重试压缩",
                    "retry",
                )
                sanitizeLegacyReasoningHistory()
                conversation += compactionRequest
                aiClient.complete(
                    settings,
                    conversation,
                    emptyList(),
                    onRetry = { notice ->
                        onEvent(
                            AgentEvent.State(
                                "压缩请求：${notice.reason}，" +
                                    "${notice.delaySeconds} 秒后重试",
                            ),
                        )
                    },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            auditLog.append(
                "agent",
                "自动压缩降级",
                error.message ?: error.javaClass.simpleName,
                "fallback",
            )
            null
        } finally {
            conversation.removeAll { it == compactionRequest }
        }
        val fallback = turn?.content.isNullOrBlank()
        val checkpoint = turn?.content?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: buildLocalCheckpoint(originalUserText, contextSnapshot)
        auditLog.append(
            "model",
            if (fallback) "生成本地兜底检查点" else "生成上下文检查点",
            "chars=${checkpoint.length}",
            buildString {
                turn?.usage?.let {
                    append("prompt=${it.promptTokens}")
                    append(" cache_hit=${it.cacheHitTokens}")
                    append(" completion=${it.completionTokens}")
                }
            },
        )

        val systemMessage = contextSnapshot.firstOrNull { it.role == "system" }
            ?: AgentMessage(role = "system", content = buildSystemPrompt())
        conversation.clear()
        conversation += systemMessage
        conversation += AgentMessage(
            role = "user",
            content = """
                [Pocket Agent compacted checkpoint]
                Continue the active task from the summary below. Use tools when needed. Do not ask the
                user to repeat information that is already present here.

                Original user request:
                $originalUserText

                Checkpoint:
                $checkpoint
            """.trimIndent(),
        )
        val afterTokens = estimateConversationTokens()
        val elapsedMs = System.currentTimeMillis() - startedAt
        auditLog.append(
            "agent",
            "上下文压缩完成",
            "before=$beforeTokens after_estimate=$afterTokens " +
                "checkpoint_chars=${checkpoint.length} fallback=$fallback " +
                "elapsed_ms=$elapsedMs",
            if (fallback) "fallback" else "success",
        )
        onEvent(
            AgentEvent.ContextCompacted(
                beforeTokens = beforeTokens,
                afterTokens = afterTokens,
                contextLimit = contextLimit,
                summaryChars = checkpoint.length,
                elapsedMs = elapsedMs,
                fallback = fallback,
            ),
        )
        return turn?.usage
    }

    private fun buildLocalCheckpoint(
        originalUserText: String,
        snapshot: List<AgentMessage>,
    ): String {
        val transcript = snapshot
            .asSequence()
            .filter { it.role != "system" }
            .toList()
            .takeLast(LOCAL_CHECKPOINT_MESSAGES)
            .joinToString("\n\n") { message ->
                val body = message.content.orEmpty()
                    .replace("\r\n", "\n")
                    .take(LOCAL_CHECKPOINT_MESSAGE_CHARS)
                buildString {
                    append("[${message.role.uppercase()}]")
                    message.name?.let { append(" $it") }
                    append('\n')
                    append(body)
                    if (message.toolCalls.isNotEmpty()) {
                        append("\nTool calls: ")
                        append(
                            message.toolCalls.joinToString { call ->
                                "${call.name}(${call.arguments.take(600)})"
                            },
                        )
                    }
                }
            }
        return """
            The model-generated checkpoint was unavailable. This is a deterministic fallback
            containing the original request and the most recent task transcript.

            Original request:
            ${originalUserText.take(8_000)}

            Recent transcript:
            ${transcript.take(LOCAL_CHECKPOINT_MAX_CHARS)}
        """.trimIndent()
    }

    private fun sanitizeLegacyReasoningHistory() {
        conversation.removeAll { message ->
            message.role == "tool" ||
                (
                    message.role == "assistant" &&
                        message.reasoningContent.isNullOrBlank()
                    )
        }
    }

    private fun estimateConversationTokens(): Int = conversation.sumOf { message ->
        val contentChars = message.content?.length ?: 0
        val toolChars = message.toolCalls.sumOf { it.arguments.length + it.name.length }
        (contentChars + toolChars) / 3 + 8
    }

    private fun contextPercent(tokens: Int, contextLimit: Int): Int =
        ((tokens.toFloat() / contextLimit.toFloat()) * 100f).toInt().coerceIn(0, 100)

    private suspend fun executeTool(
        call: AgentToolCall,
        authorize: suspend (PermissionRequest) -> PermissionDecision,
    ): ToolResult {
        val arguments = JSONObject(call.arguments.ifBlank { "{}" })
        return when (call.name) {
            "update_plan" -> {
                val planArray = arguments.optJSONArray("plan") ?: JSONArray()
                val plan = buildList {
                    for (index in 0 until planArray.length()) {
                        val item = planArray.optJSONObject(index) ?: continue
                        val step = item.optString("step").trim()
                        if (step.isBlank()) continue
                        val status = when (item.optString("status").lowercase()) {
                            "in_progress" -> AgentPlanStatus.IN_PROGRESS
                            "completed" -> AgentPlanStatus.COMPLETED
                            else -> AgentPlanStatus.PENDING
                        }
                        add(AgentPlanStep(step.take(240), status))
                    }
                }.take(20)
                require(plan.isNotEmpty()) { "计划至少需要一个步骤" }
                auditLog.append(
                    "plan",
                    "更新任务计划",
                    plan.joinToString(" | ") { "${it.status}: ${it.step}" },
                    "success",
                )
                ToolResult(
                    "PLAN_UPDATED steps=${plan.size}",
                    success = true,
                    preview = "计划已更新 · ${plan.size} 步",
                    plan = plan,
                )
            }

            "list_files" -> {
                val path = arguments.optString("path", ".")
                val base = workspace.resolve(path)
                require(base.isDirectory) { "目录不存在: $path" }
                val prefix = base.relativeTo(workspace.root).invariantSeparatorsPath
                val recursive = arguments.optBoolean("recursive", false)
                val files = if (recursive) {
                    workspace.listFiles()
                        .filter { entry ->
                            prefix.isBlank() || prefix == "." ||
                                entry.relativePath.startsWith("$prefix/")
                        }
                        .take(300)
                } else {
                    workspace.listChildren(path).take(300)
                }
                val text = files.joinToString("\n") {
                    if (it.isDirectory) "${it.relativePath}/" else it.relativePath
                }
                auditLog.append("file", "列目录", "path=$path count=${files.size}", "success")
                ToolResult(text.ifBlank { "(目录为空)" }, success = true, preview = "${files.size} 个条目")
            }

            "read_file" -> {
                val path = arguments.optString("path")
                val text = workspace.readText(path, maxChars = 80_000)
                auditLog.append("file", "读取文件", "path=$path chars=${text.length}", "success")
                ToolResult(text, success = true, preview = path)
            }

            "search_files" -> {
                val query = arguments.optString("query")
                val path = arguments.optString("path", ".")
                require(query.isNotBlank()) { "query 不能为空" }
                val target = workspace.resolve(path)
                require(target.exists()) { "搜索路径不存在: $path" }
                val result = commandRunner.run(
                    command = "grep -RInF --exclude-dir=.git -- " +
                        "${shellQuote(query)} ${shellQuote(target.absolutePath)}",
                    workingDir = workspace.root,
                    timeoutMs = 30_000,
                )
                val output = result.output.take(40_000)
                auditLog.append(
                    "file",
                    "搜索代码",
                    "path=$path query=$query matches=${output.lineSequence().count()}",
                    "exit=${result.exitCode}",
                )
                if (result.exitCode == 1 && output.isBlank()) {
                    ToolResult("NO_MATCHES", success = true, preview = "没有匹配")
                } else {
                    require(result.exitCode == 0 && !result.timedOut) {
                        output.ifBlank { result.error ?: "搜索失败" }
                    }
                    ToolResult(
                        output,
                        success = true,
                        preview = "${output.lineSequence().count()} 行匹配",
                    )
                }
            }

            "edit_file" -> {
                val path = arguments.optString("path")
                val oldText = arguments.optString("old_text")
                val newText = arguments.optString("new_text")
                val replaceAll = arguments.optBoolean("replace_all", false)
                require(path.isNotBlank()) { "path 不能为空" }
                require(oldText.isNotBlank()) { "old_text 不能为空" }
                val original = workspace.readText(
                    path = path,
                    maxChars = WorkspaceRepository.MAX_FILE_CHARS,
                    requireComplete = true,
                )
                val occurrences = countOccurrences(original, oldText)
                require(occurrences > 0) { "没有找到 old_text" }
                require(replaceAll || occurrences == 1) {
                    "old_text 出现 $occurrences 次，请提供更多上下文或设置 replace_all"
                }
                val updated = if (replaceAll) {
                    original.replace(oldText, newText)
                } else {
                    original.replaceFirst(oldText, newText)
                }
                val permission = authorize(
                    PermissionRequest(
                        kind = PermissionKind.WRITE_FILES,
                        title = "局部修改文件",
                        detail = "path=$path\nreplacements=$occurrences\n" +
                            "removed=${oldText.length} added=${newText.length}\n" +
                            "sha256=${sha256(updated)}",
                        risk = "workspace-edit",
                    ),
                )
                if (!permission.allowed) {
                    auditLog.append("file", "拒绝局部修改", "path=$path", permission.reason)
                    return ToolResult(
                        "DENIED_BY_USER: ${permission.reason}",
                        success = false,
                        preview = "局部修改未获授权",
                    )
                }
                workspace.writeText(path, updated)
                auditLog.append(
                    "file",
                    "局部修改文件",
                    "path=$path replacements=$occurrences chars=${updated.length} " +
                        "sha256=${sha256(updated)}",
                    "success",
                )
                ToolResult(
                    "Edited $path: replaced $occurrences occurrence(s)",
                    success = true,
                    preview = "$path · $occurrences 处修改",
                )
            }

            "device_info" -> {
                val permission = authorizeSystemRead(
                    PermissionRequest(
                        kind = PermissionKind.SYSTEM_READ,
                        title = "读取设备信息",
                        detail = "读取型号、Android 版本、CPU、内存、存储、屏幕和运行环境",
                        risk = "device-info",
                    ),
                    authorize,
                )
                if (!permission.allowed) {
                    return ToolResult(
                        "DENIED_BY_USER: ${permission.reason}",
                        success = false,
                        preview = "设备信息读取未获授权",
                    )
                }
                val text = systemAccess.deviceInfo()
                auditLog.append(
                    "system",
                    "读取设备信息",
                    "root=${rootAvailable()} chars=${text.length} sha256=${sha256(text)}",
                    "success",
                )
                ToolResult(text, success = true, preview = "设备信息 ${text.length} 字符")
            }

            "list_system_dir" -> {
                val path = arguments.optString("path")
                val root = rootAvailable()
                val decision = SystemReadPolicy.check(path, root, SystemPathType.DIRECTORY)
                if (!decision.allowed) {
                    auditLog.append(
                        "system",
                        "拒绝列出系统目录",
                        "path=$path root=$root",
                        decision.reason,
                    )
                    return ToolResult(
                        "DENIED_BY_POLICY: ${decision.reason}",
                        success = false,
                        preview = "目录不符合读取策略",
                    )
                }
                val permission = authorizeSystemRead(
                    PermissionRequest(
                        kind = PermissionKind.SYSTEM_READ,
                        title = "列出系统目录",
                        detail = "path=${decision.normalizedPath}\nroot=$root\nmode=read-only",
                        risk = if (root) "system-read-root" else "system-read",
                    ),
                    authorize,
                )
                if (!permission.allowed) {
                    return ToolResult(
                        "DENIED_BY_USER: ${permission.reason}",
                        success = false,
                        preview = "目录读取未获授权",
                    )
                }
                val result = systemAccess.list(decision.normalizedPath, root)
                auditLog.append(
                    "system",
                    "列出系统目录",
                    "path=${decision.normalizedPath} root=${result.usedRoot} chars=${result.output.length}",
                    "success",
                )
                ToolResult(
                    result.output,
                    success = true,
                    preview = "${decision.normalizedPath} · ${result.output.lineSequence().count()} 行",
                )
            }

            "read_system_file" -> {
                val path = arguments.optString("path")
                val root = rootAvailable()
                val decision = SystemReadPolicy.check(path, root, SystemPathType.FILE)
                if (!decision.allowed) {
                    auditLog.append(
                        "system",
                        "拒绝读取系统文件",
                        "path=$path root=$root",
                        decision.reason,
                    )
                    return ToolResult(
                        "DENIED_BY_POLICY: ${decision.reason}",
                        success = false,
                        preview = "文件不符合读取策略",
                    )
                }
                val permission = authorizeSystemRead(
                    PermissionRequest(
                        kind = PermissionKind.SYSTEM_READ,
                        title = "读取系统文件",
                        detail = "path=${decision.normalizedPath}\nroot=$root\nmode=read-only",
                        risk = if (root) "system-read-root" else "system-read",
                    ),
                    authorize,
                )
                if (!permission.allowed) {
                    return ToolResult(
                        "DENIED_BY_USER: ${permission.reason}",
                        success = false,
                        preview = "系统文件读取未获授权",
                    )
                }
                val result = systemAccess.read(decision.normalizedPath, root)
                auditLog.append(
                    "system",
                    "读取系统文件",
                    "path=${decision.normalizedPath} root=${result.usedRoot} " +
                        "chars=${result.output.length} sha256=${sha256(result.output)}",
                    "success",
                )
                ToolResult(
                    result.output,
                    success = true,
                    preview = "${decision.normalizedPath} · ${result.output.length} 字符",
                )
            }

            "write_file" -> {
                val path = arguments.optString("path")
                val content = arguments.optString("content")
                require(path.isNotBlank()) { "path 不能为空" }
                val permission = authorize(
                    PermissionRequest(
                        kind = PermissionKind.WRITE_FILES,
                        title = "修改文件",
                        detail = "path=$path\nchars=${content.length}\nsha256=${sha256(content)}",
                        risk = "workspace-write",
                    ),
                )
                if (!permission.allowed) {
                    auditLog.append(
                        "file",
                        "拒绝写入",
                        "path=$path",
                        permission.reason,
                    )
                    return ToolResult(
                        "DENIED_BY_USER: ${permission.reason}",
                        success = false,
                        preview = "写入未获授权",
                    )
                }
                workspace.writeText(path, content)
                auditLog.append(
                    "file",
                    "写入文件",
                    "path=$path chars=${content.length} sha256=${sha256(content)}",
                    "success",
                )
                ToolResult(
                    "Wrote ${content.length} characters to $path",
                    success = true,
                    preview = path,
                )
            }

            "run_command" -> {
                val command = arguments.optString("command").trim()
                val cwdText = arguments.optString("cwd", ".")
                val assessment = CommandPolicy.assess(command)
                if (assessment.risk == CommandRisk.BLOCKED) {
                    auditLog.append(
                        "command",
                        "命令被阻止",
                        "cwd=$cwdText command=$command",
                        assessment.reason,
                    )
                    return ToolResult(
                        "BLOCKED: ${assessment.reason}",
                        success = false,
                        preview = "命令被安全策略阻止",
                    )
                }
                val kind = when (assessment.risk) {
                    CommandRisk.NETWORK -> PermissionKind.NETWORK
                    CommandRisk.DESTRUCTIVE, CommandRisk.UNKNOWN -> PermissionKind.DESTRUCTIVE
                    else -> PermissionKind.RUN_COMMAND
                }
                val permission = authorize(
                    PermissionRequest(
                        kind = kind,
                        title = when (kind) {
                            PermissionKind.NETWORK -> "允许联网/安装依赖"
                            PermissionKind.DESTRUCTIVE -> "允许高风险命令"
                            else -> "执行命令"
                        },
                        detail = "cwd=$cwdText\ncommand=$command",
                        risk = assessment.risk.name.lowercase(),
                    ),
                )
                if (!permission.allowed) {
                    auditLog.append(
                        "command",
                        "拒绝执行",
                        "cwd=$cwdText command=$command",
                        permission.reason,
                    )
                    return ToolResult(
                        "DENIED_BY_USER: ${permission.reason}",
                        success = false,
                        preview = "命令未获授权",
                    )
                }
                val cwd = workspace.resolve(cwdText)
                require(cwd.isDirectory) { "cwd 不是目录: $cwdText" }
                auditLog.append(
                    "command",
                    "开始执行",
                    "cwd=$cwdText risk=${assessment.risk} command=$command",
                )
                val result = commandRunner.run(
                    command = command,
                    workingDir = cwd,
                    timeoutMs = 120_000,
                )
                auditLog.append(
                    "command",
                    "执行结束",
                    "cwd=$cwdText command=$command bytes=${result.output.length} sha256=${sha256(result.output)}",
                    "exit=${result.exitCode} timed_out=${result.timedOut} duration_ms=${result.durationMs}",
                )
                val payload = buildString {
                    appendLine("exit_code=${result.exitCode}")
                    appendLine("timed_out=${result.timedOut}")
                    appendLine("duration_ms=${result.durationMs}")
                    result.error?.let { appendLine("error=$it") }
                    appendLine("output:")
                    append(result.output.take(24_000))
                }
                ToolResult(
                    payload,
                    success = result.exitCode == 0 && !result.timedOut,
                    preview = buildString {
                        append("exit ")
                        append(result.exitCode)
                        append(" · ")
                        append(result.durationMs)
                        append("ms")
                    },
                )
            }

            else -> ToolResult("Unknown tool: ${call.name}", success = false, preview = "未知工具")
        }
    }

    private suspend fun authorizeSystemRead(
        request: PermissionRequest,
        authorize: suspend (PermissionRequest) -> PermissionDecision,
    ): PermissionDecision {
        val decision = authorize(request)
        if (!decision.allowed) {
            auditLog.append(
                "permission",
                "拒绝 ${request.title}",
                request.detail,
                decision.reason,
            )
        }
        return decision
    }

    private fun ensureSystemMessage() {
        val prompt = buildSystemPrompt()
        if (conversation.firstOrNull()?.role == "system") {
            conversation[0] = AgentMessage(role = "system", content = prompt)
        } else {
            conversation.add(0, AgentMessage(role = "system", content = prompt))
        }
    }

    private fun buildSystemPrompt(): String {
        return """
            You are Pocket Agent, a coding and device-inspection agent running on an Android phone.
            Inspect relevant files before editing. Use write_file for workspace changes and
            prefer edit_file for focused changes. Use search_files to locate code before reading
            many files. Use update_plan only for a genuinely multi-phase task or when the user
            explicitly asks for a plan. Do not create a plan for simple questions, one lookup,
            one edit, or one command. Reuse and update the existing plan during follow-up work
            instead of recreating it unless the goal has materially changed.
            Use run_command for builds/tests. Never claim a task succeeded without running an
            available relevant build or test command.

            Phone and system information:
            - When the user asks about the phone, device, Android version, hardware, memory, CPU,
              storage, kernel, or runtime environment, call device_info first.
            - Use list_system_dir and read_system_file for absolute system paths. These tools are
              read-only, require the app's system-read permission, and are audited.
            - Never write to a system path. Workspace writes must use write_file.
            - The app automatically uses Root when it has already been granted and otherwise uses
              the app UID. Do not ask the user to run su manually. Use device_info when root status
              or device capabilities matter.

            Safety rules:
            - The shell supports the complete Android shell syntax: pipes, redirection, chaining,
              scripts, package manager commands, settings and other system utilities. Commands use
              the app UID normally and the already-granted root UID automatically when available.
            - Network operations follow the separate network permission. Destructive commands are
              approved per action in ask mode and fully audited. Raw device nodes are not exposed
              through the system-read tools, but shell behavior is not artificially restricted.
            - Keep edits focused and explain remaining limitations briefly.
        """.trimIndent()
    }

    private fun summarizeArguments(raw: String): String = runCatching {
        val json = JSONObject(raw)
        when {
            json.has("command") -> json.optString("command")
            json.has("path") -> json.optString("path")
            else -> raw
        }.take(180)
    }.getOrDefault(raw.take(180))

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun countOccurrences(source: String, target: String): Int {
        var count = 0
        var index = source.indexOf(target)
        while (index >= 0) {
            count++
            index = source.indexOf(target, index + target.length)
        }
        return count
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private data class ToolResult(
        val content: String,
        val success: Boolean,
        val preview: String,
        val plan: List<AgentPlanStep>? = null,
    )

    companion object {
        private const val CONTEXT_COMPACT_RATIO = 0.72
        private const val LOCAL_CHECKPOINT_MESSAGES = 18
        private const val LOCAL_CHECKPOINT_MESSAGE_CHARS = 3_000
        private const val LOCAL_CHECKPOINT_MAX_CHARS = 36_000
        private const val MAX_TOOL_RESULT_CHARS = 30_000
        private const val MAX_HISTORY_MESSAGES = 80

        private val TOOLS = listOf(
            ToolDefinition(
                name = "update_plan",
                description = "Create or update a plan only for a genuinely multi-phase task. Do not use it for simple questions or one-step operations.",
                parameters = schema(
                    properties = mapOf(
                        "plan" to JSONObject().apply {
                            put("type", "array")
                            put(
                                "items",
                                JSONObject().apply {
                                    put("type", "object")
                                    put(
                                        "properties",
                                        JSONObject().apply {
                                            put(
                                                "step",
                                                stringProperty("Short actionable step"),
                                            )
                                            put(
                                                "status",
                                                JSONObject().apply {
                                                    put("type", "string")
                                                    put(
                                                        "enum",
                                                        JSONArray(
                                                            listOf(
                                                                "pending",
                                                                "in_progress",
                                                                "completed",
                                                            ),
                                                        ),
                                                    )
                                                },
                                            )
                                        },
                                    )
                                    put(
                                        "required",
                                        JSONArray(listOf("step", "status")),
                                    )
                                    put("additionalProperties", false)
                                },
                            )
                        },
                    ),
                    required = listOf("plan"),
                ),
            ),
            ToolDefinition(
                name = "list_files",
                description = "List the direct children of a workspace directory. Set recursive only when needed.",
                parameters = schema(
                    properties = mapOf(
                        "path" to stringProperty("Workspace-relative directory, defaults to ."),
                        "recursive" to JSONObject().apply {
                            put("type", "boolean")
                            put("description", "Recursively list up to 300 workspace entries")
                        },
                    ),
                ),
            ),
            ToolDefinition(
                name = "read_file",
                description = "Read a UTF-8 text file from the workspace.",
                parameters = schema(
                    properties = mapOf(
                        "path" to stringProperty("Workspace-relative file path"),
                    ),
                    required = listOf("path"),
                ),
            ),
            ToolDefinition(
                name = "search_files",
                description = "Search recursively for an exact text string inside the workspace and return line-numbered matches.",
                parameters = schema(
                    properties = mapOf(
                        "query" to stringProperty("Exact text to search for"),
                        "path" to stringProperty("Workspace-relative file or directory, defaults to ."),
                    ),
                    required = listOf("query"),
                ),
            ),
            ToolDefinition(
                name = "edit_file",
                description = "Apply a focused text replacement inside an existing workspace file. Requires the old text to be unique unless replace_all is true. A checkpoint is saved.",
                parameters = schema(
                    properties = mapOf(
                        "path" to stringProperty("Workspace-relative file path"),
                        "old_text" to stringProperty("Exact existing text to replace"),
                        "new_text" to stringProperty("Replacement text"),
                        "replace_all" to JSONObject().apply {
                            put("type", "boolean")
                            put("description", "Replace every occurrence instead of requiring one")
                        },
                    ),
                    required = listOf("path", "old_text", "new_text"),
                ),
            ),
            ToolDefinition(
                name = "device_info",
                description = "Read phone/device information such as manufacturer, model, Android version, CPU, memory, storage, display and runtime.",
                parameters = schema(emptyMap()),
            ),
            ToolDefinition(
                name = "list_system_dir",
                description = "List one absolute system directory in read-only mode. Requires user permission and is audited. Root is selected automatically when granted.",
                parameters = schema(
                    properties = mapOf(
                        "path" to stringProperty("Absolute directory path, for example /system or /data/data"),
                    ),
                    required = listOf("path"),
                ),
            ),
            ToolDefinition(
                name = "read_system_file",
                description = "Read one absolute text file from the phone in read-only mode. Requires user permission and is audited. Root is selected automatically when granted.",
                parameters = schema(
                    properties = mapOf(
                        "path" to stringProperty("Absolute text file path, for example /proc/version"),
                    ),
                    required = listOf("path"),
                ),
            ),
            ToolDefinition(
                name = "write_file",
                description = "Create or replace a text file in the workspace. A checkpoint is kept before overwrite.",
                parameters = schema(
                    properties = mapOf(
                        "path" to stringProperty("Workspace-relative file path"),
                        "content" to stringProperty("Complete new UTF-8 file content"),
                    ),
                    required = listOf("path", "content"),
                ),
            ),
            ToolDefinition(
                name = "run_command",
                description = "Run commands through the complete Android shell, including pipelines, redirection, chaining and system utilities. Root is used automatically when granted. Every command is audited.",
                parameters = schema(
                    properties = mapOf(
                        "command" to stringProperty("Shell command or script. Full shell syntax is supported."),
                        "cwd" to stringProperty("Workspace-relative directory, defaults to ."),
                    ),
                    required = listOf("command"),
                ),
            ),
        )

        private fun schema(
            properties: Map<String, JSONObject>,
            required: List<String> = emptyList(),
        ): JSONObject = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                properties.forEach { (key, value) -> put(key, value) }
            })
            put("required", org.json.JSONArray(required))
            put("additionalProperties", false)
        }

        private fun stringProperty(description: String): JSONObject = JSONObject().apply {
            put("type", "string")
            put("description", description)
        }
    }
}
