package dev.pocketagent.ui

import android.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView

@Composable
fun TerminalScreen(
    state: AppUiState,
    terminalManager: TerminalSessionManager,
    onInstallRuntime: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            terminalView?.let(terminalManager::detach)
        }
    }

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("交互式 Shell", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (state.capabilities.internalTermuxReady) {
                        "完整命令行与工具链"
                    } else {
                        "正在准备内置开发环境"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = terminalManager::decreaseFont) {
                Text("A-")
            }
            TextButton(onClick = terminalManager::increaseFont) {
                Text("A+")
            }
            TextButton(
                onClick = terminalManager::restart,
                enabled = state.capabilities.internalTermuxReady,
            ) {
                Text("重启 Shell")
            }
        }

        if (!state.capabilities.internalTermuxReady) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    state.internalRuntimeInstallMessage ?: "首次启动需要解压内置 runtime",
                    style = MaterialTheme.typography.bodyMedium,
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
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
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
