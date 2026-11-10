package dev.pocketagent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.pocketagent.data.PromptPreset
import dev.pocketagent.data.PromptPresets
import kotlinx.coroutines.launch

class PromptPresetActions(
    val save: suspend (String?, String, String, String) -> Boolean,
    val select: suspend (String?) -> Boolean,
    val delete: suspend (String) -> Boolean,
)

@Composable
internal fun CustomPromptSettings(presets: PromptPresets, actions: PromptPresetActions) {
    var opened by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Agent 偏好", style = MaterialTheme.typography.titleSmall)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { opened = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentAvatar(presets.active?.avatarPath.orEmpty(), Modifier.size(36.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("自定义 Agent", style = MaterialTheme.typography.bodyMedium)
                    Text(presets.active?.name ?: "CodeMate · 默认 Agent",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (opened) PromptPresetManager(presets, actions) { opened = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptPresetManager(presets: PromptPresets, actions: PromptPresetActions, onClose: () -> Unit) {
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var newId by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    val editable = presets.loadError == null
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val select: (String?) -> Unit = { id ->
        busy = true
        scope.launch {
            try { failed = !actions.select(id) } finally { busy = false }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("自定义 Agent", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = { IconButton(onClick = onClose, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    } },
                    actions = { IconButton(onClick = { newId = java.util.UUID.randomUUID().toString(); creating = true }, enabled = !busy && editable) {
                        Icon(Icons.Outlined.Add, "新建 Agent")
                    } },
                )
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).selectableGroup(),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Text("选择 Agent 的名字、头像和角色。角色提示词追加到内置规则后，切换后下次任务生效，适用于所有模型与会话。",
                        Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                presets.loadError?.let { error -> item {
                    Text(error, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error)
                } }
                if (failed) item {
                    Text("切换失败，仍使用原预设，请重试。", Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.error)
                }
                item {
                    PromptPresetRow("CodeMate · 默认", "仅使用内置通用提示词", presets.active == null, !busy && editable,
                        onSelect = { select(null) })
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                }
                items(presets.items, key = { it.id }) { preset ->
                    PromptPresetRow(preset.name, preset.content, presets.activeId == preset.id, !busy,
                        avatarPath = preset.avatarPath, onSelect = { select(preset.id) }, onEdit = { editorId = preset.id })
                }
                item {
                    TextButton(onClick = { newId = java.util.UUID.randomUUID().toString(); creating = true }, enabled = !busy && editable,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("新建 Agent")
                    }
                }
            }
        }
        val editing = presets.items.firstOrNull { it.id == editorId }
        if (creating || editing != null) {
            key(editorId, creating) {
                PromptPresetEditor(editing, newId, presets.items.firstOrNull { it.id == (editing?.id ?: newId) },
                    presets.activeId == editing?.id && editing != null, actions) {
                    creating = false; editorId = null
                }
            }
        }
    }
}

@Composable
private fun PromptPresetRow(
    name: String, summary: String, selected: Boolean, enabled: Boolean,
    onSelect: () -> Unit, onEdit: (() -> Unit)? = null, avatarPath: String = "",
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 80.dp)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(12.dp))
            AgentAvatar(avatarPath, Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onEdit != null) IconButton(onClick = onEdit, enabled = enabled) {
            Icon(Icons.Outlined.Edit, "编辑 $name", Modifier.size(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptPresetEditor(preset: PromptPreset?, newId: String, persisted: PromptPreset?, active: Boolean,
    actions: PromptPresetActions, onClose: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(preset?.name.orEmpty()) }
    var avatarPath by rememberSaveable { mutableStateOf(preset?.avatarPath.orEmpty()) }
    var submittedAvatar by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf(preset?.content.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var submittedName by rememberSaveable { mutableStateOf<String?>(null) }
    var submittedContent by rememberSaveable { mutableStateOf<String?>(null) }
    // A completed write survives rotation even though the old composition's callback does not.
    LaunchedEffect(persisted, submittedName, submittedContent, submittedAvatar) {
        if (submittedName != null && persisted?.name == submittedName && persisted?.content == submittedContent &&
            name.trim() == submittedName && draft.trim() == submittedContent &&
            persisted?.avatarPath == submittedAvatar && avatarPath == submittedAvatar) onClose()
    }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try { avatarPath = importAgentAvatar(context, uri); failure = null }
                catch (error: Exception) { failure = "头像导入失败，请换一张图片重试。" }
                finally { busy = false }
            }
        }
    }
    val tooLong = draft.length > PromptPresets.MAX_CONTENT_LENGTH
    val badName = name.trim().length > PromptPresets.MAX_NAME_LENGTH
    val changed = avatarPath != preset?.avatarPath.orEmpty() || name != preset?.name.orEmpty() || draft != preset?.content.orEmpty()
    val requestClose: () -> Unit = { if (!busy) { if (changed) confirmDiscard = true else onClose() } }

    Dialog(onDismissRequest = requestClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                TopAppBar(
                    title = { Text(if (preset == null) "新建 Agent" else "编辑 Agent", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = { IconButton(onClick = requestClose, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回 Agent 列表")
                    } },
                    actions = {
                        TextButton(onClick = {
                            submittedName = name.trim()
                            submittedContent = draft.trim()
                            submittedAvatar = avatarPath
                            busy = true
                            scope.launch {
                                try {
                                    if (actions.save(preset?.id ?: newId, name, draft, avatarPath)) onClose()
                                    else failure = "保存失败，草稿已保留，请重试。"
                                } finally { busy = false }
                            }
                        }, enabled = changed && name.isNotBlank() && draft.isNotBlank() && !badName && !tooLong && !busy) {
                            Text(if (busy) "处理中…" else "保存")
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AgentAvatar(avatarPath, Modifier.size(64.dp))
                    Column {
                        TextButton(onClick = { picker.launch("image/*") }, enabled = !busy) {
                            Text(if (busy) "处理中…" else "选择头像")
                        }
                        if (avatarPath.isNotBlank()) TextButton(onClick = { avatarPath = "" }, enabled = !busy) {
                            Text("恢复默认头像")
                        }
                    }
                }
                OutlinedTextField(name, { name = it; failure = null }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("Agent 名字") }, placeholder = { Text("例如：Kotlin 导师") }, singleLine = true,
                    isError = badName, supportingText = { if (badName) Text("名称最多 ${PromptPresets.MAX_NAME_LENGTH} 字符") })
                OutlinedTextField(draft, { draft = it; failure = null }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("角色提示词") },
                    placeholder = { Text("例如：你是一位耐心的 Kotlin 导师。默认用中文，先给结论，再解释关键代码。") },
                    minLines = 8, maxLines = 16, isError = tooLong,
                    supportingText = { Text("${draft.length} / ${PromptPresets.MAX_CONTENT_LENGTH} 字符${if (tooLong) " · 已超出限制" else ""}") })
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(if (active) "此 Agent 正在使用，保存后下次任务生效。" else "保存后回到列表，点选 Agent 即可使用。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("自定义内容不替换基础规则与工具权限，不影响独立生图。头像仅保存在本机；名字和提示词会发送给当前 API 服务，请勿填写密码或密钥。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (preset != null) TextButton(onClick = { confirmDelete = true }, enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("删除 Agent", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (confirmDiscard) AlertDialog(
            onDismissRequest = { confirmDiscard = false }, title = { Text("放弃未保存的更改？") },
            text = { Text("返回后将保留上次保存的内容。") },
            confirmButton = { TextButton(onClick = onClose) { Text("放弃更改") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
        )
        if (confirmDelete && preset != null) AlertDialog(
            onDismissRequest = { if (!busy) confirmDelete = false }, title = { Text("删除“${preset.name}”？") },
            text = { Text(if (active) "删除后将恢复默认 Agent，下次任务生效。此操作无法撤销。" else "此操作无法撤销。") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        if (actions.delete(preset.id)) onClose() else {
                            confirmDelete = false; failure = "删除失败，预设已保留，请重试。"
                        }
                    } finally { busy = false }
                }
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }, enabled = !busy) { Text("取消") } },
        )
    }
}
