package dev.pocketagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import dev.pocketagent.data.AiSettings
import dev.pocketagent.ui.theme.ThemePreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: AppUiState,
    onSaveSettings: suspend (AiSettings) -> Boolean,
    onProfileSelected: (String) -> Unit,
    onCreateProfile: (String, String, String, Int) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onThemePreferenceChanged: (ThemePreference) -> Unit,
    extensionActions: ExtensionActions,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var baseUrl by rememberSaveable(state.activeProfileId, state.settings.baseUrl) {
        mutableStateOf(state.settings.baseUrl)
    }
    var apiKey by rememberSaveable(state.activeProfileId, state.settings.apiKey) {
        mutableStateOf(state.settings.apiKey)
    }
    var contextWindow by rememberSaveable(state.activeProfileId, state.settings.contextWindow) {
        mutableStateOf(state.settings.contextWindow)
    }
    var showEditProfile by rememberSaveable { mutableStateOf(false) }
    var showNewProfile by rememberSaveable { mutableStateOf(false) }
    var deleteProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    if (showNewProfile) {
        NewProfileDialog(
            contextWindow = contextWindow,
            onDismiss = { showNewProfile = false },
            onCreate = { name, base, key, context ->
                showNewProfile = false
                onCreateProfile(name, base, key, context)
            },
        )
    }
    deleteProfileId?.let { profileId ->
        val profile = state.modelProfiles.firstOrNull { it.id == profileId }
        AlertDialog(
            onDismissRequest = { deleteProfileId = null },
            title = { Text("删除模型配置？") },
            text = {
                Text(
                    "将永久删除“${profile?.name ?: profileId}”及其保存的 API Key。" +
                        "历史对话不会被删除。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteProfileId = null
                        showEditProfile = false
                        onDeleteProfile(profileId)
                    },
                ) {
                    Text(
                        "确认删除",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteProfileId = null }) {
                    Text("取消")
                }
            },
        )
    }

    if (showEditProfile) {
        ModalBottomSheet(
            onDismissRequest = { showEditProfile = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("编辑模型配置", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.modelProfiles.firstOrNull { it.id == state.activeProfileId }?.name.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { showEditProfile = false }) {
                        Icon(Icons.Outlined.Close, "关闭配置")
                    }
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("服务地址") },
                    placeholder = { Text("https://api.deepseek.com/v1") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API 密钥") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("上下文窗口", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(CONTEXT_WINDOW_OPTIONS) { option ->
                            FilterChip(
                                selected = contextWindow == option,
                                onClick = { contextWindow = option },
                                label = { Text(formatContextWindow(option)) },
                            )
                        }
                    }
                    Text(
                        "${state.settings.model} · 思考 ${reasoningEffortLabel(state.settings.reasoningEffort)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { scope.launch {
                        val saved = onSaveSettings(
                            AiSettings(
                                baseUrl = baseUrl.trim(),
                                model = state.settings.model,
                                apiKey = apiKey.trim(),
                                contextWindow = contextWindow,
                                workspacePath = state.workspacePath,
                                reasoningEffort = state.settings.reasoningEffort,
                            ),
                        )
                        if (saved) showEditProfile = false
                    } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    enabled = !state.modelSettingsSaving,
                ) { Text(if (state.modelSettingsSaving) "正在保存…" else "保存并刷新模型") }
                state.modelSettingsError?.let { error ->
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (state.agentRunning) {
                    Text("保存后下次任务生效，当前任务继续使用原配置。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.modelProfiles.size > 1) {
                    TextButton(
                        onClick = { deleteProfileId = state.activeProfileId },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("删除此配置", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("外观", style = MaterialTheme.typography.titleSmall)
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThemeChoice(
                            modifier = Modifier.weight(1f), label = "跟随系统",
                            selected = state.themePreference == ThemePreference.SYSTEM,
                            onClick = { onThemePreferenceChanged(ThemePreference.SYSTEM) },
                        )
                        ThemeChoice(
                            modifier = Modifier.weight(1f), label = "浅色",
                            selected = state.themePreference == ThemePreference.LIGHT,
                            onClick = { onThemePreferenceChanged(ThemePreference.LIGHT) },
                        )
                        ThemeChoice(
                            modifier = Modifier.weight(1f), label = "深色",
                            selected = state.themePreference == ThemePreference.DARK,
                            onClick = { onThemePreferenceChanged(ThemePreference.DARK) },
                        )
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("模型配置", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { showNewProfile = true }) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                        Text("新建")
                    }
                }
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column {
                        state.modelProfiles.forEachIndexed { index, profile ->
                            val selected = profile.id == state.activeProfileId
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { onProfileSelected(profile.id) }
                                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Check, if (selected) "当前配置" else null,
                                    Modifier.size(18.dp),
                                    tint = if (selected) MaterialTheme.colorScheme.primary
                                        else androidx.compose.ui.graphics.Color.Transparent,
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(profile.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        profile.model.ifBlank { "尚未选择模型" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (selected) {
                                    IconButton(onClick = {
                                        baseUrl = state.settings.baseUrl
                                        apiKey = state.settings.apiKey
                                        contextWindow = state.settings.contextWindow
                                        showEditProfile = true
                                    }) { Icon(Icons.Outlined.Edit, "编辑当前配置", Modifier.size(20.dp)) }
                                }
                            }
                            if (index < state.modelProfiles.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 46.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        }
                    }
                }
                Text(
                    "点选切换配置，点击编辑修改服务地址与密钥。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.modelsError?.let { error ->
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item { ExtensionsSettings(state, extensionActions) }
        item {
            Text(
                "CodeMate · 0.1.0",
                modifier = Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThemeChoice(
    modifier: Modifier,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 36.dp),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
            else androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                .semantics { this.selected = selected },
            contentAlignment = Alignment.Center,
        ) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
    }
}

@Composable
private fun NewProfileDialog(
    contextWindow: Int,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, Int) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("https://api.deepseek.com/v1") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建模型配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("配置名称") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, baseUrl, apiKey, contextWindow) },
                enabled = name.isNotBlank() && baseUrl.isNotBlank() && apiKey.isNotBlank(),
            ) { Text("创建并切换") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private val CONTEXT_WINDOW_OPTIONS = listOf(
    128_000,
    200_000,
    256_000,
    512_000,
    1_000_000,
)

private fun formatContextWindow(tokens: Int): String =
    if (tokens >= 1_000_000) "1M" else "${tokens / 1_000}K"
