package dev.pocketagent.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val terminalBackground = MaterialTheme.colorScheme.surface

    LaunchedEffect(terminalManager, darkTheme) {
        terminalManager.setDarkTheme(darkTheme)
    }

    DisposableEffect(Unit) {
        onDispose {
            terminalView?.let(terminalManager::detach)
        }
    }

    Column(modifier.imePadding()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RuntimeChoice(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.FolderOpen,
                        label = "Termux",
                        selected = state.terminalRuntime != RuntimeKind.SYSTEM_SHELL,
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
                    TextButton(
                        onClick = terminalManager::restart,
                        enabled = state.terminalRuntime == RuntimeKind.SYSTEM_SHELL ||
                            state.capabilities.internalTermuxReady,
                    ) {
                        Text("重启")
                    }
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
                    setBackgroundColor(terminalBackground.toArgb())
                    terminalManager.attach(this)
                    terminalView = this
                }
            },
        )
        TerminalShortcutBar(terminalManager)
    }
}

@Composable
private fun TerminalShortcutBar(manager: TerminalSessionManager) {
    var menuVisible by remember { mutableStateOf(false) }
    val rows = listOf(
        listOf("Esc", "Tab", "PgUp", "Home", "↑", "End", "⋮"),
        listOf("Ctrl", "Alt", "PgDn", "←", "↓", "→", "⌨"),
    )
    val keyCodes = mapOf(
        "Esc" to KeyEvent.KEYCODE_ESCAPE,
        "Tab" to KeyEvent.KEYCODE_TAB,
        "PgUp" to KeyEvent.KEYCODE_PAGE_UP,
        "PgDn" to KeyEvent.KEYCODE_PAGE_DOWN,
        "Home" to KeyEvent.KEYCODE_MOVE_HOME,
        "End" to KeyEvent.KEYCODE_MOVE_END,
        "↑" to KeyEvent.KEYCODE_DPAD_UP,
        "↓" to KeyEvent.KEYCODE_DPAD_DOWN,
        "←" to KeyEvent.KEYCODE_DPAD_LEFT,
        "→" to KeyEvent.KEYCODE_DPAD_RIGHT,
    )
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(thickness = 0.5.dp)
            rows.forEach { keys ->
                Row(Modifier.fillMaxWidth()) {
                    keys.forEach { key ->
                        val active = when (key) {
                            "Ctrl" -> manager.controlPressed
                            "Alt" -> manager.altPressed
                            else -> false
                        }
                        Box(
                            modifier = Modifier.weight(1f).height(36.dp)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primaryContainer
                                    else ComposeColor.Transparent,
                                )
                                .semantics {
                                    contentDescription = when (key) {
                                        "⋮" -> "终端快捷操作"
                                        "⌨" -> "显示或隐藏键盘"
                                        else -> key
                                    }
                                    if (key == "Ctrl" || key == "Alt") selected = active
                                }
                                .clickable {
                                    when (key) {
                                        "Ctrl" -> manager.toggleControl()
                                        "Alt" -> manager.toggleAlt()
                                        "⋮" -> menuVisible = true
                                        "⌨" -> manager.toggleKeyboard()
                                        else -> keyCodes[key]?.let(manager::sendShortcutKey)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                key,
                                fontSize = 13.sp,
                                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                            )
                            if (key == "⋮") {
                                DropdownMenu(
                                    expanded = menuVisible,
                                    onDismissRequest = { menuVisible = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("粘贴") },
                                        onClick = { menuVisible = false; manager.pasteClipboard() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("中断 · Ctrl+C") },
                                        onClick = {
                                            menuVisible = false
                                            manager.sendControlShortcut(KeyEvent.KEYCODE_C)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("清屏 · Ctrl+L") },
                                        onClick = {
                                            menuVisible = false
                                            manager.sendControlShortcut(KeyEvent.KEYCODE_L)
                                        },
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

@Composable
private fun RuntimeChoice(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.66f)
        } else {
            ComposeColor.Transparent
        },
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
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
