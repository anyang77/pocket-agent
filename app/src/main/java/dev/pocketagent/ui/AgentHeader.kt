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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketagent.agent.TokenUsage
import dev.pocketagent.agent.contextCompactionThreshold
import java.util.Locale
import kotlin.math.roundToInt

/** Model, execution and usage controls grouped by purpose instead of wrapped into one crowded strip. */
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
    val compactAt = contextCompactionThreshold(contextLimit)
    val compactRatio = compactAt.toFloat() / safeLimit

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp, bottom = 7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f)) {
                ModelControl(
                    modelLabel = modelLabel,
                    effortLabel = reasoningEffortLabel(state.settings.reasoningEffort),
                    profileName = activeProfile?.name,
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
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            AgentPermissionControl(permissions, onPermissionsChanged)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(AgentHeader.PillHeight)
                    .clip(RoundedCornerShape(AgentHeader.PillRadius))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f))
                    .clickable { usageMenuExpanded = true }
                    .padding(horizontal = 9.dp),
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
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = when {
                            ratio <= 0f -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            ratio >= compactRatio -> MaterialTheme.colorScheme.error
                            ratio >= 0.5f -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        },
                        trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                    Text(
                        text = if (usedTokens > 0) {
                            String.format(Locale.US, "%.0f%%", ratio * 100)
                        } else {
                            "--"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = if (ratio >= compactRatio) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    HorizontalDivider(
                        modifier = Modifier
                            .width(1.dp)
                            .height(12.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
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
}

@Composable
private fun ModelControl(
    modelLabel: String,
    effortLabel: String,
    profileName: String?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    modelLabel,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        profileName?.takeIf { it.isNotBlank() },
                        "思考 $effortLabel",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icons.Filled.ArrowDropDown.let { arrow ->
                androidx.compose.material3.Icon(
                    arrow,
                    contentDescription = "切换模型与思考等级",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun cacheDotColor(rate: Float?): Color = when {
    rate == null -> MaterialTheme.colorScheme.outline
    rate >= 0.5f -> Color(0xFF15803D)
    rate >= 0.2f -> Color(0xFFA16207)
    else -> Color(0xFFB42318)
}

@Composable
private fun cacheRateColor(rate: Float): Color = when {
    rate >= 0.5f -> MaterialTheme.colorScheme.primary
    rate >= 0.2f -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
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
    val compactAt = contextCompactionThreshold(contextLimit)
    val compactRatio = compactAt.toFloat() / safeLimit
    val overallPercent = overallRate?.let { "${(it * 100).roundToInt()}%" } ?: "--"

    AgentMenuSection(text = "缓存命中率（整体）")
    Text(
        overallPercent,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
        style = MaterialTheme.typography.headlineSmall.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        ),
        color = overallRate?.let { cacheRateColor(it) }
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
        accent = if (ratio >= compactRatio) MaterialTheme.colorScheme.error else null,
    )
    AgentMenuRow(
        label = "剩余空间",
        value = formatTokenCount((contextLimit - usedTokens).coerceAtLeast(0)),
    )
    AgentMenuRow(
        label = "自动压缩线",
        value = formatTokenCount(compactAt),
    )
}
