package dev.pocketagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketagent.extensions.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnlineExtensions(kind: String, state: AppUiState, actions: ExtensionActions, onDismiss: () -> Unit) {
    val catalog = state.onlineCatalog
    val skills = kind == "skills"
    var query by rememberSaveable(kind) { mutableStateOf("") }
    var sourceMenu by remember { mutableStateOf(false) }
    var addSource by remember { mutableStateOf(false) }
    val defaultSource = if (skills) CatalogSources.skills.first() else CatalogSources.chineseMcp
    val source = if (catalog.kind == kind) catalog.source else defaultSource
    LaunchedEffect(kind) { actions.browseCatalog(kind, source, "", false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (skills) "技能仓库" else "MCP 目录", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = actions.refreshCatalog, enabled = !catalog.loading && catalog.busy == null) { Icon(Icons.Outlined.Refresh, "刷新在线仓库") }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭在线仓库") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { sourceMenu = true }, enabled = catalog.busy == null) {
                        Text(CatalogSources.labels[source] ?: source, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                    }
                    DropdownMenu(sourceMenu, { sourceMenu = false }) {
                        (if (skills) catalog.sources else listOf(CatalogSources.chineseMcp, CatalogSources.officialMcp)).forEach { repo ->
                            DropdownMenuItem(text = { Column { Text(CatalogSources.labels[repo] ?: repo); if (repo != CatalogSources.officialMcp) Text(repo, style = MaterialTheme.typography.labelSmall) } },
                                onClick = { sourceMenu = false; query = ""; actions.browseCatalog(kind, repo, "", false) })
                        }
                        if (skills) DropdownMenuItem(text = { Text("添加 GitHub 仓库…") }, onClick = { sourceMenu = false; addSource = true })
                    }
                }
                if (source != CatalogSources.officialMcp) TextButton(onClick = { actions.readDocument(source) }) { Text("仓库文档") }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), singleLine = true,
                placeholder = { Text(if (skills) "搜索技能名称或用途" else "搜索 MCP 名称、用途或分类") },
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) },
                trailingIcon = { if (!skills) IconButton(onClick = { actions.browseCatalog(kind, source, query, false) }) { Icon(Icons.Outlined.Search, "搜索") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (!skills) actions.browseCatalog(kind, source, query, false) }))
            if (catalog.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
            catalog.error?.let { error ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = actions.refreshCatalog) { Text("重试") }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 480.dp)) {
                if (skills) {
                    val filtered = catalog.skills.filter { listOf(it.name, it.title, it.description, it.path).any { text -> text.contains(query, true) } }
                    items(filtered, key = { it.key }) { item ->
                        CatalogRow(item.title.ifBlank { item.name }, item.description.ifBlank { item.path },
                            if (catalog.installed[item.key] == item.revision) "已安装" else if (item.key in catalog.installed) "可更新" else "", { actions.inspectSkill(item) })
                    }
                    if (filtered.isEmpty() && !catalog.loading && catalog.error == null) item { EmptyCatalog("没有找到技能") }
                } else {
                    items(catalog.servers, key = { it.name }) { item ->
                        CatalogRow(item.title, item.description, if (state.mcpServers.any { it.name == item.name }) "已添加" else item.category.removePrefix("🌐 ").take(10), { actions.inspectServer(item) })
                    }
                    if (catalog.cursor.isNotEmpty()) item {
                        TextButton(onClick = { actions.browseCatalog(kind, source, catalog.query, true) }, Modifier.fillMaxWidth(), enabled = !catalog.loading) { Text("加载更多") }
                    }
                    if (catalog.servers.isEmpty() && !catalog.loading && catalog.error == null) item { EmptyCatalog("没有找到服务") }
                }
            }
        }
    }
    if (addSource) {
        var repo by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { addSource = false }, title = { Text("添加技能仓库") }, text = {
            Column {
                OutlinedTextField(repo, { repo = it; error = null }, label = { Text("GitHub 仓库") }, placeholder = { Text("owner/repo") }, singleLine = true)
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = {
            try { val normalized = normalizeSkillRepository(repo); actions.browseCatalog(kind, normalized, "", false); addSource = false; query = "" }
            catch (failure: Exception) { error = failure.message }
        }) { Text("添加") } }, dismissButton = { TextButton(onClick = { addSource = false }) { Text("取消") } })
    }
    if (skills && catalog.selectedSkill != null) {
        val item = catalog.selectedSkill
        ModalBottomSheet(onDismissRequest = { actions.inspectSkill(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
                Text(item.title.ifBlank { catalog.detail?.name ?: item.name }, style = MaterialTheme.typography.titleMedium)
                Text("${item.repo} · ${item.revision.take(7)}", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (catalog.loading || catalog.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                catalog.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                catalog.detail?.let { detail ->
                    if (detail.license.isNotBlank()) Text("许可：${detail.license}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(detail.description, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    RepositoryText(detail.content, Modifier.fillMaxWidth().heightIn(max = 330.dp))
                }
                Text("按原仓库条款使用，安装保留技能目录、脚本和参考资料。",
                    Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { actions.readDocument(item.repo) }) { Text("仓库文档") }
                    val installed = catalog.installed[item.key]
                    Button(onClick = { actions.installSkill(item) }, enabled = catalog.detail != null && installed != item.revision && catalog.busy == null && !state.agentRunning) {
                        Text(if (catalog.busy == item.key) "安装中…" else if (installed == item.revision) "已安装" else if (installed != null) "更新技能" else "安装技能")
                    }
                }
                if (state.agentRunning) Text("当前任务结束后可安装", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    catalog.selectedServer?.let { item -> CatalogMcpDetail(item, state, actions) }
    catalog.documentRepo?.let { repo ->
        val uri = LocalUriHandler.current
        ModalBottomSheet(onDismissRequest = { actions.readDocument(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("仓库文档", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { runCatching { uri.openUri("https://github.com/$repo") } }) { Text("打开来源") }
                }
                Text(repo + (catalog.document?.let { " · ${it.path}" } ?: ""), Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (catalog.documentLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                catalog.documentError?.let { error -> Text(error, color = MaterialTheme.colorScheme.error); TextButton(onClick = { actions.readDocument(repo) }) { Text("重试") } }
                catalog.document?.let { document -> RepositoryText(document.content, Modifier.fillMaxWidth().heightIn(max = 520.dp)) }
            }
        }
    }
}

@Composable
private fun RepositoryText(content: String, modifier: Modifier = Modifier) {
    // Paragraphs stay lazy even when a directory README contains thousands of entries.
    val sections = remember(content) { buildList {
        val block = StringBuilder()
        var fenced = false
        content.lineSequence().forEach { line ->
            if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) fenced = !fenced
            if (line.isBlank() && !fenced) {
                if (block.isNotBlank()) { add(block.toString()); block.clear() }
            } else { block.appendLine(line) }
        }
        if (block.isNotBlank()) add(block.toString())
    } }
    SelectionContainer { LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(sections.size) { index -> MarkdownContent(sections[index]) }
    } }
}

@Composable
private fun EmptyCatalog(text: String) { Text(text, Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
private fun CatalogRow(title: String, description: String, status: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status.isNotEmpty()) Text(status, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(description, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogMcpDetail(item: CatalogMcp, state: AppUiState, actions: ExtensionActions) {
    var choice by remember(item.name) { mutableIntStateOf(0) }
    var values by remember(item.name, choice) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    val option = item.options.getOrNull(choice)
    val fields = remember(option) { option?.inputs().orEmpty() }
    val catalog = state.onlineCatalog
    ModalBottomSheet(onDismissRequest = { actions.inspectServer(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            if (item.version.isNotEmpty()) Text("版本 ${item.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(item.description, style = MaterialTheme.typography.bodyMedium) }
            if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.repository.startsWith("https://github.com/")) TextButton(onClick = {
                actions.readDocument(item.repository.removePrefix("https://github.com/").split('/').take(2).joinToString("/"))
            }) { Text("项目文档 · 优先中文") }
            if (catalog.serverLoading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在查找可导入配置…", style = MaterialTheme.typography.bodySmall) }
            catalog.serverError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (option == null && !catalog.serverLoading) Text("未找到可自动导入的兼容配置。可先阅读项目文档，再通过 MCP 设置手动添加或导入 JSON。", style = MaterialTheme.typography.bodySmall)
            item.options.forEachIndexed { index, candidate ->
                Row(Modifier.fillMaxWidth().clickable { choice = index; error = null }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(choice == index, { choice = index; error = null })
                    Text(candidate.label, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            fields.forEach { field ->
                OutlinedTextField(values[field.key] ?: field.default, { values = values + (field.key to it); error = null }, Modifier.fillMaxWidth(),
                    label = { Text(field.label + if (field.required) " *" else "（选填）") }, singleLine = true,
                    supportingText = { if (field.description.isNotBlank() || field.choices.isNotEmpty()) Text((field.description + if (field.choices.isNotEmpty()) " 可选：${field.choices.joinToString()}" else "").trim()) },
                    visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None)
            }
            if (option != null) {
                Text(if (option.local) "本地服务需要 Termux 的 ${if (option.label.startsWith("npm")) "Node.js / npx" else "Python / uvx"}，首次连接会下载指定版本。部分依赖可能不支持 Android。" else "添加后可测试连接；密钥加密保存在本机。OAuth 暂需手动填写访问令牌。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                val existing = state.mcpServers.firstOrNull { it.name == item.name }
                Button(onClick = {
                    try {
                        val config = option.config(item.name, values)
                        actions.saveServer(if (existing != null) config.copy(id = existing.id) else config)
                        actions.inspectServer(null)
                    } catch (failure: Exception) { error = failure.message ?: "配置无效" }
                }, Modifier.fillMaxWidth(), enabled = !state.agentRunning) { Text(if (existing != null) "替换已有配置" else "添加服务") }
            }
        }
    }
}
