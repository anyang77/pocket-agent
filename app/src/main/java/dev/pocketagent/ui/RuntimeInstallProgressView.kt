package dev.pocketagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private data class InstallStage(
    val label: String,
    val start: Float,
    val end: Float,
)

private val installStages = listOf(
    InstallStage("基础 Termux", 0f, 0.34f),
    InstallStage("软件源", 0.34f, 0.38f),
    InstallStage("下载工具链", 0.38f, 0.70f),
    InstallStage("解压与配置", 0.70f, 0.94f),
    InstallStage("验证环境", 0.94f, 1f),
)

@Composable
fun RuntimeInstallProgressView(
    message: String?,
    progress: Float,
    installing: Boolean,
) {
    val value = progress.coerceIn(0f, 1f)
    val failed = !installing && message?.contains("失败") == true
    val accent = if (failed) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.primary
    }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message ?: "等待初始化",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = accent,
            )
            Text(
                "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = accent,
            )
        }
        LinearProgressIndicator(
            progress = { value },
            modifier = Modifier.fillMaxWidth(),
            color = accent,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        installStages.forEach { stage ->
            val completed = value >= stage.end
            val active = value >= stage.start && value < stage.end
            Row(
                modifier = Modifier.padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    when {
                        completed -> "✓"
                        active -> "●"
                        else -> "○"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        completed -> MaterialTheme.colorScheme.primary
                        active -> accent
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    stage.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (completed || active) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
