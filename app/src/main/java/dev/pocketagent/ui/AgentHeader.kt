package dev.pocketagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pocketagent.agent.TokenUsage
import java.util.Locale
import kotlin.math.roundToInt

const val AUTO_COMPACT_RATIO = 0.72f

/** Single control strip for the agent tab: model, thinking level, cache hit rate, history. */
@Composable
fun AgentHeaderBar(
    state: AppUiState,
    permissions: dev.pocketagent.agent.AgentPermissions,
    onPermissionsChanged: (dev.pocketagent.agent.AgentPermissions) -> Unit,
    contextLimit: Int,
    contextTokens: Int,
    tokensPerSecond: Float?,
    latestRequestUsage: TokenUsage?,
    taskUsage: TokenUsage?,
    sessionUsage: TokenUsage?,
    onModelSelected: (String) -> Unit,
    onProfileSelected: (String) -> Unit,
    onReasoningEffortSelected: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    var modelMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var effortMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var usageMenuExpanded by rememberSaveable { mutableStateOf(false) }

    val activeProfile = state.modelProfiles.firstOrNull { it.id == state.activeProfileId }
    val modelLabel = state.settings.model.ifBlank { "选择模型" }
    // The engine publishes the real prompt size; fall back to the last response for older data.
    val usedTokens = if (contextTokens > 0) {
        contextTokens
    } else {
        latestRequestUsage?.promptTokens ?: 0
    }
    val safeLimit = contextLimit.coerceAtLeast(1)
    val ratio = (usedTokens.toFloat() / safeLimit).coerceIn(0f, 1f)
    val overallUsage = remember(sessionUsage, taskUsage) {
        listOfNotNull(sessionUsage, taskUsage).maxByOrNull { it.requests }
    }
    val overallCacheRate = overallUsage?.takeIf { it.cacheMetricsAvailable }?.cacheHitRate

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.weight(1f)) {
            AgentPill(
                label = modelLabel,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = Icons.Filled.ArrowDropDown,
                active = true,
                onClick = { modelMenuExpanded = true },
            )
            AgentDropdown(
                expanded = modelMenuExpanded,
                onDismiss = { modelMenuExpanded = false },
            ) {
                ModelMenuContent(
                    state = state,
                    activeProfile = activeProfile,
                    onProfileSelected = {
                        onProfileSelected(it)
                        modelMenuExpanded = false
                    },
                    onModelSelected = {
                        onModelSelected(it)
                        modelMenuExpanded = false
                    },
                    onReasoningEffortSelected = {
                        onReasoningEffortSelected(it)
                        modelMenuExpanded = false
                    },
                    onRefreshModels = {
                        onRefreshModels()
                        modelMenuExpanded = false
                    },
                )
            }
        }

        // Thinking level sits with the model so the pairing is visible without opening a menu.
        Box {
            val effortLabel = reasoningEffortLabel(state.settings.reasoningEffort)
            AgentPill(
                label = "思考 $effortLabel",
                trailingIcon = Icons.Filled.ArrowDropDown,
                active = state.settings.reasoningEffort != "auto",
                onClick = { effortMenuExpanded = true },
            )
            AgentDropdown(
                expanded = effortMenuExpanded,
                onDismiss = { effortMenuExpanded = false },
            ) {
                EffortMenuContent(
                    state = state,
                    onSelected = {
                        onReasoningEffortSelected(it)
                        effortMenuExpanded = false
                    },
                )
            }
        }

        Box {
            AgentPill(
                label = overallRateLabel(overallCacheRate),
                dotColor = cacheDotColor(overallCacheRate),
                active = overallCacheRate != null,
                onClick = { usageMenuExpanded = true },
            )
            AgentDropdown(
                expanded = usageMenuExpanded,
                onDismiss = { usageMenuExpanded = false },
            ) {
                UsageMenuContent(
                    usage = latestRequestUsage,
                    overall = overallUsage,
                    overallRate = overallCacheRate,
                    usedTokens = usedTokens,
                    contextLimit = contextLimit,
                    tokensPerSecond = tokensPerSecond,
                )
            }
        }

        AgentIconAction(
            icon = Icons.Outlined.History,
            contentDescription = "历史对话",
            onClick = onOpenHistory,
        )
        AgentIconAction(
            icon = Icons.Outlined.AddComment,
            contentDescription = "新对话",
            enabled = !state.agentRunning,
            onClick = onNewConversation,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AgentPermissionControl(permissions, onPermissionsChanged)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(AgentHeader.PillHeight)
                .clip(RoundedCornerShape(AgentHeader.PillRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
                .clickable { usageMenuExpanded = true }
                .padding(horizontal = 7.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = when {
                        ratio <= 0f -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        ratio >= AUTO_COMPACT_RATIO -> MaterialTheme.colorScheme.error
                        ratio >= 0.5f -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Text(
                    text = if (usedTokens > 0) "${(ratio * 100).roundToInt()}%" else "--",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = when {
                        usedTokens <= 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                        ratio >= AUTO_COMPACT_RATIO -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = speedLabel(tokensPerSecond),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = if (tokensPerSecond == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** "42.1 t/s" style readout; keeps a fixed width so the bar does not jump around. */
private fun speedLabel(tokensPerSecond: Float?): String =
    if (tokensPerSecond == null || tokensPerSecond <= 0f) {
        "-- t/s"
    } else {
        String.format(Locale.US, "%.1f t/s", tokensPerSecond)
    }

private fun overallRateLabel(rate: Float?): String =
    if (rate == null) "缓存 --" else "缓存 ${(rate * 100).roundToInt()}%"

private fun cacheDotColor(rate: Float?): Color = when {
    rate == null -> Color(0xFF9E9E9E)
    rate >= 0.5f -> Color(0xFF2E9E5B)
    rate >= 0.2f -> Color(0xFFD79A26)
    else -> Color(0xFFC4553F)
}

@Composable
private fun AgentDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 300.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 3.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
        ),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        ) { content() }
    }
}

@Composable
private fun ModelMenuContent(
    state: AppUiState,
    activeProfile: dev.pocketagent.data.ModelProfile?,
    onProfileSelected: (String) -> Unit,
    onModelSelected: (String) -> Unit,
    onReasoningEffortSelected: (String) -> Unit,
    onRefreshModels: () -> Unit,
) {
    AgentMenuSection(
        text = if (state.modelProfiles.size > 1) "模型配置" else "当前配置",
        trailing = activeProfile?.name,
    )
    if (state.modelProfiles.isEmpty()) {
        AgentMenuItem(
            title = "尚未配置模型",
            subtitle = state.settings.baseUrl,
            enabled = false,
            onClick = {},
        )
    } else {
        state.modelProfiles.forEach { profile ->
            AgentMenuItem(
                title = profile.name,
                subtitle = "${profile.model} · ${profile.baseUrl}",
                selected = profile.id == state.activeProfileId,
                onClick = { onProfileSelected(profile.id) },
            )
        }
    }
    AgentMenuDivider()
    AgentMenuSection(text = "模型")
    if (state.availableModels.isEmpty()) {
        AgentMenuItem(
            title = if (state.modelsLoading) "正在获取模型…" else "没有可用模型",
            enabled = false,
            onClick = {},
        )
    } else {
        state.availableModels.forEach { model ->
            AgentMenuItem(
                title = model,
                selected = model == state.settings.model,
                onClick = { onModelSelected(model) },
            )
        }
    }
    AgentMenuDivider()
    EffortMenuContent(state = state, onSelected = onReasoningEffortSelected)
    AgentMenuDivider()
    AgentMenuItem(
        title = if (state.modelsLoading) "正在刷新模型列表…" else "刷新模型列表",
        enabled = !state.modelsLoading,
        onClick = onRefreshModels,
    )
}

/** Shared "思考等级" list, used by both the model menu and the compact effort pill. */
@Composable
private fun EffortMenuContent(
    state: AppUiState,
    onSelected: (String) -> Unit,
) {
    val current = state.settings.reasoningEffort
    val efforts = state.modelReasoningEfforts[state.settings.model].orEmpty()
    AgentMenuSection(
        text = "思考等级",
        trailing = reasoningEffortLabel(current),
    )
    AgentMenuItem(
        title = "自动",
        subtitle = "由服务商决定",
        selected = current == "auto",
        onClick = { onSelected("auto") },
    )
    efforts.forEach { effort ->
        AgentMenuItem(
            title = reasoningEffortLabel(effort),
            selected = current == effort,
            onClick = { onSelected(effort) },
        )
    }
    if (efforts.isEmpty()) {
        AgentMenuItem(
            title = "该模型未声明可调等级",
            enabled = false,
            onClick = {},
        )
    }
}

@Composable
private fun UsageMenuContent(
    usage: TokenUsage?,
    overall: TokenUsage?,
    overallRate: Float?,
    usedTokens: Int,
    contextLimit: Int,
    tokensPerSecond: Float?,
) {
    val safeLimit = contextLimit.coerceAtLeast(1)
    val ratio = (usedTokens.toFloat() / safeLimit).coerceIn(0f, 1f)
    val overallPercent = overallRate?.let { "${(it * 100).roundToInt()}%" } ?: "--"

    AgentMenuSection(text = "缓存命中率（整体）")
    Text(
        overallPercent,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
        style = MaterialTheme.typography.headlineSmall.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        ),
        color = overallRate?.let { cacheDotColor(it) }
            ?: MaterialTheme.colorScheme.onSurfaceVariant,
    )
    AgentMenuRow(
        label = "命中 / 总输入",
        value = if (overall != null && overallRate != null) {
            "${formatTokenCount(overall.cacheHitTokens)} / " +
                formatTokenCount(overall.promptTokens)
        } else {
            "--"
        },
    )
    AgentMenuRow(label = "累计请求", value = overall?.requests?.toString() ?: "--")
    AgentMenuRow(label = "输出 Token", value = formatTokenCount(overall?.completionTokens ?: 0))
    AgentMenuRow(
        label = "最近一次请求",
        value = when {
            usage == null -> "--"
            !usage.cacheMetricsAvailable -> "服务商未返回"
            else -> usage.cacheHitRate?.let { "${(it * 100).roundToInt()}%" } ?: "--"
        },
    )
    AgentMenuDivider()
    AgentMenuSection(text = "生成速度")
    AgentMenuRow(
        label = "当前",
        value = speedLabel(tokensPerSecond),
        accent = tokensPerSecond?.let { MaterialTheme.colorScheme.primary },
        emphasized = true,
    )
    AgentMenuRow(
        label = "输出 Token",
        value = formatTokenCount(overall?.completionTokens ?: 0),
    )
    AgentMenuDivider()
    AgentMenuSection(text = "上下文窗口", trailing = "${(ratio * 100).roundToInt()}%")
    AgentMenuRow(
        label = "本请求输入",
        value = "${formatTokenCount(usedTokens)} / ${formatTokenCount(contextLimit)}",
        accent = if (ratio >= AUTO_COMPACT_RATIO) MaterialTheme.colorScheme.error else null,
    )
    AgentMenuRow(
        label = "剩余空间",
        value = formatTokenCount((contextLimit - usedTokens).coerceAtLeast(0)),
    )
    AgentMenuRow(
        label = "自动压缩线",
        value = formatTokenCount((contextLimit * AUTO_COMPACT_RATIO).roundToInt()),
    )
}
