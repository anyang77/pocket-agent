package dev.pocketagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketagent.extensions.SubagentTask

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubagentDock(tasks: List<SubagentTask>, onStop: (String) -> Unit) {
    if (tasks.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccountTree, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("子代理 · ${tasks.count { it.running }} 个运行中 / ${tasks.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text(if (expanded) "收起" else "查看", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                tasks.forEach { task ->
                    Row(Modifier.fillMaxWidth().clickable { selectedId = task.id }.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(task.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${task.status} · ${task.tokens} tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (task.running) IconButton(onClick = { onStop(task.id) }, Modifier.size(36.dp)) { Icon(Icons.Outlined.StopCircle, "停止子代理 ${task.title}", Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
    tasks.firstOrNull { it.id == selectedId }?.let { task ->
        ModalBottomSheet(onDismissRequest = { selectedId = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(task.title, style = MaterialTheme.typography.titleMedium)
                Text("${task.status} · ${task.tokens} tokens", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                SelectionContainer { Text(task.task, style = MaterialTheme.typography.bodySmall) }
                if (task.detail.isNotBlank()) Text(task.detail, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (task.result.isNotBlank()) SelectionContainer { Text(task.result, style = MaterialTheme.typography.bodyMedium) }
                if (task.running) TextButton(onClick = { onStop(task.id) }) { Text("停止此子代理") }
            }
        }
    }
}
