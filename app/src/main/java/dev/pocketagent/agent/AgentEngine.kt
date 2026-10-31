package dev.pocketagent.agent

import dev.pocketagent.extensions.AgentExtensions
import dev.pocketagent.data.AiSettings
import dev.pocketagent.data.AuditLog
import dev.pocketagent.data.GitCommandAuth
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

internal data class LineDiffStats(
    val additions: Int,
    val deletions: Int,
)

/** Device controls that fail under the app UID on several Android ROMs, including MIUI. */
internal fun commandNeedsRootForSystemShell(command: String): Boolean =
    Regex(
        """(?:^|[;&|]\s*)(?:/system/bin/)?(?:am\s+(?:start(?:-activity)?|force-stop)|monkey)\b""",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(command) &&
        !Regex(
            """(?:^|[;&|]\s*)su\s+(?:-\S+\s+)*-c\s+["'][^"']*""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(command)

internal fun compactToolResultForModel(content: String, maxChars: Int): String {
    if (content.length <= maxChars) return content
    require(maxChars >= 64) { "maxChars must be at least 64" }
    val marker = "\n\n[... ${content.length} characters omitted; showing start and end ...]\n\n"
    val contentBudget = maxChars - marker.length
    if (contentBudget <= 0) {
        return "[... ${content.length} characters omitted; requested limit is too small ...]"
            .take(maxChars)
    }
    val headChars = contentBudget * 2 / 3
    val tailChars = contentBudget - headChars
    return content.take(headChars) + marker + content.takeLast(tailChars)
}

/**
 * Leaves enough room for the next model turn while using the longer windows effectively.
 * The 32K reserve dominates small windows; larger windows rise toward 92%.
 */
internal fun contextCompactionThreshold(contextLimit: Int): Int {
    val safeLimit = contextLimit.coerceAtLeast(1)
    if (safeLimit <= 64_000) return safeLimit
    return (safeLimit - 32_000).coerceIn(1, safeLimit)
}

internal fun countLineDiff(before: String, after: String): LineDiffStats {
    val beforeLines = if (before.isEmpty()) emptyList() else before.lines()
    val afterLines = if (after.isEmpty()) emptyList() else after.lines()
    if (beforeLines == afterLines) return LineDiffStats(0, 0)
    val prefix = beforeLines.zip(afterLines)
        .takeWhile { (oldLine, newLine) -> oldLine == newLine }
        .size
    val suffixLimit = minOf(beforeLines.size, afterLines.size) - prefix
    val suffix = beforeLines.asReversed()
        .zip(afterLines.asReversed())
        .take(suffixLimit)
        .takeWhile { (oldLine, newLine) -> oldLine == newLine }
        .size
    return LineDiffStats(
        additions = afterLines.size - prefix - suffix,
        deletions = beforeLines.size - prefix - suffix,
    )
}

class AgentEngine(
    private val workspace: WorkspaceRepository,
    private val commandRunner: CommandRunner,
    private val systemAccess: SystemAccessRepository,
    private val rootAvailable: () -> Boolean = { false },
    private val permissions: () -> AgentPermissions = { workspace.agentPermissions() },
    private val aiClient: AiClient = AiClient(),
    private val auditLog: AuditLog,
    private val extensions: AgentExtensions? = null,
    private val readOnlySubagent: Boolean = false,
    private val commandAuth: () -> GitCommandAuth = { GitCommandAuth() },
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
            .filter { !it.content.isNullOrBlank() || it.imagePaths.isNotEmpty() }
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
        choose: suspend (UserQuestion) -> AgentChoice? = { null },
        imagePaths: List<String> = emptyList(),
        takeSteering: suspend () -> List<AgentMessage> = { emptyList() },
        taskMode: AgentTaskMode = AgentTaskMode.EXECUTE,
        onEvent: (AgentEvent) -> Unit,
    ) {
        if (!readOnlySubagent && taskMode == AgentTaskMode.EXECUTE) extensions?.prepare(authorize)
        val tools = if (readOnlySubagent) TOOLS.filter { it.name in setOf("list_files", "read_file", "search_files") }
            else if (taskMode != AgentTaskMode.EXECUTE) TOOLS.filter { taskMode.allows(it.name) }
            else TOOLS + extensions?.tools().orEmpty()
        ensureSystemMessage(taskMode)
        val projectInstructions = try {
            if (workspace.resolve("AGENTS.md").isFile) {
                workspace.readText("AGENTS.md", maxChars = 24_000)
            } else ""
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            "[AGENTS.md could not be loaded: ${error.message}. Do not assume its contents.]"
        }
        conversation[0] = conversation[0].copy(content = conversation[0].content.orEmpty() +
            "\nProject instructions (AGENTS.md; subordinate to system and task restrictions):\n" +
            projectInstructions + "\nActive task restrictions:\n" + taskMode.instructions)
        conversation += AgentMessage(role = "user", content = userText, imagePaths = imagePaths)
        auditLog.append("chat", "用户消息", userText, "received")

        var step = 0
        var lastRetryCount = 0
        val completedToolCalls = mutableMapOf<String, ToolResult>()
        try {
            var lastPromptTokens = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val steering = takeSteering()
                if (steering.isNotEmpty()) {
                    conversation += steering
                    lastPromptTokens = 0
                    completedToolCalls.clear()
                }
                // Keep previously sent messages immutable for provider prefix caching.
                // New tool results are bounded when appended; history is rewritten only
                // during the explicit context-compaction pass below.
                val contextLimit = ModelContextBudget.limitFor(
                    settings.model,
                    settings.contextWindow,
                )
                val effectivePromptTokens = lastPromptTokens.takeIf { it > 0 }
                    ?: estimateConversationTokens()
                if (effectivePromptTokens >= contextCompactionThreshold(contextLimit)) {
                    val usage = compactConversation(
                        settings = settings,
                        originalUserText = userText,
                        beforeTokens = effectivePromptTokens,
                        contextLimit = contextLimit,
                        onEvent = onEvent,
                    )
                    lastPromptTokens = 0
                    usage?.let { onEvent(AgentEvent.Usage(it, updatesContext = false)) }
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
                        contextTokens = lastPromptTokens.takeIf { it > 0 }
                            ?: estimateConversationTokens(),
                    ),
                )
                val requestStartedAt = System.currentTimeMillis()
                lastRetryCount = 0
                var streamedChars = 0
                var emittedStreamChars = 0
                var reasoningChars = 0
                val pendingDelta = StringBuilder()
                var lastDeltaAt = 0L
                fun flushStreamText() {
                    if (pendingDelta.isEmpty()) return
                    val text = pendingDelta.toString()
                    emittedStreamChars += text.length
                    onEvent(AgentEvent.AssistantDelta(text))
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
                val onStreamReset: (Int) -> Unit = { contentChars ->
                    pendingDelta.clear()
                    val visibleChars = minOf(contentChars, emittedStreamChars)
                    streamedChars = 0
                    emittedStreamChars = 0
                    reasoningChars = 0
                    onEvent(AgentEvent.StreamOutputReset(visibleChars))
                }
                val turn = try {
                    aiClient.complete(
                        settings,
                        conversation,
                        tools,
                        onRetry = { notice ->
                            lastRetryCount = notice.attempt
                            onEvent(AgentEvent.RetryScheduled(notice))
                        },
                        onDelta = onDelta,
                        onReasoningDelta = onReasoningDelta,
                        onStreamReset = onStreamReset,
                        onDiagnostic = ::auditCacheDiagnostic,
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
                        tools,
                        onRetry = { notice ->
                            lastRetryCount = notice.attempt
                            onEvent(AgentEvent.RetryScheduled(notice))
                        },
                        onDelta = onDelta,
                        onReasoningDelta = onReasoningDelta,
                        onStreamReset = onStreamReset,
                        onDiagnostic = ::auditCacheDiagnostic,
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
                        completionTokens = turn.usage?.completionTokens ?: 0,
                    ),
                )
                turn.reasoningContent
                    ?.takeIf { it.isNotBlank() }
                    ?.let { onEvent(AgentEvent.ReasoningCompleted(it)) }
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
                    val steering = takeSteering()
                    if (steering.isNotEmpty()) {
                        conversation += steering
                        lastPromptTokens = 0
                        completedToolCalls.clear()
                        continue
                    }
                    if (taskMode == AgentTaskMode.EXECUTE && extensions?.subagents?.tasks?.value?.any { it.running } == true) {
                        onEvent(AgentEvent.State("正在等待子代理检查结果"))
                    }
                    val childResults = if (taskMode == AgentTaskMode.EXECUTE) extensions?.subagents?.remainingResults() else null
                    if (childResults != null) {
                        conversation += AgentMessage("user", childResults)
                        lastPromptTokens = 0
                        continue
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
                    val signature = reusableToolSignature(call)
                    val callable = taskMode.allows(call.name) &&
                        (!readOnlySubagent || call.name in setOf("list_files", "read_file", "search_files"))
                    val cachedResult = if (callable) signature?.let(completedToolCalls::get) else null
                    auditLog.append(
                        "tool",
                        if (cachedResult == null) "调用 ${call.name}" else "跳过重复 ${call.name}",
                        summary,
                    )
                    val visibleToolName = extensions?.displayName(call.name, call.arguments) ?: call.name
                    onEvent(AgentEvent.ToolStarted(visibleToolName, summary))
                    val result = cachedResult?.let {
                        ToolResult(
                            content = "DUPLICATE_TOOL_CALL_SUPPRESSED: This exact " +
                                "${call.name} request already completed successfully in " +
                                "this task. Reuse its earlier result and continue with the next " +
                                "required action instead of repeating it.",
                            success = true,
                            preview = "已跳过重复 ${toolDisplayName(call.name)}",
                            additions = 0,
                            deletions = 0,
                        )
                    } ?: try {
                        if (readOnlySubagent && call.name !in setOf("list_files", "read_file", "search_files")) {
                            ToolResult("Subagents are read-only. Ask the coordinating agent to perform this action.", false, "子代理无此能力")
                        } else if (!taskMode.allows(call.name)) {
                            ToolResult("TASK_MODE_DENIED: ${taskMode.name} does not allow ${call.name}.", false, "当前模式不允许此操作")
                        } else {
                            val extra = if (taskMode == AgentTaskMode.EXECUTE) extensions?.execute(call, settings,
                                conversation.takeLast(8).joinToString("\n") { "${it.role}: ${it.content.orEmpty().take(4000)}" }, authorize) else null
                            if (extra != null) ToolResult(extra.content, extra.success, extra.preview)
                            else executeTool(call, authorize, choose, taskMode)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        ToolResult(
                            content = "TOOL_ERROR: ${error.message ?: error.javaClass.simpleName}",
                            success = false,
                            preview = "工具调用失败",
                        )
                    }
                    if (cachedResult == null && signature != null && result.success) {
                        completedToolCalls[signature] = result
                    }
                    if (call.name == "edit_file" || call.name == "write_file") {
                        invalidateFileToolResults(completedToolCalls)
                    }
                    conversation += AgentMessage(
                        role = "tool",
                        content = compactToolResultForModel(
                            result.content,
                            MAX_TOOL_RESULT_CHARS,
                        ),
                        name = call.name,
                        toolCallId = call.id,
                    )
                    onEvent(
                        AgentEvent.ToolFinished(
                            name = visibleToolName,
                            summary = result.preview,
                            success = result.success,
                            output = result.content,
                            additions = result.additions,
                            deletions = result.deletions,
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
                    retryCount = (error as? AgentException)
                        ?.retryAttempts
                        ?.coerceAtLeast(lastRetryCount)
                        ?: lastRetryCount,
                ),
            )
        }
    }

    private fun auditCacheDiagnostic(action: String, detail: String) {
        auditLog.append("model", action, detail)
    }

    /**
     * Compacts the current context on demand, powering the "压缩上下文" composer command.
     * Returns false when there was nothing worth compacting.
     */
    suspend fun compactNow(settings: AiSettings, onEvent: (AgentEvent) -> Unit): Boolean {
        val contextLimit = ModelContextBudget.limitFor(settings.model, settings.contextWindow)
        val beforeTokens = estimateConversationTokens()
        val originalUserText = conversation.asReversed()
            .firstOrNull {
                it.role == "user" && it.content?.startsWith("[CodeMate") != true
            }
            ?.content
            ?.takeIf { it.isNotBlank() }
            ?: return false
        if (beforeTokens <= 0) return false
        auditLog.append("agent", "手动压缩上下文", "tokens=$beforeTokens limit=$contextLimit")
        val usage = compactConversation(
            settings = settings,
            originalUserText = originalUserText,
            beforeTokens = beforeTokens,
            contextLimit = contextLimit,
            onEvent = onEvent,
        )
        usage?.let { onEvent(AgentEvent.Usage(it, updatesContext = false)) }
        return true
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
                "上下文占用 ${contextPercent(beforeTokens, contextLimit)}%，正在压缩 · 可随时停止",
            ),
        )
        val compactionRequest = AgentMessage(
            role = "user",
            content = """
                [CodeMate runtime notice]
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
                    onDiagnostic = ::auditCacheDiagnostic,
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
                    onDiagnostic = ::auditCacheDiagnostic,
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
                [CodeMate compacted checkpoint]
                Continue the active task from the summary below. Use tools when needed. Do not ask the
                user to repeat information that is already present here.

                Original user request:
                $originalUserText

                Checkpoint:
                $checkpoint
            """.trimIndent(),
            imagePaths = contextSnapshot.lastOrNull { it.role == "user" && it.imagePaths.isNotEmpty() }
                ?.imagePaths.orEmpty(),
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
        (contentChars + toolChars) / 3 + 8 + message.imagePaths.size * 1500
    }

    private fun contextPercent(tokens: Int, contextLimit: Int): Int =
        ((tokens.toFloat() / contextLimit.toFloat()) * 100f).toInt().coerceIn(0, 100)

    private suspend fun executeTool(
        call: AgentToolCall,
        authorize: suspend (PermissionRequest) -> PermissionDecision,
        choose: suspend (UserQuestion) -> AgentChoice?,
        taskMode: AgentTaskMode = AgentTaskMode.EXECUTE,
    ): ToolResult {
        require(taskMode.allows(call.name)) { "当前模式不允许此操作" }
        val arguments = JSONObject(call.arguments.ifBlank { "{}" })
        if (taskMode == AgentTaskMode.INIT && call.name == "write_file") {
            return writeProjectInstructions(arguments, authorize, choose)
        }
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
                ToolResult(
                    text.ifBlank { "(目录为空)" },
                    success = true,
                    preview = "浏览 $path · ${files.size} 个条目",
                )
            }

            "read_file" -> {
                val path = arguments.optString("path")
                val text = workspace.readText(path, maxChars = 80_000)
                auditLog.append("file", "读取文件", "path=$path chars=${text.length}", "success")
                ToolResult(text, success = true, preview = "读取 $path · ${text.length} 字符")
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
                        preview = "搜索 $query · ${output.lineSequence().count()} 行匹配",
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
                val lineStats = lineDiffStats(original, updated)
                auditLog.append(
                    "file",
                    "局部修改文件",
                    "path=$path replacements=$occurrences chars=${updated.length} " +
                        "+${lineStats.additions} -${lineStats.deletions} " +
                        "sha256=${sha256(updated)}",
                    "success",
                )
                ToolResult(
                    "Edited $path: replaced $occurrences occurrence(s)\n" +
                        "+${lineStats.additions} -${lineStats.deletions}",
                    success = true,
                    preview = "编辑 $path · +${lineStats.additions} -${lineStats.deletions}",
                    additions = lineStats.additions,
                    deletions = lineStats.deletions,
                )
            }

            "ask_user" -> {
                val question = arguments.optString("question").trim()
                require(question.isNotBlank()) { "question 不能为空" }
                val rawOptions = arguments.optJSONArray("options") ?: JSONArray()
                val options = buildList {
                    for (index in 0 until rawOptions.length()) {
                        val item = rawOptions.optJSONObject(index) ?: continue
                        val id = item.optString("id").trim()
                        val title = item.optString("title").trim()
                        if (id.isBlank() || title.isBlank()) continue
                        add(
                            AgentChoice(
                                id = id.take(80),
                                title = title.take(160),
                                detail = item.optString("detail").trim().take(320),
                            ),
                        )
                    }
                }
                require(options.size in 2..3) { "ask_user 必须提供 2 到 3 个有效选项" }
                require(options.map { it.id }.distinct().size == options.size) {
                    "ask_user 选项 id 必须唯一"
                }
                auditLog.append(
                    "agent",
                    "等待用户选择",
                    "$question | ${options.joinToString { "${it.id}:${it.title}" }}",
                    "waiting",
                )
                val selected = choose(UserQuestion(question, options, allowCustomInput = true))
                    ?: return ToolResult(
                        "USER_CHOICE_CANCELLED: The user dismissed this choice. Do not guess a " +
                            "material branch; stop and ask again with a concise question.",
                        success = false,
                        preview = "用户未选择",
                    )
                require(
                    if (selected.isCustom) customUserChoice(selected.detail) == selected
                    else options.any { it == selected },
                ) {
                    "用户选择不在可选范围内"
                }
                auditLog.append(
                    "agent",
                    "用户已选择",
                    "$question | ${selected.id}:${selected.title}",
                    "success",
                )
                ToolResult(
                    "USER_SELECTION: ${selected.id} | ${selected.title}\n" +
                        selected.detail + "\nContinue the original task using this choice.",
                    success = true,
                    preview = "已选择 · ${selected.title}",
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
                val original = runCatching {
                    workspace.readText(
                        path = path,
                        maxChars = WorkspaceRepository.MAX_FILE_CHARS,
                        requireComplete = true,
                    )
                }.getOrDefault("")
                workspace.writeText(path, content)
                val lineStats = lineDiffStats(original, content)
                auditLog.append(
                    "file",
                    "写入文件",
                    "path=$path chars=${content.length} " +
                        "+${lineStats.additions} -${lineStats.deletions} " +
                        "sha256=${sha256(content)}",
                    "success",
                )
                ToolResult(
                    "Wrote ${content.length} characters to $path\n" +
                        "+${lineStats.additions} -${lineStats.deletions}",
                    success = true,
                    preview = "写入 $path · +${lineStats.additions} -${lineStats.deletions}",
                    additions = lineStats.additions,
                    deletions = lineStats.deletions,
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
                val requestedEnvironment = arguments.optString("environment", "termux").lowercase()
                val autoRootForAppTest = requestedEnvironment == "system" &&
                    rootAvailable() &&
                    commandNeedsRootForSystemShell(command)
                // Keep pkg/project commands in their requested runtime. Android activity control
                // is the narrow exception: MIUI rejects it under the app UID even with storage
                // access, then the app would otherwise wander through manual su attempts.
                val wantsRoot = requestedEnvironment == "root" || autoRootForAppTest
                val permissionKind = when {
                    requestedEnvironment == "root" -> PermissionKind.ROOT
                    wantsRoot -> PermissionKind.DESTRUCTIVE
                    else -> kind
                }
                val permission = authorize(
                    PermissionRequest(
                        kind = permissionKind,
                        title = when (permissionKind) {
                            PermissionKind.ROOT -> "在 Root 环境中执行命令"
                            PermissionKind.NETWORK -> "允许联网/安装依赖"
                            PermissionKind.DESTRUCTIVE -> "允许测试外部应用"
                            else -> "执行命令"
                        },
                        detail = "environment=${if (wantsRoot) "root" else requestedEnvironment}\n" +
                            "cwd=$cwdText\ncommand=$command",
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
                val cwd = if (AgentAccessPolicy.hasFullAccess(permissions()) && File(cwdText).isAbsolute) {
                    File(cwdText).canonicalFile
                } else {
                    workspace.resolve(cwdText)
                }
                require(cwd.isDirectory) { "cwd 不是目录: $cwdText" }
                auditLog.append(
                    "command",
                    "开始执行",
                    "cwd=$cwdText risk=${assessment.risk} command=$command",
                )
                val auth = commandAuth()
                val rawResult = commandRunner.run(
                    command = command,
                    workingDir = cwd,
                    asRoot = wantsRoot,
                    useInternalRuntime = requestedEnvironment != "system",
                    timeoutMs = 120_000,
                    extraEnv = auth.environment,
                )
                val result = rawResult.copy(
                    output = auth.redact(rawResult.output),
                    error = rawResult.error?.let(auth::redact),
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

    private suspend fun writeProjectInstructions(
        arguments: JSONObject,
        authorize: suspend (PermissionRequest) -> PermissionDecision,
        choose: suspend (UserQuestion) -> AgentChoice?,
    ): ToolResult {
        require(isProjectInstructionsPath(arguments.optString("path"))) {
            "项目说明模式只能写入根目录 AGENTS.md"
        }
        val target = File(workspace.root.canonicalFile, "AGENTS.md")
        require(target.canonicalFile == target.absoluteFile) { "AGENTS.md 不能是符号链接" }
        val content = arguments.optString("content")
        require(content.isNotBlank() && content.length <= 24_000) { "项目说明应为 1 到 24000 字符" }
        val existed = target.exists()
        val original = if (existed) workspace.readText("AGENTS.md", maxChars = 24_000, requireComplete = true) else ""
        if (original == content) return ToolResult("AGENTS.md unchanged", true, "项目说明无需修改")
        val root = workspace.root.canonicalFile
        val selected = choose(UserQuestion(
            "${if (existed) "更新" else "创建"} AGENTS.md？以下是完整变更：\n\n" + projectInstructionsDiff(original, content),
            listOf(AgentChoice("write", "确认写入", "仅更新项目根目录的 AGENTS.md，保留原文件检查点"),
                AgentChoice("cancel", "取消", "保留原文件")),
        ))
        if (selected?.id != "write") return ToolResult("USER_CANCELLED: AGENTS.md was not written. Stop this setup task.", false, "未写入项目说明")
        val permission = authorize(PermissionRequest(
            kind = PermissionKind.WRITE_FILES, title = "写入已确认的项目说明",
            detail = "path=AGENTS.md\nsha256=${sha256(content)}", risk = "workspace-write",
        ))
        if (!permission.allowed) return ToolResult("DENIED_BY_USER: ${permission.reason}", false, "项目说明未获写入授权")
        require(workspace.root.canonicalFile == root && target.canonicalFile == target.absoluteFile) { "工作区或文件路径已变化，请重新生成" }
        require(target.exists() == existed && (!existed || workspace.readText("AGENTS.md", maxChars = 24_000, requireComplete = true) == original)) {
            "AGENTS.md 在确认期间被修改，请重新读取后生成"
        }
        val backup = if (existed) workspace.checkpointProjectInstructions(original) else null
        workspace.writeText("AGENTS.md", content)
        auditLog.append("file", "写入项目说明", "path=${target.absolutePath} sha256=${sha256(content)} backup=$backup", "success")
        return ToolResult("Wrote AGENTS.md after explicit confirmation", true, "项目说明已保存")
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

    private fun ensureSystemMessage(taskMode: AgentTaskMode = AgentTaskMode.EXECUTE) {
        val prompt = buildSystemPrompt(taskMode)
        if (conversation.firstOrNull()?.role == "system") {
            conversation[0] = AgentMessage(role = "system", content = prompt)
        } else {
            conversation.add(0, AgentMessage(role = "system", content = prompt))
        }
    }

    private fun buildSystemPrompt(taskMode: AgentTaskMode = AgentTaskMode.EXECUTE): String = agentSystemPrompt(permissions()) +
        if (readOnlySubagent) "\nYou are a read-only child. Only list_files, read_file and search_files are available. Return findings to the parent."
        else if (taskMode != AgentTaskMode.EXECUTE) "\n" + taskMode.instructions
        else "\n" + commandAuth().description + extensions?.instructions().orEmpty()

    private fun invalidateFileToolResults(results: MutableMap<String, ToolResult>) {
        results.keys
            .filter { signature ->
                signature.startsWith("read_file:") ||
                    signature.startsWith("list_files:") ||
                    signature.startsWith("search_files:") ||
                    signature.startsWith("read_system_file:") ||
                    signature.startsWith("list_system_dir:")
            }
            .forEach(results::remove)
    }

    private fun reusableToolSignature(call: AgentToolCall): String? {
        if (call.name !in REUSABLE_TOOL_NAMES) return null
        val normalizedArguments = runCatching {
            JSONObject(call.arguments.ifBlank { "{}" }).toString()
        }.getOrElse { call.arguments.trim() }
        return "${call.name}:$normalizedArguments"
    }

    private fun toolDisplayName(name: String): String = when (name) {
        "read_file" -> "读取"
        "list_files" -> "列目录"
        "search_files" -> "搜索"
        "read_system_file" -> "系统文件读取"
        "list_system_dir" -> "系统目录读取"
        "run_command" -> "命令"
        "device_info" -> "设备信息读取"
        "ask_user" -> "等待选择"
        else -> name
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
        val additions: Int = 0,
        val deletions: Int = 0,
    )

    private fun lineDiffStats(before: String, after: String): LineDiffStats =
        countLineDiff(before, after)

    companion object {
        private const val LOCAL_CHECKPOINT_MESSAGES = 18
        private const val LOCAL_CHECKPOINT_MESSAGE_CHARS = 3_000
        private const val LOCAL_CHECKPOINT_MAX_CHARS = 36_000
        private const val MAX_TOOL_RESULT_CHARS = 16_000
        private const val MAX_HISTORY_MESSAGES = 80
        private val REUSABLE_TOOL_NAMES = setOf(
            "read_file",
            "list_files",
            "search_files",
            "read_system_file",
            "list_system_dir",
            "run_command",
            "device_info",
        )

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
                name = "ask_user",
                description = "Ask the user to choose between 2–3 concrete options when a complex task has a material branch that cannot be resolved from context. Use only for real product, design, compatibility, or risk decisions.",
                parameters = schema(
                    properties = mapOf(
                        "question" to stringProperty("A concise question explaining the decision"),
                        "options" to JSONObject().apply {
                            put("type", "array")
                            put("minItems", 2)
                            put("maxItems", 3)
                            put(
                                "items",
                                JSONObject().apply {
                                    put("type", "object")
                                    put(
                                        "properties",
                                        JSONObject().apply {
                                            put("id", stringProperty("Stable short identifier"))
                                            put("title", stringProperty("User-facing option label"))
                                            put("detail", stringProperty("Tradeoff or effect of this option"))
                                        },
                                    )
                                    put(
                                        "required",
                                        JSONArray(listOf("id", "title", "detail")),
                                    )
                                    put("additionalProperties", false)
                                },
                            )
                        },
                    ),
                    required = listOf("question", "options"),
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
                description = "Run a command in the selected environment. termux is the normal package/toolchain shell; system is the Android phone shell; root runs in Termux with UID 0 and requires explicit approval. Every command is audited.",
                parameters = schema(
                    properties = mapOf(
                        "command" to stringProperty("Shell command or script. Full shell syntax is supported."),
                        "cwd" to stringProperty("Working directory, defaults to .; workspace-relative, or an absolute path when full access is enabled."),
                        "environment" to enumProperty(
                            description = "Execution environment. Use termux for pkg/apt and project tools, system for Android shell utilities, root only for an operation that genuinely needs UID 0.",
                            values = listOf("termux", "system", "root"),
                        ),
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

        private fun enumProperty(description: String, values: List<String>): JSONObject = JSONObject().apply {
            put("type", "string")
            put("description", description)
            put("enum", org.json.JSONArray(values))
            put("default", values.first())
        }
    }
}

internal fun agentSystemPrompt(permissions: AgentPermissions): String = """
You are CodeMate, a coding and device-inspection agent running on an Android phone.
        Treat the latest user request as the active task goal. Keep work aligned with that goal,
        mark plan steps accurately as you progress, and verify the result against the goal before
        claiming completion.
        Inspect relevant files before editing. Use write_file for workspace changes and
        prefer edit_file for focused changes. Use search_files to locate code before reading
        many files. Use update_plan only for a genuinely multi-phase task or when the user
        explicitly asks for a plan. Do not create a plan for simple questions, one lookup,
        one edit, or one command. Reuse and update the existing plan during follow-up work
        instead of recreating it unless the goal has materially changed.
        Use run_command for builds/tests. Never claim a task succeeded without running an
        available relevant build or test command.

        Efficiency rules:
        - Resolve the active request with the shortest reliable tool path. For a focused
          request, inspect only the files that can affect the answer.
        - When several inspections are independent, issue them together in one assistant
          response so they execute in the same agent round. In particular, combine related
          file reads, status checks, searches, and shell diagnostics into one run_command
          script when that does not hide errors.
        - After an edit, prefer the smallest relevant build, test, or targeted check. Do not
          repeat a successful inspection or command unless a file or runtime state changed.
        - A tool result marked DUPLICATE_TOOL_CALL_SUPPRESSED means that exact request already
          ran. Use the prior result; do not investigate the duplicate marker itself.
        - Do not call update_plan just to narrate progress. Answer a simple informational
          request directly when no tool is needed.
        - When testing another Android app, combine launch, interaction or inspection, and
          screenshot/log collection into the same run_command so it finishes atomically.
          After the command ends, keep the tested app in the foreground. Return to CodeMate
          only when the user explicitly requests it.
        - If a complex task has a material product, design, compatibility, or risk choice that
          cannot be resolved from the request or repository, call ask_user with 2–3 concrete
          options. Do not guess between materially different outcomes. Ask only when the choice
          changes implementation or user experience; do not ask for routine confirmations.

        Phone and system information:
        - When the user asks about the phone, device, Android version, hardware, memory, CPU,
          storage, kernel, or runtime environment, call device_info first.
        - Use list_system_dir and read_system_file for absolute system paths. These tools are
          read-only, require the app's system-read permission, and are audited.
        ${AgentAccessPolicy.instructions(permissions)}
        - Termux, Android system shell, and Root are independent environments. Installing tools
          with pkg in Termux does not require disabling Root, and Root does not replace pkg.
          Use termux for project tools, system for Android utilities, and root only for an
          operation that genuinely needs UID 0. Do not use su manually inside termux or system.
        - A normal command remains usable when Root is available. Switch environments based on
          the command's needs instead of treating Root as a global mode.

        Safety rules:
        - The shell supports the complete Android shell syntax: pipes, redirection, chaining,
          scripts and package manager commands. Use Android system utilities only when they are
          relevant to the task, and do not assume they share the Termux package manager.
        - Network operations follow the separate network permission. Destructive commands are
          approved per action in ask mode and fully audited. Raw device nodes are not exposed
          through the system-read tools, but shell behavior is not artificially restricted.
        - Keep edits focused and explain remaining limitations briefly.
    """.trimIndent()
