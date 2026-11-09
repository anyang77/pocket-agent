package dev.pocketagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
internal fun StudioModelPicker(
    catalog: StudioModelCatalogState,
    selected: String,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val choices = remember(catalog.models, query, showAll) { studioModelChoices(catalog.models, query, showAll) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().heightIn(max = 580.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选择图像模型", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索模型") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("查看全部模型", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = showAll, onCheckedChange = { showAll = it })
                }
                Text("默认按名称筛选生图候选，不代表当前密钥具有调用权限。自定义名称可查看全部或手动输入。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (catalog.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                catalog.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    if (choices.isEmpty() && !catalog.loading) item {
                        Text(if (query.isNotBlank()) "没有匹配的模型，试试其他关键词或查看全部。" else "未识别到生图模型。可查看全部模型，或返回手动输入 ID。",
                            Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    items(choices, key = { it }) { model ->
                        TextButton(enabled = enabled, onClick = { onSelect(model) }, modifier = Modifier.fillMaxWidth()) {
                            Text(model, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                            if (model == selected) Icon(Icons.Outlined.Check, "已选择", Modifier.size(20.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = enabled && !catalog.loading, onClick = onRefresh) { Text("刷新列表") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}
