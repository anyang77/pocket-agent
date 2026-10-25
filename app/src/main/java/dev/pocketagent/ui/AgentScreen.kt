package dev.pocketagent.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketagent.agent.TokenUsage
import dev.pocketagent.agent.ToolActivity
import dev.pocketagent.agent.ModelContextBudget
import dev.pocketagent.agent.AgentPlanStatus
import dev.pocketagent.agent.AgentPlanStep
import dev.pocketagent.data.ConversationSummary
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    state: AppUiState,
    onSend: (String) -> Unit,
    onStopAgent: () -> Unit,
    onNewConversation: () -> Unit,
    onModelSelected: (String) -> Unit,
    onProfileSelected: (String) -> Unit,
    onReasoningEffortSelected: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onSelectConversation: (Long) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onApprovePending: () -> Unit,
    onDenyPending: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var modelMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var usageMenuExpanded by rememberSaveable { mutableStateOf(false) }
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
    val activeProfileName = state.modelProfiles
        .firstOrNull { it.id == state.activeProfileId }
        ?.name

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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box {
                AssistChip(
                    onClick = { modelMenuExpanded = true },
                    enabled = !state.modelsLoading,
                    modifier = Modifier.widthIn(max = 190.dp),
                    label = {
                        Text(
                            listOfNotNull(
                                activeProfileName,
                                state.settings.model.ifBlank { "获取模型" },
                                reasoningEffortLabel(state.settings.reasoningEffort),
                            ).joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                DropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "模型配置",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                    state.modelProfiles.forEach { profile ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        profile.name,
                                        color = if (profile.id == state.activeProfileId) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    Text(
                                        "${profile.model} · ${profile.baseUrl}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            onClick = {
                                onProfileSelected(profile.id)
                                modelMenuExpanded = false
                            },
                        )
                    }
                    HorizontalDivider()
                    if (state.availableModels.isEmpty()) {
                        DropdownMenuItem(
                            text = {
                                Text(if (state.modelsLoading) "正在获取模型…" else "没有可用模型")
                            },
                            onClick = { modelMenuExpanded = false },
                        )
                    } else {
                        state.availableModels.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        model,
                                        color = if (model == state.settings.model) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                },
                                onClick = {
                                    onModelSelected(model)
                                    modelMenuExpanded = false
                                },
                            )
                        }
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                "思考等级",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "自动",
                                color = if (state.settings.reasoningEffort == "auto") {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                        onClick = {
                            onReasoningEffortSelected("auto")
                            modelMenuExpanded = false
                        },
                    )
                    state.modelReasoningEfforts[state.settings.model].orEmpty()
                        .forEach { effort ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        reasoningEffortLabel(effort),
                                        color = if (
                                            state.settings.reasoningEffort == effort
                                        ) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                },
                                onClick = {
                                    onReasoningEffortSelected(effort)
                                    modelMenuExpanded = false
                                },
                            )
                        }
                    if (state.modelReasoningEfforts[state.settings.model].isNullOrEmpty()) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "该模型未声明可调等级",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            onClick = {},
                            enabled = false,
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("刷新模型列表") },
                        leadingIcon = {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                        },
                        onClick = {
                            onRefreshModels()
                            modelMenuExpanded = false
                        },
                    )
                }
            }
            if (state.modelsLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
            }
            Spacer(Modifier.width(6.dp))
            UsageBadge(
                usage = latestRequestUsage,
                taskUsage = taskUsage,
                sessionUsage = sessionUsage,
                expanded = usageMenuExpanded,
                onExpandedChange = { usageMenuExpanded = it },
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { historyVisible = true }) {
                Icon(Icons.Outlined.History, contentDescription = "历史对话")
            }
            IconButton(
                onClick = onNewConversation,
                enabled = !state.agentRunning,
            ) {
                Icon(Icons.Outlined.AddComment, contentDescription = "新对话")
            }
        }

        ContextProgress(
            usedTokens = latestRequestUsage?.promptTokens ?: 0,
            contextLimit = contextLimit,
            taskUsage = taskUsage,
            lastRequestAt = latestUsageEntry?.lastRequestAt,
        )

        if (state.agentCompacting) {
            CompactionProgressBanner(
                startedAt = state.agentCompactionStartedAt,
                beforeTokens = state.agentCompactionBeforeTokens,
                contextLimit = state.agentCompactionLimit,
                status = state.agentStatus,
                onStop = onStopAgent,
            )
        }

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
                    ChatBubble(entry)
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

        if (state.agentRunning && !state.agentCompacting) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = state.agentStatus ?: "正在执行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 10.dp),
            placeholder = {
                Text(
                    if (state.agentRunning) {
                        "Agent 正在持续执行，点击右侧停止"
                    } else {
                        "问点什么，或者让 Agent 直接改代码…"
                    },
                )
            },
            minLines = 1,
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (input.isNotBlank() && !state.agentRunning) {
                        onSend(input)
                        input = ""
                    }
                },
            ),
            trailingIcon = {
                FilledIconButton(
                    onClick = {
                        if (state.agentRunning) {
                            onStopAgent()
                        } else {
                            onSend(input)
                            input = ""
                        }
                    },
                    enabled = state.agentRunning || input.isNotBlank(),
                    modifier = Modifier.size(38.dp),
                    shape = CircleShape,
                ) {
                    Icon(
                        if (state.agentRunning) Icons.Filled.Stop else Icons.Filled.Send,
                        contentDescription = if (state.agentRunning) "停止任务" else "发送",
                    )
                }
            },
        )
    }
}

