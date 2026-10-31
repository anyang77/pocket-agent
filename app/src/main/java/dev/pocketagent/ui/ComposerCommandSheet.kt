package dev.pocketagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Agent actions only: existing navigation and settings belong in their original entrances. */
@Composable
internal fun ComposerCommandPanel(
    state: AppUiState,
    onCompact: () -> Unit,
    onCommand: (String) -> Unit,
) {
    val changedFiles = (state.gitState.changes + state.gitState.staged)
        .distinct().size
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
    ) {
        Text(
            "更多功能",
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        CommandRow(
            icon = Icons.Outlined.Compress,
            title = "压缩上下文",
            detail = "保留任务检查点，释放上下文空间",
            command = "/compact",
            enabled = !state.agentRunning && state.chat.isNotEmpty(),
            onClick = onCompact,
        )
        CommandRow(
            icon = Icons.Outlined.RateReview,
            title = "审查改动",
            detail = "检查未提交的改动，列出问题与位置",
            command = "/review",
            enabled = !state.agentRunning && state.gitState.isRepository && changedFiles > 0,
            onClick = { onCommand("/review") },
        )
        CommandRow(
            icon = Icons.Outlined.AltRoute,
            title = if (state.agentPlanMode) "退出规划模式" else "规划模式",
            detail = "只读分析并制定方案，确认后再执行",
            command = "/plan",
            enabled = !state.agentRunning,
            onClick = { onCommand("/plan") },
        )
        CommandRow(
            icon = Icons.Outlined.Description,
            title = "项目说明",
            detail = "生成 AGENTS.md，预览变更后确认写入",
            command = "/init",
            enabled = !state.agentRunning,
            onClick = { onCommand("/init") },
        )
    }
}

@Composable
private fun CommandRow(
    icon: ImageVector,
    title: String,
    detail: String,
    command: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, contentDescription = null, modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(
                title, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            Text(
                detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
        Text(
            command, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
