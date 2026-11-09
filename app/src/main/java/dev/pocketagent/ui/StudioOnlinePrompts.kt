package dev.pocketagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StudioOnlinePrompts(
    enabled: Boolean,
    allowNetwork: Boolean,
    hasDraft: Boolean,
    repository: StudioPromptRepository,
    onApply: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sourceId by rememberSaveable { mutableStateOf(studioPromptSources.first().id) }
    val source = studioPromptSources.first { it.id == sourceId }
    var entries by remember { mutableStateOf(emptyList<StudioCommunityPrompt>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var refreshSource by remember { mutableStateOf<String?>(null) }
    fun requestRefresh() { refreshSource = sourceId; refresh++ }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable(sourceId) { mutableStateOf<String?>(null) }
    val selected = entries.firstOrNull { it.id == selectedId }
    var draft by rememberSaveable(selectedId, sourceId) { mutableStateOf(selected?.prompt.orEmpty()) }
    var replace by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun open(url: String) {
        try { uri.openUri(url) } catch (_: Exception) {
            scope.launch { snackbar.showSnackbar("无法打开链接，请检查是否安装了浏览器。") }
        }
    }
    LaunchedEffect(sourceId, refresh, allowNetwork) {
        entries = emptyList()
        error = null
        if (!allowNetwork) { loading = false; return@LaunchedEffect }
        loading = true
        val forceRefresh = refreshSource == sourceId
        refreshSource = null
        try {
            entries = repository.load(source, forceRefresh)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            error = "目录暂时无法读取，可能是网络、GitHub 限流或来源格式变化。可重试或打开仓库查看。"
        } finally { loading = false }
    }
    if (replace) AlertDialog(
        onDismissRequest = { replace = false },
        title = { Text("替换当前草稿？") },
        text = { Text("仅替换提示词，保留当前模型、画幅和张数，不会发起生成。") },
        confirmButton = { TextButton(enabled = enabled && canApplyStudioPrompt(draft), onClick = { replace = false; onApply(draft.trim()) }) { Text("替换草稿") } },
        dismissButton = { TextButton(onClick = { replace = false }) { Text("保留草稿") } },
    )
    Column(modifier.fillMaxWidth()) {
        LazyColumn(
            Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selected == null) {
                item {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        studioPromptSources.forEach { item ->
                            FilterChip(selected = sourceId == item.id, onClick = { sourceId = item.id; selectedId = null; query = "" }, label = { Text(item.title) })
                        }
                    }
                }
                item {
                    Text("由 YouMind OpenLab 汇编 · CC BY 4.0", style = MaterialTheme.typography.labelMedium)
                    Text("社区内容，来源声明仅供教育用途；使用权须另行核对。模型分类不保证接口兼容，部分提示词需要参考图。版面内不加载第三方效果图。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { open(source.documentUrl) }) { Text("来源仓库") }
                        TextButton(onClick = { open(source.licenseUrl) }) { Text("许可与署名") }
                        TextButton(enabled = allowNetwork && !loading, onClick = { requestRefresh() }) { Text("刷新") }
                    }
                }
                if (!allowNetwork) item {
                    Text("网络访问未开启。请在应用设置中允许网络访问后读取在线目录；本地模板不受影响。", color = MaterialTheme.colorScheme.error)
                }
                if (loading) item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在读取公开提示词目录…", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { message -> item {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { requestRefresh() }, enabled = allowNetwork && !loading) { Text("重新读取") }
                } }
                if (entries.isNotEmpty()) {
                    item { OutlinedTextField(value = query, onValueChange = { query = it.take(200) },
                        label = { Text("搜索在线提示词") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    val choices = entries.filter { it.matches(query) }
                    item { Text("${choices.size} 条 · 当前目录最多展示 500 条", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (choices.isEmpty()) item { Text("没有匹配的提示词，试试其他关键词。") }
                    items(choices, key = { it.id }) { prompt ->
                        StudioTemplateListRow(
                            title = prompt.title,
                            description = prompt.description,
                            supportingText = prompt.author,
                            onClick = { selectedId = prompt.id; draft = prompt.prompt },
                        )
                        HorizontalDivider()
                    }
                }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectedId = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回在线目录") }
                        Text(selected.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    Text("${selected.author.ifBlank { "原作者未标注" }} · YouMind OpenLab 汇编 · CC BY 4.0",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    selected.originalUrl?.let { url -> TextButton(onClick = { open(url) }) { Text("查看原始发布") } }
                }
                if (selected.description.isNotBlank()) item { Text(selected.description, style = MaterialTheme.typography.bodyMedium) }
                item {
                    OutlinedTextField(value = draft, onValueChange = { draft = it.take(20_000) }, label = { Text("提示词 · 使用前可编辑") },
                        modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 12,
                        isError = draft.length > 16_000,
                        supportingText = {
                            Text(if (draft.length > 16_000) "${draft.length}/16000 字符，请精简后填入草稿；原文未截断。"
                                else "${draft.length}/16000 字符")
                        })
                }
                item {
                    Text("当前仅支持独立文生图。需要上传参考图、保持原图人物或连续编辑的描述，不能直接照用。来源中的示例比例与参数不会自动应用。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = enabled && canApplyStudioPrompt(draft), onClick = { if (hasDraft) replace = true else onApply(draft.trim()) },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("填入草稿") }
                    Text("不会立即生成或产生生图费用；请先核对内容及使用权。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        SnackbarHost(snackbar)
    }
}
