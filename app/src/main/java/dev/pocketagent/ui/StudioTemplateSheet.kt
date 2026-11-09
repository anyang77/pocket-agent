package dev.pocketagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Multi-line list content must not inherit a Button's pill-shaped clipping. */
@Composable
internal fun StudioTemplateListRow(
    title: String,
    description: String,
    supportingText: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 4.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (supportingText.isNotBlank()) Text(supportingText, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun StudioTemplateSheet(
    enabled: Boolean,
    hasDraft: Boolean,
    model: String,
    allowNetwork: Boolean,
    onDismiss: () -> Unit,
    onApplyPrompt: (String) -> Unit,
    onApply: (String, String) -> Unit,
) {
    val onlineState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val repository = remember { StudioPromptRepository() }
    var online by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf("全部") }
    var query by rememberSaveable { mutableStateOf("") }
    val choices = remember(category, query) { studioTemplateChoices(category, query) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = studioTemplates.firstOrNull { it.id == selectedId }
    var values by rememberSaveable(selectedId) { mutableStateOf(selected?.fields?.map { it.example }.orEmpty()) }
    var replace by remember { mutableStateOf(false) }
    fun apply() {
        selected?.let { onApply(it.compose(values), studioTemplateSize(it.size, model)) }
    }
    if (replace) AlertDialog(
        onDismissRequest = { replace = false },
        title = { Text("替换当前草稿？") },
        text = { Text("将用模板描述和推荐画幅替换当前草稿。不会生成图片，也不会修改已有作品。") },
        confirmButton = { TextButton(enabled = enabled, onClick = { replace = false; apply() }) { Text("替换草稿") } },
        dismissButton = { TextButton(onClick = { replace = false }) { Text("保留草稿") } },
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("创作模板", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            TabRow(selectedTabIndex = if (online) 1 else 0) {
                Tab(selected = !online, onClick = { online = false }, text = { Text("本地配方") })
                Tab(selected = online, onClick = { online = true }, text = { Text("在线提示词") })
            }
            if (online) {
                onlineState.SaveableStateProvider("online-prompts") {
                    StudioOnlinePrompts(enabled, allowNetwork, hasDraft, repository, onApplyPrompt,
                        modifier = Modifier.weight(1f))
                }
            } else LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selected != null) IconButton(onClick = { selectedId = null }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回模板库")
                    }
                    Column(Modifier.weight(1f)) {
                        if (selected != null) Text(selected.title, style = MaterialTheme.typography.titleMedium)
                        Text(if (selected == null) "CodeMate 原创配方 · 无需联网，按用途编辑创作要素" else selected.description,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (selected == null) {
                item {
                    OutlinedTextField(value = query, onValueChange = { query = it },
                        label = { Text("搜索用途、风格或模板") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
                item {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        (listOf("全部") + studioTemplates.map { it.category }.distinct()).forEach { label ->
                            FilterChip(selected = category == label, onClick = { category = label }, label = { Text(label) })
                        }
                    }
                }
                if (choices.isEmpty()) item {
                    Text("没有匹配的模板，试试其他关键词或切换分类。", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                }
                items(choices, key = { it.id }) { template ->
                    StudioTemplateListRow(
                        title = template.title,
                        description = template.description,
                        supportingText = "${template.category} · ${sizeLabel(template.size)}",
                        onClick = { selectedId = template.id },
                    )
                    HorizontalDivider()
                }
            } else {
                items(selected.fields.indices.toList(), key = { it }) { index ->
                    OutlinedTextField(
                        value = values.getOrElse(index) { "" },
                        onValueChange = { text -> values = values.toMutableList().also { it[index] = text.take(2000) } },
                        label = { Text(selected.fields[index].label) },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                    )
                }
                item {
                    Text("提示词预览", style = MaterialTheme.typography.titleSmall)
                    Text(selected.compose(values), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Text("推荐${sizeLabel(studioTemplateSize(selected.size, model))} · 仅填入描述和画幅，保留当前模型与张数；可在发送前继续编辑。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = enabled && values.firstOrNull()?.isNotBlank() == true,
                        onClick = { if (hasDraft) replace = true else apply() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text("使用模板")
                    }
                }
            }
        }
        }
    }
}
