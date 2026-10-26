package dev.pocketagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.pocketagent.data.AiSettings

@Composable
fun SettingsScreen(
    state: AppUiState,
    onSaveSettings: (AiSettings) -> Unit,
    onProfileSelected: (String) -> Unit,
    onCreateProfile: (String, String, String, Int) -> Unit,
    onDeleteProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var baseUrl by rememberSaveable(state.settings.baseUrl) {
        mutableStateOf(state.settings.baseUrl)
    }
    var apiKey by rememberSaveable(state.settings.apiKey) {
        mutableStateOf(state.settings.apiKey)
    }
    var contextWindow by rememberSaveable(state.settings.contextWindow) {
        mutableStateOf(state.settings.contextWindow)
    }
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text("模型配置", style = MaterialTheme.typography.titleLarge)
                Text(
                    "每套配置独立保存服务商、密钥、模型和上下文。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Profiles", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showNewProfile = true }) { Text("新建") }
                    TextButton(
                        onClick = { deleteProfileId = state.activeProfileId },
                        enabled = state.modelProfiles.size > 1,
                    ) { Text("删除当前") }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.modelProfiles, key = { it.id }) { profile ->
                        AssistChip(
                            onClick = { onProfileSelected(profile.id) },
                            label = {
                                Text(
                                    profile.name,
                                    color = if (profile.id == state.activeProfileId) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Base URL") },
                placeholder = { Text("https://api.deepseek.com/v1") },
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("上下文窗口", style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CONTEXT_WINDOW_OPTIONS) { option ->
                        AssistChip(
                            onClick = { contextWindow = option },
                            label = {
                                Text(
                                    formatContextWindow(option),
                                    color = if (contextWindow == option) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            },
                        )
                    }
                }
                Text(
                    "当前模型：${state.settings.model} · " +
                        "思考 ${reasoningEffortLabel(state.settings.reasoningEffort)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.modelsError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        item {
            Button(
                onClick = {
                    onSaveSettings(
                        AiSettings(
                            baseUrl = baseUrl.trim(),
                            model = state.settings.model,
                            apiKey = apiKey.trim(),
                            contextWindow = contextWindow,
                            workspacePath = state.workspacePath,
                            reasoningEffort = state.settings.reasoningEffort,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.agentRunning,
            ) {
                Text("保存并刷新模型")
            }
        }
        item {
            Text(
                "Pocket Agent 0.1.0",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
