package dev.pocketagent.ui

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView
import dev.pocketagent.runtime.RuntimeKind

@Composable
fun TerminalScreen(
    state: AppUiState,
    terminalManager: TerminalSessionManager,
    onInstallRuntime: () -> Unit,
    onRuntimeSelected: (RuntimeKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            terminalView?.let(terminalManager::detach)
        }
    }

    Column(modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("执行环境", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when (state.terminalRuntime) {
                                RuntimeKind.TERMUX -> "内置 Termux · 使用 pkg/apt 管理工具"
                                RuntimeKind.SYSTEM_SHELL -> "手机系统 Shell · 使用 Android 系统命令"
                                RuntimeKind.ROOT_TERMUX -> "Root · 在 Termux 中执行系统级操作"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = terminalManager::decreaseFont) { Text("A−") }
                    TextButton(onClick = terminalManager::increaseFont) { Text("A+") }
                    TextButton(
                        onClick = terminalManager::restart,
                        enabled = state.terminalRuntime != RuntimeKind.TERMUX ||
                            state.capabilities.internalTermuxReady,
                    ) {
                        Text("重启")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RuntimeChoice(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.FolderOpen,
                        label = "Termux",
                        selected = state.terminalRuntime == RuntimeKind.TERMUX,
                        enabled = state.capabilities.internalTermuxReady,
                        onClick = { onRuntimeSelected(RuntimeKind.TERMUX) },
                    )
                    RuntimeChoice(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.PhoneAndroid,
                        label = "手机 Shell",
                        selected = state.terminalRuntime == RuntimeKind.SYSTEM_SHELL,
                        enabled = true,
                        onClick = { onRuntimeSelected(RuntimeKind.SYSTEM_SHELL) },
                    )
                    RuntimeChoice(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.DeveloperMode,
                        label = "Root",
                        selected = state.terminalRuntime == RuntimeKind.ROOT_TERMUX,
                        enabled = state.capabilities.rootGranted,
                        onClick = { onRuntimeSelected(RuntimeKind.ROOT_TERMUX) },
                    )
                }
            }
        }

        if (state.terminalRuntime == RuntimeKind.TERMUX && !state.capabilities.internalTermuxReady) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Outlined.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text("开发环境尚未就绪", style = MaterialTheme.typography.titleMedium)
                Text(
                    state.internalRuntimeInstallMessage ?: "首次启动需要解压内置 runtime",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!state.internalRuntimeInstalling) {
                    OutlinedButton(onClick = onInstallRuntime) {
                        Text("安装开发环境")
                    }
                }
            }
            return
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { context ->
                TerminalView(context, null).apply {
                    setBackgroundColor(Color.rgb(11, 15, 20))
                    terminalManager.attach(this)
                    terminalView = this
                }
            },
        )
    }
}

@Composable
private fun RuntimeChoice(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }
    Surface(
        modifier = modifier
            .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
        onClick = onClick,
        enabled = enabled,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.66f)
        } else {
            ComposeColor.Transparent
        },
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}
