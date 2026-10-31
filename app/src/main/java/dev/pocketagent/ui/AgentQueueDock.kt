package dev.pocketagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A single waiting message stays directly actionable; multiple messages share a folding dock. */
@Composable
internal fun AgentQueueDock(
    state: AppUiState,
    onRemove: (Long) -> Unit,
    onEdit: (Long, String) -> Unit,
    onSteer: (Long) -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
) {
    val queue = state.queuedPrompts
    var expanded by rememberSaveable(state.currentConversationId) { mutableStateOf(false) }
    var editingId by rememberSaveable(state.currentConversationId) { mutableStateOf<Long?>(null) }
    var editedText by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(queue) {
        if (queue.none { it.id == editingId && !it.steering }) editingId = null
        if (queue.isEmpty()) expanded = false
    }
    if (queue.isEmpty()) return
    val showRows = queue.size == 1 || expanded || editingId != null
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            if (queue.size > 1 || state.queuePaused) {
                Row(Modifier.fillMaxWidth().clickable(enabled = editingId == null) { expanded = !expanded }
                    .padding(start = 10.dp, end = 4.dp).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Queue, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.queuePaused) "${queue.size} 条排队消息 · 已暂停" else "${queue.size} 条排队消息",
                        Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    if (state.queuePaused) TextButton(onClick = onResume, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("继续", style = MaterialTheme.typography.labelMedium)
                    }
                    else IconButton(onClick = onPause, Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Pause, "暂停队列", Modifier.size(16.dp))
                    }
                    if (queue.size > 1) Icon(
                        if (showRows) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                        if (showRows) "折叠队列" else "展开队列", Modifier.size(18.dp),
                    )
                }
            }
            if (showRows) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                items(queue, key = { it.id }) { prompt ->
                    val editing = editingId == prompt.id
                    if (queue.size > 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 3.dp, bottom = 3.dp).heightIn(min = 38.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (queue.size == 1 && !state.queuePaused) {
                            Icon(Icons.Outlined.Queue, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                        }
                        if (!editing) prompt.attachments.firstOrNull()?.let { attachment ->
                            if (attachment.isImage) AttachmentImage(attachment, Modifier.size(24.dp))
                            else Icon(Icons.Outlined.Description, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            if (editing) {
                                val requester = remember { FocusRequester() }
                                LaunchedEffect(Unit) { requester.requestFocus() }
                                OutlinedTextField(editedText, { editedText = it }, Modifier.fillMaxWidth().focusRequester(requester),
                                    textStyle = MaterialTheme.typography.bodySmall, maxLines = 3,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        if (editedText.isNotBlank() || prompt.attachments.isNotEmpty()) { onEdit(prompt.id, editedText); editingId = null }
                                    }))
                            } else {
                                Text(prompt.text.ifBlank { prompt.attachments.joinToString("、") { it.name } }, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!editing && prompt.attachments.isNotEmpty() && prompt.text.isNotBlank()) {
                                Text(prompt.attachments.joinToString(" · ") { it.name }, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (prompt.taskMode == dev.pocketagent.agent.AgentTaskMode.PLAN) {
                                Text("规划任务 · 只读", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            if (prompt.steering) Text("将在下一步插话", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        if (editing) {
                            IconButton(onClick = { onEdit(prompt.id, editedText); editingId = null },
                                enabled = editedText.isNotBlank() || prompt.attachments.isNotEmpty(), modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Check, "保存排队消息", Modifier.size(16.dp))
                            }
                            IconButton(onClick = { editingId = null }, Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Close, "取消编辑", Modifier.size(16.dp))
                            }
                        } else {
                            IconButton(onClick = { editingId = prompt.id; editedText = prompt.text; expanded = true },
                                enabled = !prompt.steering, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Edit, "编辑排队消息", Modifier.size(16.dp))
                            }
                            IconButton(onClick = { onRemove(prompt.id) }, enabled = !prompt.steering, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.DeleteOutline, "删除排队消息", Modifier.size(16.dp))
                            }
                            IconButton(onClick = { onSteer(prompt.id) }, enabled = state.agentRunning && !prompt.steering &&
                                    state.agentActiveTaskMode == dev.pocketagent.agent.AgentTaskMode.EXECUTE &&
                                    prompt.taskMode == dev.pocketagent.agent.AgentTaskMode.EXECUTE,
                                modifier = Modifier.size(32.dp)) {
                                Icon(Icons.AutoMirrored.Outlined.Send, "插话发送", Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
