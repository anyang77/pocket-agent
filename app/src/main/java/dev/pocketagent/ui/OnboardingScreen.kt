package dev.pocketagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.pocketagent.agent.AgentPermissions
import dev.pocketagent.agent.PermissionMode
import dev.pocketagent.data.AiSettings

@Composable
fun OnboardingScreen(
    state: AppUiState,
    onSaveSettings: (AiSettings) -> Unit,
    onPermissionsChanged: (AgentPermissions) -> Unit,
    onInstallRuntime: () -> Unit,
    onRequestStorageAccess: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var baseUrl by rememberSaveable(state.settings.baseUrl) {
        mutableStateOf(state.settings.baseUrl)
    }
    var apiKey by rememberSaveable(state.settings.apiKey) {
        mutableStateOf(state.settings.apiKey)
    }
    var contextWindow by rememberSaveable(state.settings.contextWindow) {
        mutableStateOf(state.settings.contextWindow)
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(20.dp))
            Text("Pocket Agent", style = MaterialTheme.typography.headlineSmall)
            SetupStepIndicator(step)
            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (step) {
                    0 -> PermissionSetup(
                        state = state,
                        onChange = onPermissionsChanged,
                        onRequestStorageAccess = onRequestStorageAccess,
                        onRequestNotificationAccess = onRequestNotificationAccess,
                    )
                    1 -> EnvironmentSetup(state, onInstallRuntime)
                    else -> ModelSetup(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        onBaseUrlChange = { baseUrl = it },
                        onApiKeyChange = { apiKey = it },
                        model = state.settings.model,
                        modelsLoading = state.modelsLoading,
                        modelsError = state.modelsError,
                        contextWindow = contextWindow,
                        onContextWindowChange = { contextWindow = it },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    TextButton(onClick = { step-- }) { Text("上一步") }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        when (step) {
                            0 -> step = 1
                            1 -> step = 2
                            else -> {
                                if (baseUrl.isNotBlank() && apiKey.isNotBlank()) {
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
                                }
                                onFinish()
                            }
                        }
                    },
                    enabled = when (step) {
                        0 -> (state.storageAccessGranted || state.capabilities.rootGranted) &&
                            state.notificationPermissionGranted
                        1 -> state.capabilities.internalTermuxReady
                        else -> true
                    },
                ) {
                    Text(
                        when (step) {
                            0 -> "下一步"
                            1 -> if (state.capabilities.internalTermuxReady) {
                                "下一步"
                            } else if (state.internalRuntimeInstalling) {
                                "安装中…"
                            } else {
                                "等待环境"
                            }
                            else -> "保存并开始"
                        },
                    )
                }
                if (step == 2) {
                    TextButton(onClick = onFinish) { Text("稍后配置模型") }
                }
            }
        }
    }
}