@Composable
private fun CompactionProgressBanner(
    startedAt: Long?,
    beforeTokens: Int,
    contextLimit: Int,
    status: String?,
    onStop: () -> Unit,
) {
    var elapsedSeconds by remember(startedAt) { mutableStateOf(0L) }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsedSeconds = startedAt
                ?.let { ((System.currentTimeMillis() - it) / 1_000).coerceAtLeast(0) }
                ?: 0L
            delay(1_000)
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 7.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "正在压缩上下文",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Text(
                        "${formatTokenCount(beforeTokens)} / " +
                            "${formatTokenCount(contextLimit)} · 已等待 ${elapsedSeconds}s",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
                TextButton(onClick = onStop) {
                    Text("停止")
                }
            }
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                trackColor = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.16f),
            )
            Text(
                status ?: "正在生成可继续任务所需的检查点，完成后会自动继续",
                style = MaterialTheme.typography.labelSmall,
            )
        }
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
private fun ChatBubble(entry: ChatEntry) {
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
                SelectionContainer {
                    Text(
                        text = entry.content,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "P",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
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
                    "Pocket Agent",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
                if (entry.pending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(11.dp),
                        strokeWidth = 1.5.dp,
                    )
                }
            }

            if (entry.plan.isNotEmpty()) {
                PlanCard(entry.plan)
            }

            if (entry.activities.isNotEmpty()) {
                ActivityTrace(entry)
            }

            when {
                entry.content.isNotBlank() -> {
                    SelectionContainer {
                        MarkdownContent(entry.content)
                    }
                }
                entry.pending && entry.activities.isEmpty() -> {
                    Text(
                        "正在处理…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

        }
    }
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
private fun ActivityTrace(entry: ChatEntry) {
    val running = entry.activities.any { it.success == null }
    var expanded by rememberSaveable(entry.id) { mutableStateOf(running) }
    LaunchedEffect(running) {
        expanded = running
    }
    val failures = entry.activities.count { it.success == false }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 1.8.dp,
                    )
                } else {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (failures == 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "执行过程 · ${entry.activities.size} 步",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        when {
                            running -> "工具正在执行"
                            failures > 0 -> "$failures 步失败，详情可展开"
                            else -> "全部完成"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起执行过程" else "展开执行过程",
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                )
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    entry.activities.forEach { activity ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                when (activity.success) {
                                    null -> "…"
                                    true -> "✓"
                                    false -> "×"
                                },
                                modifier = Modifier.width(14.dp),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = when (activity.success) {
                                    false -> MaterialTheme.colorScheme.tertiary
                                    true -> MaterialTheme.colorScheme.primary
                                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    activity.name,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    activity.summary,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 16.sp,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                activity.result?.let {
                                    val timing = activity.durationMs
                                        ?.takeIf { duration -> activity.name != "上下文压缩" }
                                        ?.let(::formatActivityDuration)
                                        ?.let { duration -> " · $duration" }
                                        .orEmpty()
                                    Text(
                                        "↳ $it$timing",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
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

@Composable
private fun ContextProgress(
    usedTokens: Int,
    contextLimit: Int,
    taskUsage: TokenUsage?,
    lastRequestAt: Long?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val ratio = if (usedTokens > 0 && contextLimit > 0) {
        (usedTokens.toFloat() / contextLimit.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val progressColor = when {
        ratio >= 0.72f -> MaterialTheme.colorScheme.tertiary
        ratio >= 0.5f -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(top = 1.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "模型上下文 ⓘ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                if (usedTokens > 0) {
                    "${formatTokenCount(usedTokens)} / ${formatTokenCount(contextLimit)} · " +
                        "${(ratio * 100).roundToInt()}%"
                } else {
                    "-- / ${formatTokenCount(contextLimit)}"
                },
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = progressColor,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Column(
                modifier = Modifier
                    .width(275.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    "当前请求上下文",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "取最近一次模型响应里的 usage.prompt_tokens，不是累计计费 Token。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UsageDetailRow("本请求输入", formatTokenCount(usedTokens))
                UsageDetailRow("模型窗口", formatTokenCount(contextLimit))
                UsageDetailRow(
                    "剩余空间",
                    formatTokenCount((contextLimit - usedTokens).coerceAtLeast(0)),
                )
                UsageDetailRow(
                    "自动压缩线",
                    formatTokenCount((contextLimit * 0.72f).roundToInt()),
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                )
                Text(
                    "包含系统提示、历史消息、计划、工具结果和当前工作状态。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                taskUsage?.let {
                    UsageDetailRow("本次任务累计输入", formatTokenCount(it.promptTokens))
                    UsageDetailRow("本次任务请求数", it.requests.toString())
                }
                lastRequestAt?.let {
                    UsageDetailRow(
                        "更新时间",
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it)),
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageBadge(
    usage: TokenUsage?,
    taskUsage: TokenUsage?,
    sessionUsage: TokenUsage?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val cacheRate = usage?.cacheHitRate
    val hasCacheMetrics = usage?.cacheMetricsAvailable == true && cacheRate != null
    val accent = if (hasCacheMetrics) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box {
        Surface(
            modifier = Modifier.clickable { onExpandedChange(true) },
            shape = RoundedCornerShape(50),
            color = if (hasCacheMetrics) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("●", style = MaterialTheme.typography.labelSmall, color = accent)
                Text(
                    if (hasCacheMetrics) {
                        "缓存 ${(cacheRate!! * 100).roundToInt()}%"
                    } else {
                        "缓存 --"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = accent,
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            Column(
                modifier = Modifier
                    .width(230.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    "Token 与缓存",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (usage == null) {
                    Text(
                        "服务商尚未返回 usage 数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "当前请求",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    UsageDetailRow("输入", formatTokenCount(usage.promptTokens))
                    UsageDetailRow("输出", formatTokenCount(usage.completionTokens))
                    UsageDetailRow(
                        "缓存命中",
                        if (hasCacheMetrics) {
                            "${formatTokenCount(usage.cacheHitTokens)} · " +
                                "${(cacheRate!! * 100).roundToInt()}%"
                        } else {
                            "服务商未返回"
                        },
                        accent = if (hasCacheMetrics) accent else null,
                    )
                    if (usage.cacheMetricsAvailable) {
                        UsageDetailRow(
                            "缓存未命中",
                            formatTokenCount(usage.cacheMissTokens),
                        )
                    }
                    if (usage.reasoningTokens > 0) {
                        UsageDetailRow(
                            "推理",
                            formatTokenCount(usage.reasoningTokens),
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    )
                    taskUsage?.let {
                        Text(
                            "本次任务累计",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        UsageDetailRow(
                            "输入 / 输出",
                            "${formatTokenCount(it.promptTokens)} / " +
                                formatTokenCount(it.completionTokens),
                        )
                        UsageDetailRow(
                            "缓存命中率",
                            formatCacheRate(it),
                            accent = MaterialTheme.colorScheme.primary,
                        )
                        UsageDetailRow("请求次数", it.requests.toString())
                    }
                    sessionUsage?.takeIf { it.requests > (taskUsage?.requests ?: 0) }?.let {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                        )
                        Text(
                            "当前会话累计",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        UsageDetailRow(
                            "输入 / 输出",
                            "${formatTokenCount(it.promptTokens)} / " +
                                formatTokenCount(it.completionTokens),
                        )
                        UsageDetailRow(
                            "缓存命中率",
                            formatCacheRate(it),
                            accent = MaterialTheme.colorScheme.primary,
                        )
                        UsageDetailRow("请求次数", it.requests.toString())
                    }
                }
            }
        }
    }
}

private fun formatCacheRate(usage: TokenUsage): String {
    val rate = usage.cacheHitRate ?: return "服务商未返回"
    return "${(rate * 100).roundToInt()}% · ${formatTokenCount(usage.cacheHitTokens)}"
}

@Composable
private fun UsageDetailRow(
    label: String,
    value: String,
    accent: Color? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            ),
            color = accent ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MarkdownContent(content: String) {
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

                is MarkdownBlock.Code -> Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                ) {
                    Column(Modifier.padding(11.dp)) {
                        Text(
                            block.language.ifBlank { "code" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            block.code,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            softWrap = false,
                        )
                    }
                }

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

private fun formatTokenCount(value: Int): String =
    when {
        value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
        else -> value.toString()
    }

private fun reasoningEffortLabel(effort: String): String = when (effort) {
    "minimal" -> "极低"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "极高"
    "max" -> "最高"
    "none" -> "关闭"
    else -> "自动"
}
