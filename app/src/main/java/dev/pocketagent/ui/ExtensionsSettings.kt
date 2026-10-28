package dev.pocketagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketagent.extensions.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Settings actions stay together so the extensions panel can be used from any settings layout. */
class ExtensionActions(
    val refresh: () -> Unit,
    val saveSettings: (ExtensionSettings) -> Unit,
    val setSkillEnabled: (String, Boolean) -> Unit,
    val saveSkill: (String?, String, String, String) -> Unit,
    val removeSkill: (String) -> Unit,
    val saveServer: (McpServerConfig) -> Unit,
    val removeServer: (String) -> Unit,
    val importServers: (String) -> Unit,
    val testServer: (String) -> Unit,
    val browseCatalog: (String, String, String, Boolean) -> Unit,
    val refreshCatalog: () -> Unit,
    val inspectSkill: (CatalogSkill?) -> Unit,
    val installSkill: (CatalogSkill) -> Unit,
    val inspectServer: (CatalogMcp?) -> Unit,
    val readDocument: (String?) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtensionsSettings(state: AppUiState, actions: ExtensionActions) {
    var online by rememberSaveable { mutableStateOf<String?>(null) }
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var editServer by remember { mutableStateOf<McpServerConfig?>(null) }
    var newServer by rememberSaveable { mutableStateOf(false) }
    var importConfig by rememberSaveable { mutableStateOf(false) }
    var editSkill by remember { mutableStateOf<SkillInfo?>(null) }
    var newSkill by rememberSaveable { mutableStateOf(false) }
    var viewSkill by remember { mutableStateOf<SkillInfo?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Agent 扩展", style = MaterialTheme.typography.titleSmall)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Column {
                ExtensionRow(Icons.Outlined.MenuBook, "技能", "${state.skills.count { it.enabled }} 项已启用") { panel = "技能"; actions.refresh() }
                HorizontalDivider(Modifier.padding(start = 52.dp))
                ExtensionRow(Icons.Outlined.Extension, "MCP 服务", "${state.mcpServers.count { it.enabled }} 项已启用") { panel = "MCP 服务"; actions.refresh() }
                HorizontalDivider(Modifier.padding(start = 52.dp))
                ExtensionRow(Icons.Outlined.AccountTree, "子代理", if (state.extensionSettings.subagentsEnabled) "最多 ${state.extensionSettings.maxSubagents} 个并行任务" else "已关闭") { panel = "子代理" }
            }
        }
    }
    panel?.let { title ->
        ModalBottomSheet(onDismissRequest = { panel = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (title == "技能") {
                        IconButton(onClick = actions.refresh) { Icon(Icons.Outlined.Refresh, "刷新技能") }
                        IconButton(onClick = { newSkill = true }) { Icon(Icons.Outlined.Add, "创建技能") }
                    }
                    if (title == "MCP 服务") {
                        TextButton(onClick = { importConfig = true }, enabled = !state.agentRunning) { Text("导入 JSON") }
                        IconButton(onClick = { newServer = true }, enabled = !state.agentRunning) { Icon(Icons.Outlined.Add, "添加 MCP 服务") }
                    }
                }
                if (title == "技能" || title == "MCP 服务") {
                    TextButton(onClick = { online = if (title == "技能") "skills" else "mcp" }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (title == "技能") "浏览在线技能仓库" else "浏览 MCP 在线目录")
                    }
                }
                when (title) {
                    "子代理" -> {
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("允许使用子代理", style = MaterialTheme.typography.bodyMedium)
                                Text("复杂任务可并行检索、分析和复核。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(state.extensionSettings.subagentsEnabled, { actions.saveSettings(state.extensionSettings.copy(subagentsEnabled = it)) })
                        }
                        Text("最大并行数", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (1..3).forEach { count -> FilterChip(state.extensionSettings.maxSubagents == count,
                                { actions.saveSettings(state.extensionSettings.copy(maxSubagents = count)) }, label = { Text("$count") }) }
                        }
                        Text("子代理使用当前模型，独立读取工作区；主 Agent 汇总结果并修改文件。每个子任务会消耗模型额度，可在对话中查看进度和停止。",
                            Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    "技能" -> {
                        Text("内置流程和自定义 SKILL.md。工作区技能从 .agents/skills 或 .codemate/skills 读取。",
                            Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn(Modifier.heightIn(max = 480.dp)) {
                            items(state.skills, key = { it.id }) { skill ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).clickable { viewSkill = skill }) {
                                        Text(skill.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(skill.description, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(if (skill.builtIn) "内置" else if (skill.source.isNotBlank()) "${skill.source} · ${skill.revision.take(7)}" else skill.path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (!skill.builtIn && "/files/skills/" in skill.path) {
                                        IconButton(onClick = { editSkill = skill }) { Icon(if (skill.source.isNotBlank()) Icons.Outlined.Settings else Icons.Outlined.Edit, "编辑 ${skill.name}", Modifier.size(18.dp)) }
                                    }
                                    Switch(skill.enabled, { actions.setSkillEnabled(skill.id, it) })
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                    "MCP 服务" -> {
                        Text("支持 Streamable HTTP 与本机 stdio。连接后工具会提供给 Agent，调用过程显示在对话中。",
                            Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.mcpServers.isEmpty()) Text("尚未添加服务", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn(Modifier.heightIn(max = 480.dp)) {
                            items(state.mcpServers, key = { it.id }) { config ->
                                val connection = state.mcpStates[config.id]
                                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f).clickable { editServer = config }) {
                                            Text(config.name, style = MaterialTheme.typography.bodyMedium)
                                            Text(if (config.transport == "http") config.url else "${config.command} ${config.args.joinToString(" ")}",
                                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        IconButton(onClick = { editServer = config }, enabled = !state.agentRunning) { Icon(Icons.Outlined.Edit, "编辑 ${config.name}", Modifier.size(18.dp)) }
                                        Switch(config.enabled, { actions.saveServer(config.copy(enabled = it)) }, enabled = !state.agentRunning)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(connection?.let { "${it.status}${if (it.toolCount > 0) " · ${it.toolCount} 个工具" else ""}" } ?: "未连接",
                                            Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        TextButton(onClick = { actions.testServer(config.id) }, enabled = !state.agentRunning && connection?.connecting != true) {
                                            Text(if (connection?.connecting == true) "连接中…" else "测试连接")
                                        }
                                    }
                                    connection?.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                                    if (connection?.tools?.isNotEmpty() == true) Text(connection.tools.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
    online?.let { OnlineExtensions(it, state, actions) { online = null; actions.inspectSkill(null) } }
    if (newServer || editServer != null) McpServerEditor(editServer, { newServer = false; editServer = null },
        onSave = { actions.saveServer(it); newServer = false; editServer = null }, onDelete = { actions.removeServer(it); editServer = null })
    if (importConfig) {
        var text by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { importConfig = false }, title = { Text("导入 MCP 配置") }, text = {
            Column {
                OutlinedTextField(text, { text = it; error = null }, Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    placeholder = { Text("{\"mcpServers\": {\"服务名称\": {\"url\": \"https://…/mcp\"}}}") }, label = { Text("JSON 配置") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = { TextButton(onClick = {
            try { parseMcpConfig(text); actions.importServers(text); importConfig = false }
            catch (failure: Exception) { error = failure.message }
        }, enabled = text.isNotBlank()) { Text("导入") } }, dismissButton = { TextButton(onClick = { importConfig = false }) { Text("取消") } })
    }
    viewSkill?.let { skill ->
        AlertDialog(onDismissRequest = { viewSkill = null }, title = { Text(skill.name) }, text = {
            SelectionContainer { Text(skill.content, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
        }, confirmButton = { TextButton(onClick = { viewSkill = null }) { Text("关闭") } })
    }
    if (newSkill || editSkill != null) SkillEditor(editSkill, { newSkill = false; editSkill = null },
        { id, name, description, content -> actions.saveSkill(id, name, description, content); newSkill = false; editSkill = null },
        { actions.removeSkill(it); editSkill = null })
}

@Composable
private fun ExtensionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpServerEditor(config: McpServerConfig?, onDismiss: () -> Unit, onSave: (McpServerConfig) -> Unit, onDelete: (String) -> Unit) {
    var name by remember { mutableStateOf(config?.name.orEmpty()) }
    var transport by remember { mutableStateOf(config?.transport ?: "http") }
    var url by remember { mutableStateOf(config?.url.orEmpty()) }
    var command by remember { mutableStateOf(config?.command.orEmpty()) }
    var args by remember { mutableStateOf(JSONArray(config?.args.orEmpty()).toString()) }
    var headers by remember { mutableStateOf(JSONObject(config?.headers.orEmpty()).toString(2)) }
    var env by remember { mutableStateOf(JSONObject(config?.env.orEmpty()).toString(2)) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteConfirm by remember { mutableStateOf(false) }
    if (deleteConfirm) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text("删除 MCP 服务？") },
        text = { Text(name) }, confirmButton = { TextButton(onClick = { config?.let { onDelete(it.id) }; onDismiss() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("取消") } })
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (config == null) "添加 MCP 服务" else "编辑 MCP 服务", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("服务名称") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(transport == "http", { transport = "http" }, label = { Text("HTTP") })
                FilterChip(transport == "stdio", { transport = "stdio" }, label = { Text("本机 stdio") })
            }
            if (transport == "http") {
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("MCP 地址") }, placeholder = { Text("https://example.com/mcp") }, singleLine = true)
                OutlinedTextField(headers, { headers = it }, Modifier.fillMaxWidth(), label = { Text("请求头 JSON") }, maxLines = 4,
                    visualTransformation = PasswordVisualTransformation())
                Text("例如 {\"Authorization\":\"Bearer …\"}，加密保存在本机。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OutlinedTextField(command, { command = it }, Modifier.fillMaxWidth(), label = { Text("命令") }, placeholder = { Text("npx 或 python") }, singleLine = true)
                OutlinedTextField(args, { args = it }, Modifier.fillMaxWidth(), label = { Text("参数 JSON 数组") }, placeholder = { Text("[\"-y\", \"服务包名\"]") }, maxLines = 4)
                OutlinedTextField(env, { env = it }, Modifier.fillMaxWidth(), label = { Text("环境变量 JSON") }, maxLines = 4,
                    visualTransformation = PasswordVisualTransformation())
                Text("使用内置 Termux 环境运行，所需服务包请先安装。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = {
                try {
                    val updated = McpServerConfig(config?.id ?: UUID.randomUUID().toString(), name.trim(), transport, url.trim(), command.trim(),
                        JSONArray(args).stringList(), config?.enabled ?: true, JSONObject(headers).stringMap(), JSONObject(env).stringMap())
                    validateMcpServer(updated); onSave(updated)
                } catch (failure: Exception) { error = failure.message ?: "配置格式无效" }
            }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
            if (config != null) TextButton(onClick = { deleteConfirm = true }, Modifier.fillMaxWidth()) { Text("删除服务", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillEditor(skill: SkillInfo?, onDismiss: () -> Unit, onSave: (String?, String, String, String) -> Unit, onDelete: (String) -> Unit) {
    var name by remember { mutableStateOf(skill?.name.orEmpty()) }
    var description by remember { mutableStateOf(skill?.description.orEmpty()) }
    var content by remember { mutableStateOf(skill?.content.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteConfirm by remember { mutableStateOf(false) }
    if (deleteConfirm) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text("删除技能？") }, text = { Text(name) },
        confirmButton = { TextButton(onClick = { skill?.let { onDelete(it.id) }; onDismiss() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("取消") } })
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (skill == null) "创建技能" else if (skill.source.isNotBlank()) "管理仓库技能" else "编辑技能", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名称") }, placeholder = { Text("my-workflow") }, singleLine = true, readOnly = skill?.source?.isNotBlank() == true)
            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("适用场景") }, maxLines = 3, readOnly = skill?.source?.isNotBlank() == true)
            OutlinedTextField(content, { content = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 300.dp), label = { Text("技能内容 · Markdown") }, maxLines = 12, readOnly = skill?.source?.isNotBlank() == true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (skill?.source.isNullOrBlank()) Button(onClick = {
                if (!Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}").matches(name)) error = "名称使用 1–64 位字母、数字、短横线或下划线"
                else if (description.isBlank() || content.isBlank()) error = "请填写适用场景和技能内容"
                else onSave(skill?.id, name, description, content)
            }, Modifier.fillMaxWidth()) { Text("保存") }
            if (skill != null) TextButton(onClick = { deleteConfirm = true }, Modifier.fillMaxWidth()) { Text("删除技能", color = MaterialTheme.colorScheme.error) }
        }
    }
}
