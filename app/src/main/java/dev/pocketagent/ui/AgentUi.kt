package dev.pocketagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

object AgentHeader {
    val PillHeight = 28.dp
    val PillRadius = 8.dp
}

/**
 * Compact pill used for the agent header. Every entry shares the same height and radius so the
 * row reads as one control strip instead of a pile of mismatched chips.
 */
@Composable
fun AgentPill(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    dotColor: Color? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val container = MaterialTheme.colorScheme.surfaceContainerLow
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(AgentHeader.PillHeight),
        shape = RoundedCornerShape(AgentHeader.PillRadius),
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(start = 9.dp, end = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(dotColor, CircleShape),
                )
            }
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = content,
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailingIcon != null) {
                Icon(
                    trailingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = content.copy(alpha = 0.67f),
                )
            }
        }
    }
}

@Composable
fun AgentIconAction(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(AgentHeader.PillHeight),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
fun AgentMenuSection(
    text: String,
    trailing: String? = null,
    horizontalPadding: Dp = 10.dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = horizontalPadding, end = horizontalPadding, top = 8.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
fun AgentMenuItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    horizontalPadding: Dp = 10.dp,
    onClick: () -> Unit,
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 7.dp,
                bottom = 7.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.6f,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
fun AgentMenuDivider() {
    Spacer(Modifier.height(3.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
    Spacer(Modifier.height(3.dp))
}

/** Detail row used by the header menus (label on the left, value on the right). */
@Composable
fun AgentMenuRow(
    label: String,
    value: String,
    accent: Color? = null,
    horizontalPadding: Dp = 10.dp,
    emphasized: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = if (emphasized) {
                MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
            } else {
                MaterialTheme.typography.labelSmall
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            value,
            style = if (emphasized) {
                MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                )
            },
            color = accent ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

fun formatTokenCount(value: Int): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

fun reasoningEffortLabel(effort: String): String = when (effort) {
    "minimal" -> "极低"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "极高"
    "max" -> "最高"
    "none" -> "关闭"
    else -> "自动"
}
