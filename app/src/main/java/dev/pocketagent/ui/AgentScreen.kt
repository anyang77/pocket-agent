package dev.pocketagent.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketagent.agent.ToolActivity
import dev.pocketagent.agent.ModelContextBudget
import dev.pocketagent.agent.AgentPlanStatus
import dev.pocketagent.agent.AgentPlanStep
import dev.pocketagent.data.AgentGoal
import dev.pocketagent.data.ConversationSummary
import dev.pocketagent.data.GoalPhase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    state: AppUiState,
    onSend: (String) -> Unit,
    onInputDraftChange: (String) -> Unit,
    onStopAgent: () -> Unit,
    onStopSubagent: (String) -> Unit,
    onImages: (List<android.net.Uri>) -> Unit,
    onMentionQuery: (String?) -> Unit,
    onFileAttachment: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRemoveQueued: (Long) -> Unit,
    onEditQueued: (Long, String) -> Unit,
    onSteerQueued: (Long) -> Unit,
    onResumeQueue: () -> Unit,
    onPauseQueue: () -> Unit,
    onNewConversation: () -> Unit,
    onModelSelected: (String) -> Unit,
    onProfileSelected: (String) -> Unit,
    onReasoningEffortSelected: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onSelectConversation: (Long) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onApprovePending: () -> Unit,
    onDenyPending: () -> Unit,
    onSelectChoice: (String) -> Unit,
    onCustomChoice: (String) -> Unit,
    onDismissChoice: () -> Unit,
    onPauseGoal: () -> Unit,
    onResumeGoal: () -> Unit,
    onClearGoal: () -> Unit,
    onCompactContext: () -> Unit,
    onComposerCommand: (String) -> Unit,
    onPermissionsChanged: (dev.pocketagent.agent.AgentPermissions) -> Unit,
    modifier: Modifier = Modifier,
) {
    var historyVisible by rememberSaveable { mutableStateOf(false) }
    var autoFollow by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val lastEntry = state.chat.lastOrNull()
    val bottomAnchorIndex = state.chat.size
    val latestUsageEntry = state.chat.asReversed()
        .firstOrNull { it.role == ChatRole.ASSISTANT && it.lastRequestUsage != null }
    val latestRequestUsage = latestUsageEntry?.lastRequestUsage
    val taskUsage = latestUsageEntry?.usage
    val sessionUsage = remember(state.chat) {
        state.chat.mapNotNull { it.usage }
            .reduceOrNull { accumulated, usage -> accumulated + usage }
    }
    val contextLimit = ModelContextBudget.limitFor(
        state.settings.model,
        state.settings.contextWindow,
    )

    LaunchedEffect(listState, bottomAnchorIndex) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
            val atBottom = lastVisible?.index == bottomAnchorIndex &&
                lastVisible.offset + lastVisible.size <= layout.viewportEndOffset + 4
            listState.isScrollInProgress to atBottom
        }.collectLatest { (scrolling, atBottom) ->
            if (atBottom) autoFollow = true
            if (scrolling && !atBottom) autoFollow = false
        }
    }

    LaunchedEffect(
        state.chat.size,
        lastEntry?.content?.length,
        lastEntry?.activities?.size,
        lastEntry?.activities?.lastOrNull()?.success,
        state.agentStatus,
    ) {
        if (lastEntry?.role == ChatRole.USER) autoFollow = true
        if (autoFollow && state.chat.isNotEmpty()) {
            listState.scrollToItem(bottomAnchorIndex)
        }
    }

    LaunchedEffect(state.currentConversationId) {
        autoFollow = true
        if (state.chat.isNotEmpty()) {
            listState.scrollToItem(bottomAnchorIndex)
        }
    }

    state.pendingUserChoice?.let { pending ->
        UserChoiceDialog(
            pending = pending,
            onSelect = onSelectChoice,
            onCustom = onCustomChoice,
            onDismiss = onDismissChoice,
        )
    }

    state.pendingApproval?.let { pending ->
        AlertDialog(
            onDismissRequest = onDenyPending,
            title = { Text(pending.title) },
            text = {
                SelectionContainer {
                    Text(
                        text = pending.detail,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onApprovePending) {
                    Text(
                        "允许一次",
                        color = if (pending.risk == "destructive" || pending.risk == "network") {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDenyPending) { Text("拒绝") }
            },
        )
    }

    if (historyVisible) {
        ModalBottomSheet(
            onDismissRequest = { historyVisible = false },
            sheetState = sheetState,
        ) {
            ConversationHistory(
                conversations = state.conversations,
                currentId = state.currentConversationId,
                onSelect = {
                    onSelectConversation(it)
                    historyVisible = false
                },
                onDelete = onDeleteConversation,
                onNew = {
                    onNewConversation()
                    historyVisible = false
                },
            )
        }
    }

    Column(
        modifier = modifier
            .imePadding()
            .padding(horizontal = 13.dp),
    ) {
        AgentHeaderBar(
            state = state,
            permissions = state.permissions,
            onPermissionsChanged = onPermissionsChanged,
            contextLimit = contextLimit,
            contextTokens = state.agentContextTokens,
            tokensPerSecond = state.agentTokensPerSecond,
            latestRequestUsage = latestRequestUsage,
            taskUsage = taskUsage,
            sessionUsage = sessionUsage,
            onModelSelected = onModelSelected,
            onProfileSelected = onProfileSelected,
            onReasoningEffortSelected = onReasoningEffortSelected,
            onRefreshModels = onRefreshModels,
            onNewConversation = onNewConversation,
            onOpenHistory = { historyVisible = true },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .selectableGroup(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.chat, key = { it.id }) { entry ->
                    ChatBubble(
                        entry = entry,
                        liveStatus = if (entry.id == lastEntry?.id && entry.pending) {
                            state.agentStatus
                        } else {
                            null
                        },
                        liveStartedAt = if (entry.id == lastEntry?.id && entry.pending) {
                            state.agentRequestStartedAt
                        } else {
                            null
                        },
                        liveRetry = if (entry.id == lastEntry?.id && entry.pending) {
                            state.agentRetry
                        } else {
                            null
                        },
                        liveCompacting = if (
                            entry.id == lastEntry?.id &&
                            entry.pending &&
                            state.agentCompactStatus != null
                        ) {
                            state.agentCompactStatus
                        } else {
                            null
                        },
                    )
                }
                item(key = "bottom-anchor") {
                    Spacer(Modifier.height(1.dp))
                }
            }
            if (!autoFollow && state.chat.isNotEmpty()) {
                SmallFloatingActionButton(
                    onClick = {
                        autoFollow = true
                        coroutineScope.launch {
                            listState.scrollToItem(bottomAnchorIndex)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "回到底部",
                    )
                }
            }
        }

        if (state.chat.none { it.role == ChatRole.USER }) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(
                    listOf(
                        "检查项目结构",
                        "运行测试并修复错误",
                    ),
                ) { prompt ->
                    AssistChip(
                        onClick = { onSend(prompt) },
                        enabled = !state.agentRunning,
                        label = { Text(prompt) },
                    )
                }
            }
        }

        state.agentGoal?.let { goal ->
            GoalDockBar(
                goal = goal,
                onPause = onPauseGoal,
                onResume = onResumeGoal,
                onClear = onClearGoal,
            )
        }

        SubagentDock(state.subagentTasks, onStopSubagent)
        AgentComposer(
            state, onInputDraftChange, onSend, onStopAgent, onImages, onMentionQuery,
            onFileAttachment, onRemoveAttachment, onRemoveQueued, onEditQueued,
            onSteerQueued, onResumeQueue, onPauseQueue,
            onCompactContext = onCompactContext,
            onComposerCommand = onComposerCommand,
        )
    }
}

