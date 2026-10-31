package dev.pocketagent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.pocketagent.data.ChatAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentComposer(
    state: AppUiState,
    onDraft: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onImages: (List<Uri>) -> Unit,
    onMentionQuery: (String?) -> Unit,
    onFile: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRemoveQueued: (Long) -> Unit,
    onEditQueued: (Long, String) -> Unit,
    onSteerQueued: (Long) -> Unit,
    onResumeQueue: () -> Unit,
    onPauseQueue: () -> Unit,
    onCompactContext: () -> Unit,
    onComposerCommand: (String) -> Unit,
) {
    var value by remember(state.currentConversationId) { mutableStateOf(TextFieldValue(state.agentInputDraft)) }
    LaunchedEffect(state.agentInputDraft) {
        if (value.text != state.agentInputDraft) value = TextFieldValue(
            state.agentInputDraft, TextRange(state.agentInputDraft.length),
        )
    }
    val mention = activeFileMention(value.text, value.selection.start, value.selection.collapsed)
    LaunchedEffect(mention?.query, state.currentConversationId) { onMentionQuery(mention?.query) }
    DisposableEffect(Unit) { onDispose { onMentionQuery(null) } }
    val canSend = !state.agentAttachmentsLoading &&
        (value.text.isNotBlank() || state.agentAttachments.isNotEmpty())
    val send = { if (canSend) onSend(value.text) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4), onImages)
    var commandsVisible by remember { mutableStateOf(false) }
    var confirmPlanExecution by remember(state.currentConversationId) { mutableStateOf(false) }
    val commandSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AgentQueueDock(state, onRemoveQueued, onEditQueued, onSteerQueued, onResumeQueue, onPauseQueue)
        if (commandsVisible) {
            ModalBottomSheet(
                onDismissRequest = { commandsVisible = false },
                sheetState = commandSheetState,
            ) {
                ComposerCommandPanel(
                    state = state,
                    onCompact = {
                        commandsVisible = false
                        onCompactContext()
                    },
                    onCommand = { command ->
                        commandsVisible = false
                        onComposerCommand(command)
                    },
                )
            }
        }
        if (confirmPlanExecution && state.agentPlanToExecute != null && !state.agentRunning) {
            AlertDialog(
                onDismissRequest = { confirmPlanExecution = false },
                title = { Text("执行这份计划？") },
                text = { Text("将退出只读规划模式，按当前执行权限实施上方计划。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmPlanExecution = false
                        onComposerCommand("/execute-plan")
                    }) { Text("确认执行") }
                },
                dismissButton = { TextButton(onClick = { confirmPlanExecution = false }) { Text("继续规划") } },
            )
        }
        if (state.agentPlanMode) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("规划模式 · 只读", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                if (state.agentPlanToExecute != null) {
                    TextButton(onClick = { confirmPlanExecution = true }, enabled = !state.agentRunning) {
                        Text("按计划执行")
                    }
                }
                TextButton(onClick = { onComposerCommand("/plan") }, enabled = !state.agentRunning) { Text("退出") }
            }
        }
        if (mention != null) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
                    item {
                        Text(if (state.mentionFilesLoading) "搜索工作区…" else if (state.mentionFiles.isEmpty()) "没有匹配文件" else "引用工作区文件",
                            Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(state.mentionFiles, key = { it.relativePath }) { file ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !state.agentAttachmentsLoading) {
                                onFile(file.relativePath)
                                val updated = replaceFileMention(value.text, mention, file.relativePath)
                                value = TextFieldValue(updated.first, TextRange(updated.second))
                                onDraft(updated.first)
                                onMentionQuery(null)
                            }.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Description, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(file.relativePath, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (state.agentAttachments.isNotEmpty()) AttachmentRow(state.agentAttachments, onRemoveAttachment)
        Surface(shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column {
                TextField(
                    value = value,
                    onValueChange = { value = it; onDraft(it.text) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    placeholder = { Text(if (state.agentRunning) "补充任务，发送后加入队列…" else if (state.agentPlanMode) "描述任务，先分析并制定计划…" else "描述任务，@ 引用文件…") },
                    maxLines = 5,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        commandsVisible = true
                    }) {
                        Icon(Icons.Outlined.Add, "更多功能", Modifier.size(21.dp))
                    }
                    IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !state.agentAttachmentsLoading && state.agentAttachments.count { it.isImage } < 4) {
                        Icon(Icons.Outlined.Image, "添加图片", Modifier.size(21.dp))
                    }
                    IconButton(onClick = {
                        val pos = value.selection.start
                        val prefix = if (pos > 0 && !value.text[pos - 1].isWhitespace()) " @" else "@"
                        val text = value.text.replaceRange(value.selection.min, value.selection.max, prefix)
                        value = TextFieldValue(text, TextRange(value.selection.min + prefix.length))
                        onDraft(text)
                        focusRequester.requestFocus()
                        keyboard?.show()
                    }) { Text("@", style = MaterialTheme.typography.titleMedium) }
                    if (state.agentAttachmentsLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.weight(1f))
                    if (state.agentRunning) IconButton(onClick = onStop) { Icon(Icons.Outlined.StopCircle, "停止任务") }
                    FilledIconButton(onClick = send, enabled = canSend, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Send, if (state.agentRunning) "加入队列" else "发送", Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun AttachmentRow(attachments: List<ChatAttachment>, onRemove: ((String) -> Unit)? = null) {
    var preview by remember { mutableStateOf<ChatAttachment?>(null) }
    preview?.let { attachment ->
        Dialog(onDismissRequest = { preview = null }) {
            Surface(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(attachment.name, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    if (attachment.isImage) AttachmentImage(attachment, Modifier.fillMaxWidth().heightIn(max = 480.dp), true)
                    else {
                        val content by produceState("读取中…", attachment.path) {
                            value = withContext(Dispatchers.IO) { runCatching { java.io.File(attachment.path).readText() }.getOrDefault("附件已丢失") }
                        }
                        SelectionContainer { Text(content, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(4.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
                    }
                    TextButton(onClick = { preview = null }, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
                }
            }
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(attachments, key = { it.id }) { attachment ->
            Surface(onClick = { preview = attachment }, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.padding(start = 8.dp, end = if (onRemove == null) 10.dp else 0.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (attachment.isImage) AttachmentImage(attachment, Modifier.size(36.dp))
                    else Icon(Icons.Outlined.Description, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(attachment.sourcePath ?: attachment.name, Modifier.widthIn(max = 160.dp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    if (onRemove != null) IconButton(onClick = { onRemove(attachment.id) }, Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Close, "移除 ${attachment.name}", Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun AttachmentImage(attachment: ChatAttachment, modifier: Modifier, full: Boolean = false) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, attachment.path, full) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(attachment.path, BitmapFactory.Options().apply { inSampleSize = if (full) 1 else 8 }) }.getOrNull()
        }
    }
    val decoded = bitmap
    if (decoded != null) Image(decoded.asImageBitmap(), attachment.name, modifier, contentScale = if (full) ContentScale.Fit else ContentScale.Crop)
    else Box(modifier, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.BrokenImage, "图片不可用") }
}

@Composable
internal fun CopyableCodeBlock(language: String, code: String) {
    val context = LocalContext.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(language.ifBlank { "code" }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("code", code))
                    copied = true
                }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp)); Text(if (copied) "已复制" else "复制", style = MaterialTheme.typography.labelSmall)
                }
            }
            SelectionContainer {
                Text(code, Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 11.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), softWrap = false)
            }
        }
    }
}