@Composable
private fun SetupStepIndicator(currentStep: Int) {
    val labels = listOf("权限", "环境", "模型")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val completed = index < currentStep
            val current = index == currentStep
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = when {
                    current -> MaterialTheme.colorScheme.primaryContainer
                    completed -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.surface
                },
                contentColor = when {
                    current -> MaterialTheme.colorScheme.onPrimaryContainer
                    completed -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Text(
                    text = if (completed) "✓ $label" else "${index + 1} $label",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun PermissionSetup(
    state: AppUiState,
    onChange: (AgentPermissions) -> Unit,
    onRequestStorageAccess: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
) {
    val permissions = state.permissions
    val fileAccessGranted =
        state.storageAccessGranted || state.capabilities.rootGranted
    Text("1. 必须完成的系统权限", style = MaterialTheme.typography.titleLarge)
    Text(
        "必须完成以下 2 项，否则不能继续初始化。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "必需权限",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${listOf(fileAccessGranted, state.notificationPermissionGranted).count { it }}/2 已完成",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (fileAccessGranted && state.notificationPermissionGranted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
            }
            HorizontalDivider()
            OnboardingPermissionRow(
                title = "文件读写",
                detail = if (fileAccessGranted) {
                    if (state.storageAccessGranted) {
                        "已允许访问共享存储和项目目录"
                    } else {
                        "已通过 Root 获得文件访问能力"
                    }
                } else {
                    "编辑、运行和编译项目所必需"
                },
                granted = fileAccessGranted,
                onRequest = onRequestStorageAccess,
            )
            OnboardingPermissionRow(
                title = "通知权限",
                detail = if (state.notificationPermissionGranted) {
                    "已允许显示构建和长任务进度"
                } else {
                    "Agent、编译和测试在后台持续运行所必需"
                },
                granted = state.notificationPermissionGranted,
                onRequest = onRequestNotificationAccess,
            )
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            Text(
                if (state.capabilities.rootGranted) {
                    "Root：已授权，将自动使用"
                } else if (state.rootProbing) {
                    "Root：正在检测"
                } else {
                    "Root：未授权，将使用普通 App 权限"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text("Agent 执行范围", style = MaterialTheme.typography.titleMedium)
    Text(
        "这是 AI 写文件和执行命令的审计策略，不影响上面的系统权限。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OnboardingMode(
                "只读",
                "读取项目，不写文件、不执行命令",
                permissions.mode == PermissionMode.READ_ONLY,
            ) { onChange(permissions.withMode(PermissionMode.READ_ONLY)) }
            OnboardingMode(
                "逐项确认 · 推荐",
                "写文件、执行命令和联网操作逐次确认",
                permissions.mode == PermissionMode.ASK,
            ) { onChange(permissions.withMode(PermissionMode.ASK)) }
            OnboardingMode(
                "完全自动",
                "写入和完整 Android shell 自动执行，全部操作继续记入审计",
                permissions.mode == PermissionMode.AUTO,
            ) { onChange(permissions.withMode(PermissionMode.AUTO)) }
        }
    }
}

@Composable
private fun OnboardingPermissionRow(
    title: String,
    detail: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "$title · ${if (granted) "已授权" else "未授权"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!granted) {
            TextButton(onClick = onRequest) { Text("授权") }
        }
    }
}

@Composable
private fun ModelSetup(
    baseUrl: String,
    apiKey: String,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    model: String,
    modelsLoading: Boolean,
    modelsError: String?,
    contextWindow: Int,
    onContextWindowChange: (Int) -> Unit,
) {
    Text("3. 配置 AI 模型", style = MaterialTheme.typography.titleLarge)
    Text(
        "可以现在填写 API 地址和 Key，也可以直接跳过，稍后在设置中添加。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = baseUrl,
        onValueChange = onBaseUrlChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Base URL") },
        placeholder = { Text("https://api.deepseek.com/v1") },
        singleLine = true,
    )
    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("API Key") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("上下文窗口", style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CONTEXT_WINDOW_OPTIONS) { option ->
                AssistChip(
                    onClick = { onContextWindowChange(option) },
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
            "上下文用到 ${contextWindow / 1_000}K 的 72% 时会自动压缩并继续。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        "默认工作区：/sdcard。进入“文件”页即可切换目录。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (modelsLoading) {
            CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
        }
        Text(
            when {
                modelsLoading -> "正在获取模型"
                modelsError != null -> modelsError
                else -> "默认模型：$model"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (modelsError != null) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
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

@Composable
private fun EnvironmentSetup(
    state: AppUiState,
    onInstallRuntime: () -> Unit,
) {
    Text("2. 初始化内置运行环境", style = MaterialTheme.typography.titleLarge)
    Text(
        "一次性安装 Python、Node、C/C++、Git 和 SSH。工具链未就绪时不能继续。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (state.capabilities.internalTermuxReady) {
                    "内置环境已就绪"
                } else {
                    "正在初始化开发环境"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.capabilities.internalTermuxReady) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (!state.capabilities.internalTermuxReady) {
                RuntimeInstallProgressView(
                    message = state.internalRuntimeInstallMessage,
                    progress = state.internalRuntimeInstallProgress,
                    installing = state.internalRuntimeInstalling,
                )
            }
            Text(
                if (state.capabilities.rootGranted) {
                    "Root：已检测到并自动启用"
                } else {
                    "Root：未检测到，使用普通 App 权限"
                },
                style = MaterialTheme.typography.labelMedium,
            )
            if (!state.capabilities.internalTermuxReady && !state.internalRuntimeInstalling) {
                TextButton(onClick = onInstallRuntime) { Text("重试环境初始化") }
            }
        }
    }
}

@Composable
private fun OnboardingMode(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun AgentPermissions.withMode(mode: PermissionMode): AgentPermissions = when (mode) {
    PermissionMode.READ_ONLY -> copy(
        mode = mode,
        allowFileWrite = false,
        allowCommands = false,
        allowNetwork = false,
        allowSystemRead = true,
    )
    PermissionMode.ASK -> copy(
        mode = mode,
        allowFileWrite = true,
        allowCommands = true,
        allowNetwork = true,
        allowSystemRead = true,
    )
    PermissionMode.AUTO -> copy(
        mode = mode,
        allowFileWrite = true,
        allowCommands = true,
        allowNetwork = true,
        allowSystemRead = true,
    )
}