@Composable
private fun ConversationHistory(
    conversations: List<ConversationSummary>,
    currentId: Long?,
    onSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onNew: () -> Unit,
) {
    val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("历史对话", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${conversations.size} 个本地会话",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onNew) { Text("新对话") }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (conversations.isEmpty()) {
            Text(
                "还没有历史对话",
                modifier = Modifier.padding(vertical = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    val current = conversation.id == currentId
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(conversation.id) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (current) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 13.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    conversation.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    conversation.preview,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    formatter.format(Date(conversation.updatedAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onDelete(conversation.id) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "删除对话")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(
    entry: ChatEntry,
    liveStatus: String?,
    liveStartedAt: Long?,
    liveRetry: AgentRetryState?,
    liveCompacting: String? = null,
) {
    if (entry.role == ChatRole.SYSTEM) {
        Text(
            entry.content,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    if (entry.role == ChatRole.USER) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    modifier = Modifier.widthIn(max = 520.dp),
                    shape = RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 6.dp,
                        bottomStart = 20.dp,
                        bottomEnd = 20.dp,
                    ),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entry.attachments.isNotEmpty()) AttachmentRow(entry.attachments)
                        if (entry.content.isNotBlank()) SelectionContainer {
                            Text(entry.content, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                MessageActions(
                    text = entry.content,
                    timestamp = entry.timestamp,
                    alignEnd = true,
                )
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(dev.pocketagent.R.drawable.ic_launcher),
            contentDescription = null, // The adjacent CodeMate label identifies the speaker.
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(9.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    "CodeMate",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (entry.plan.isNotEmpty()) {
                PlanCard(entry.plan)
            }

            if (entry.reasoning.isNotBlank()) {
                AssistantReasoningRow(
                    entryId = entry.id,
                    text = entry.reasoning,
                    running = entry.pending,
                )
            }

            AgentTimeline(
                entry = entry,
                liveStatus = liveStatus,
                liveStartedAt = liveStartedAt,
                liveRetry = liveRetry,
                liveCompacting = liveCompacting,
            )

            if (entry.content.isNotBlank() && !entry.pending) {
                MessageActions(
                    text = entry.content,
                    timestamp = entry.timestamp,
                )
            }

            entry.error?.let { message ->
                AgentErrorCard(
                    message = message,
                    retryCount = entry.retryCount,
                )
            }

        }
    }
}

@Composable
private fun MessageActions(
    text: String,
    timestamp: Long,
    alignEnd: Boolean = false,
) {
    val context = LocalContext.current
    var copied by rememberSaveable(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_200)
            copied = false
        }
    }
    val clock = remember(timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (alignEnd) Spacer(Modifier.weight(1f))
        TextButton(
            onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("消息", text))
                copied = true
            },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        ) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (copied) "已复制" else "复制",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (!alignEnd) Spacer(Modifier.weight(1f))
        Text(
            clock,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AssistantReasoningRow(
    entryId: Long,
    text: String,
    running: Boolean,
) {
    var expanded by rememberSaveable(entryId) { mutableStateOf(false) }
    val summary = remember(text) {
        text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(140)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (running) "思考中" else "思考过程",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起思考" else "展开思考",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                MarkdownContent(text)
            } else if (summary.isNotBlank()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun GoalDockBar(
    goal: AgentGoal,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(start = 11.dp, end = 5.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                "◎",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                when (goal.phase) {
                    GoalPhase.ACTIVE -> "进行中"
                    GoalPhase.PAUSED -> "已暂停"
                    GoalPhase.BLOCKED -> "受阻"
                },
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (goal.phase == GoalPhase.BLOCKED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(
                goal.objective,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (goal.phase) {
                GoalPhase.ACTIVE -> TextButton(
                    onClick = onPause,
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                ) { Text("暂停", style = MaterialTheme.typography.labelSmall) }
                GoalPhase.PAUSED, GoalPhase.BLOCKED -> TextButton(
                    onClick = onResume,
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                ) { Text("继续", style = MaterialTheme.typography.labelSmall) }
            }
            TextButton(
                onClick = onClear,
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
            ) { Text("清除", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun AgentLiveStatus(
    status: String?,
    startedAt: Long?,
) {
    var elapsedSeconds by remember(startedAt) { mutableStateOf(0L) }
    LaunchedEffect(startedAt) {
        while (startedAt != null) {
            elapsedSeconds = (
                (System.currentTimeMillis() - startedAt) / 1_000
                ).coerceAtLeast(0)
            delay(1_000)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(13.dp),
            strokeWidth = 1.8.dp,
        )
        Text(
            text = buildString {
                append(status ?: "正在执行")
                if (startedAt != null) {
                    append(" · ")
                    append(elapsedSeconds)
                    append("s")
                }
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AgentCompactingRow(text: String) {
    Row(
        modifier = Modifier.padding(start = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.6.dp,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AgentRetryCard(retry: AgentRetryState) {
    var remainingSeconds by remember(retry.retryAt) {
        mutableStateOf(((retry.retryAt - System.currentTimeMillis() + 999) / 1_000).coerceAtLeast(0))
    }
    LaunchedEffect(retry.retryAt) {
        while (true) {
            remainingSeconds = (
                (retry.retryAt - System.currentTimeMillis() + 999) / 1_000
                ).coerceAtLeast(0)
            delay(250)
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "↻",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.tertiary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    "正在重新连接",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    retry.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    buildString {
                        append("第 ")
                        append(retry.attempt)
                        append("/")
                        append(retry.maxAttempts)
                        append(" 次重试")
                        if (remainingSeconds > 0) {
                            append(" · ")
                            append(remainingSeconds)
                            append(" 秒后继续")
                        } else {
                            append(" · 正在发起请求")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserChoiceDialog(
    pending: PendingUserChoice,
    onSelect: (String) -> Unit,
    onCustom: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editingCustom by remember(pending) { mutableStateOf(false) }
    var customText by remember(pending) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editingCustom) "说说你的想法" else "需要你决定") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    pending.question,
                    modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (editingCustom) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { if (it.length <= 4000) customText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("你的方案") },
                        placeholder = { Text("输入其他方向，或补充你的要求…") },
                        minLines = 3,
                        maxLines = 6,
                        supportingText = { Text("${customText.length}/4000") },
                    )
                } else {
                    pending.options.forEach { option ->
                        Surface(
                            onClick = { onSelect(option.id) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                modifier = Modifier
                                    .heightIn(min = 52.dp)
                                    .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        option.title,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    if (option.detail.isNotBlank()) {
                                        Text(
                                            option.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Icon(
                                    Icons.Filled.ChevronRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (pending.allowCustomInput) {
                        Surface(
                            onClick = { editingCustom = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                modifier = Modifier.heightIn(min = 52.dp).padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("其他，我来补充", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (editingCustom) {
                TextButton(
                    onClick = { onCustom(customText.trim()) },
                    enabled = customText.isNotBlank(),
                ) { Text("提交方案") }
            }
        },
        dismissButton = {
            if (editingCustom) {
                TextButton(onClick = { editingCustom = false }) { Text("返回选项") }
            } else {
                TextButton(onClick = onDismiss) { Text("暂不选择") }
            }
        },
    )
}

@Composable
private fun AgentErrorCard(
    message: String,
    retryCount: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.error.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    agentErrorTitle(message),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
                if (retryCount > 0) {
                    Text(
                        "已重试 $retryCount/5 次",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SelectionContainer {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            agentErrorHint(message)?.let { hint ->
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                )
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun agentErrorTitle(message: String): String = when {
    "401" in message || "403" in message -> "API 鉴权失败"
    "concurrency limit" in message.lowercase() ||
        "并发请求已达上限" in message -> "模型并发已满"
    "429" in message -> "请求过于频繁"
    "30 秒内" in message || "timeout" in message.lowercase() -> "模型响应超时"
    "HTTP 400" in message -> "接口拒绝了请求"
    "输出中断" in message -> "模型输出中断"
    "HTTP 5" in message -> "模型服务异常"
    else -> "模型请求失败"
}

private fun agentErrorHint(message: String): String? = when {
    "401" in message || "403" in message ->
        "检查 API Key 是否有效、额度是否可用。"
    "concurrency limit" in message.lowercase() ||
        "并发请求已达上限" in message ->
        "已自动等待并重连 5 次；当前账号仍被并发限制，请稍后再试或切换模型。"
    "429" in message ->
        "已自动退避重连 5 次；服务仍在限流，请稍后再试或切换模型。"
    "30 秒内" in message || "timeout" in message.lowercase() ->
        "服务端没有及时返回首个数据包，检查模型是否可用或网络连接。"
    "HTTP 400" in message ->
        "检查 Base URL、模型名称以及该接口是否兼容 OpenAI Chat Completions。"
    "输出中断" in message ->
        "已自动重新生成 5 次；连接仍不稳定，请稍后重试。"
    "HTTP 5" in message ->
        "上游模型服务异常，稍后重试或切换模型。"
    else -> null
}

@Composable
private fun PlanCard(plan: List<AgentPlanStep>) {
    val completed = plan.count { it.status == AgentPlanStatus.COMPLETED }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                "任务计划 · $completed/${plan.size}",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
            plan.forEach { step ->
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        when (step.status) {
                            AgentPlanStatus.COMPLETED -> "✓"
                            AgentPlanStatus.IN_PROGRESS -> "●"
                            AgentPlanStatus.PENDING -> "○"
                        },
                        modifier = Modifier.width(14.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = when (step.status) {
                            AgentPlanStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                            AgentPlanStatus.IN_PROGRESS -> MaterialTheme.colorScheme.tertiary
                            AgentPlanStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        step.step,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (step.status == AgentPlanStatus.COMPLETED) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentTimeline(
    entry: ChatEntry,
    liveStatus: String?,
    liveStartedAt: Long?,
    liveRetry: AgentRetryState?,
    liveCompacting: String?,
) {
    var cursor = 0
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        entry.activities.forEachIndexed { index, activity ->
            val offset = activity.contentOffset.coerceIn(cursor, entry.content.length)
            if (offset > cursor) {
                MarkdownContent(entry.content.substring(cursor, offset))
                cursor = offset
            }
            InlineActivity(
                entryId = entry.id,
                index = index,
                activity = activity,
            )
        }
        if (cursor < entry.content.length) {
            MarkdownContent(entry.content.substring(cursor))
        }
        if (liveRetry != null) {
            AgentRetryCard(liveRetry)
        } else if (liveStatus != null) {
            AgentLiveStatus(
                status = liveStatus,
                startedAt = liveStartedAt,
            )
        }
        if (liveCompacting != null) {
            AgentCompactingRow(liveCompacting)
        }
    }
}

@Composable
private fun InlineActivity(
    entryId: Long,
    index: Int,
    activity: ToolActivity,
) {
    var expanded by rememberSaveable(entryId, index) { mutableStateOf(false) }
    val running = activity.success == null
    val hasFileChanges = activity.additions > 0 || activity.deletions > 0
    val hasDetails = activity.result?.isNotBlank() == true
    val visual = activityVisual(activity.name)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp)
            .clickable(enabled = hasDetails) { expanded = !expanded },
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    running -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.7.dp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    else -> Icon(
                        visual.icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (activity.success == false) {
                            MaterialTheme.colorScheme.error
                        } else {
                            visual.tint
                        },
                    )
                }
                Text(
                    activityDisplayName(activity.name),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = if (activity.success == false) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hasFileChanges) {
                    Text(
                        "+${activity.additions}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "−${activity.deletions}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                activity.durationMs
                    ?.let(::formatActivityDuration)
                    ?.let { duration ->
                        Text(
                            duration,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                if (hasDetails) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                activity.summary,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) 4 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                activity.result?.takeIf { it.isNotBlank() }?.let { result ->
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    )
                    SelectionContainer {
                        Text(
                            result,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

private fun formatActivityDuration(durationMs: Long): String =
    if (durationMs < 1_000) {
        "${durationMs}ms"
    } else {
        String.format(Locale.US, "%.1fs", durationMs / 1_000.0)
    }

private fun activityDisplayName(name: String): String = when (name) {
    "load_skill" -> "加载技能"
    "spawn_subagent" -> "启动子代理"
    "wait_subagents" -> "等待子代理"
    "subagent_result" -> "读取子代理结果"
    "stop_subagent" -> "停止子代理"
    "list_files" -> "浏览目录"
    "read_file" -> "读取文件"
    "search_files" -> "搜索代码"
    "edit_file" -> "编辑文件"
    "write_file" -> "写入文件"
    "device_info" -> "读取设备信息"
    "list_system_dir" -> "浏览系统目录"
    "read_system_file" -> "读取系统文件"
    "run_command" -> "运行命令"
    "模型请求" -> "模型请求"
    "update_plan" -> "更新任务计划"
    else -> name
}

private data class ActivityVisual(val icon: ImageVector, val tint: Color)

@Composable
private fun activityVisual(name: String): ActivityVisual = when (name) {
    "list_files" -> ActivityVisual(Icons.Outlined.FolderOpen, MaterialTheme.colorScheme.secondary)
    "read_file" -> ActivityVisual(Icons.Outlined.Description, MaterialTheme.colorScheme.secondary)
    "search_files" -> ActivityVisual(Icons.Outlined.Search, MaterialTheme.colorScheme.primary)
    "edit_file", "write_file" -> ActivityVisual(Icons.Outlined.Edit, MaterialTheme.colorScheme.tertiary)
    "run_command" -> ActivityVisual(Icons.Outlined.Terminal, MaterialTheme.colorScheme.primary)
    else -> ActivityVisual(Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.primary)
}

@Composable
internal fun MarkdownContent(content: String) {
    val blocks = remember(content) { parseMarkdown(content) }
    val inlineCodeBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> RichText(
                    text = block.text,
                    inlineCodeBackground = inlineCodeBackground,
                    style = if (block.level <= 2) {
                        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                )

                is MarkdownBlock.Paragraph -> RichText(
                    text = block.text,
                    inlineCodeBackground = inlineCodeBackground,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                is MarkdownBlock.ListItem -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            block.marker,
                            modifier = Modifier.width(20.dp),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        RichText(
                            text = block.text,
                            modifier = Modifier.weight(1f),
                            inlineCodeBackground = inlineCodeBackground,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                is MarkdownBlock.Quote -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        "▎",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    RichText(
                        text = block.text,
                        modifier = Modifier.weight(1f),
                        inlineCodeBackground = inlineCodeBackground,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is MarkdownBlock.Code -> CopyableCodeBlock(block.language, block.code)

                is MarkdownBlock.Table -> Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ) {
                    Text(
                        block.text,
                        modifier = Modifier
                            .padding(11.dp)
                            .horizontalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 19.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun RichText(
    text: String,
    inlineCodeBackground: Color,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = inlineMarkdown(text, inlineCodeBackground),
        modifier = modifier,
        style = style,
        color = color,
    )
}

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class ListItem(val marker: String, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val language: String, val code: String) : MarkdownBlock
    data class Table(val text: String) : MarkdownBlock
}

private fun parseMarkdown(source: String): List<MarkdownBlock> {
    val lines = source.replace("\r\n", "\n").lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0

    while (index < lines.size) {
        val raw = lines[index]
        val line = raw.trim()
        if (line.isEmpty()) {
            index++
            continue
        }

        if (line.startsWith("```")) {
            val language = line.removePrefix("```").trim()
            val code = buildList {
                index++
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    add(lines[index])
                    index++
                }
            }.joinToString("\n")
            if (index < lines.size) index++
            blocks += MarkdownBlock.Code(language, code)
            continue
        }

        val heading = Regex("""^(#{1,6})\s+(.+)$""").find(line)
        if (heading != null) {
            blocks += MarkdownBlock.Heading(
                level = heading.groupValues[1].length,
                text = heading.groupValues[2],
            )
            index++
            continue
        }

        if (line.startsWith(">")) {
            val quote = buildList {
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    add(lines[index].trim().removePrefix(">").trim())
                    index++
                }
            }.joinToString(" ")
            blocks += MarkdownBlock.Quote(quote)
            continue
        }

        val bullet = Regex("""^([-*+]|\d+[.)])\s+(.+)$""").find(line)
        if (bullet != null) {
            blocks += MarkdownBlock.ListItem(
                marker = bullet.groupValues[1].let {
                    if (it.first().isDigit()) it else "•"
                },
                text = bullet.groupValues[2],
            )
            index++
            continue
        }

        if (
            line.contains("|") &&
            index + 1 < lines.size &&
            isMarkdownTableSeparator(lines[index + 1])
        ) {
            val tableLines = mutableListOf(raw)
            index++
            while (index < lines.size && lines[index].contains("|")) {
                tableLines += lines[index]
                index++
            }
            blocks += MarkdownBlock.Table(
                tableLines.joinToString("\n")
                    .replace("|---|", "|───|"),
            )
            continue
        }

        val paragraph = mutableListOf(line)
        index++
        while (index < lines.size) {
            val next = lines[index].trim()
            if (next.isEmpty() || startsMarkdownBlock(next)) break
            paragraph += next
            index++
        }
        blocks += MarkdownBlock.Paragraph(paragraph.joinToString(" "))
    }
    return blocks
}

private fun startsMarkdownBlock(line: String): Boolean =
    line.startsWith("```") ||
        line.startsWith(">") ||
        Regex("""^(#{1,6})\s+""").containsMatchIn(line) ||
        Regex("""^([-*+]|\d+[.)])\s+""").containsMatchIn(line)

private fun isMarkdownTableSeparator(line: String): Boolean {
    val trimmed = line.trim().trim('|')
    if (trimmed.isBlank()) return false
    return trimmed.split('|').all { cell ->
        val value = cell.trim()
        value.isNotEmpty() && value.all { it == '-' || it == ':' || it.isWhitespace() }
    }
}

private fun inlineMarkdown(text: String, inlineCodeBackground: Color): AnnotatedString =
    buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            when {
                text.startsWith("**", index) -> {
                    val end = text.indexOf("**", index + 2)
                    if (end > index + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(text.substring(index + 2, end))
                        }
                        index = end + 2
                    } else {
                        append(text[index])
                        index++
                    }
                }
                text[index] == '`' -> {
                    val end = text.indexOf('`', index + 1)
                    if (end > index + 1) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = inlineCodeBackground,
                            ),
                        ) {
                            append(text.substring(index + 1, end))
                        }
                        index = end + 1
                    } else {
                        append(text[index])
                        index++
                    }
                }
                text[index] == '[' -> {
                    val labelEnd = text.indexOf("](", index + 1)
                    val urlEnd = if (labelEnd >= 0) text.indexOf(')', labelEnd + 2) else -1
                    if (labelEnd > index + 1 && urlEnd > labelEnd + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                            append(text.substring(index + 1, labelEnd))
                        }
                        index = urlEnd + 1
                    } else {
                        append(text[index])
                        index++
                    }
                }
                else -> {
                    append(text[index])
                    index++
                }
            }
        }
    }
