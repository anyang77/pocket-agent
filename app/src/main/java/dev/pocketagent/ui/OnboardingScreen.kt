package dev.pocketagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.pocketagent.agent.AgentPermissions
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
    val scrollState = rememberScrollState()

    LaunchedEffect(step) {
        scrollState.scrollTo(0)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            SetupHeader(step)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 22.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
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

            SetupActionBar(
                step = step,
                canContinue = when (step) {
                    0 -> (state.storageAccessGranted || state.capabilities.rootGranted) &&
                        state.notificationPermissionGranted
                    1 -> state.capabilities.internalTermuxReady
                    else -> true
                },
                runtimeInstalling = state.internalRuntimeInstalling,
                onBack = { if (step > 0) step-- },
                onContinue = {
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
                onSkipModel = onFinish,
            )
        }
    }
}

@Composable
private fun SetupHeader(currentStep: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(11.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Code,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("Pocket Agent", style = MaterialTheme.typography.titleMedium)
            Text(
                "应用初始化",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "第 ${currentStep + 1} 步，共 3 步",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SetupStepRail(currentStep)
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun SetupStepRail(currentStep: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val completed = index < currentStep
            val current = index == currentStep
            val color = when {
                completed || current -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
            }
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        if (completed || current) color else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape,
                    )
                    .border(1.dp, color, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (completed) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (current) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            if (index < 2) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(
                            if (index < currentStep) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun SetupActionBar(
    step: Int,
    canContinue: Boolean,
    runtimeInstalling: Boolean,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onSkipModel: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (step > 0) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("上一步")
                    }
                }
                Button(
                    onClick = onContinue,
                    modifier = Modifier.weight(1f).height(50.dp),
                    enabled = canContinue,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(
                        when {
                            step == 0 -> "继续设置"
                            step == 1 && runtimeInstalling -> "环境安装中…"
                            step == 1 -> "继续设置"
                            else -> "保存并开始"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(7.dp))
                    Icon(Icons.Outlined.ArrowForward, contentDescription = null, Modifier.size(18.dp))
                }
            }
            if (step == 2) {
                TextButton(
                    onClick = onSkipModel,
                    modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 8.dp),
                ) {
                    Text("暂时跳过，稍后在设置中配置模型")
                }
            }
        }
    }
}

@Composable
private fun StepIntroduction(
    stepLabel: String,
    title: String,
    description: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stepLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionSetup(
    state: AppUiState,
    onChange: (AgentPermissions) -> Unit,
    onRequestStorageAccess: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
) {
    val fileAccessGranted = state.storageAccessGranted || state.capabilities.rootGranted
    val completed = listOf(fileAccessGranted, state.notificationPermissionGranted).count { it }

    StepIntroduction(
        stepLabel = "第一步 · 权限",
        title = "权限设置",
        description = "文件与通知权限只会在你点击对应按钮后申请，应用不会在启动时自动跳转系统设置。",
    )

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(42.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.AdminPanelSettings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("必要权限", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (completed == 2) "已完成，可以继续" else "完成这两项后继续初始化",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "$completed / 2",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    PermissionCard(
        icon = Icons.Outlined.FolderOpen,
        iconTint = MaterialTheme.colorScheme.secondary,
        title = "文件与项目访问",
        detail = if (fileAccessGranted) {
            if (state.storageAccessGranted) {
                "已允许读取和写入共享存储中的项目。"
            } else {
                "已通过 Root 获得项目文件访问能力。"
            }
        } else {
            "用于编辑代码、保存文件、运行脚本和执行构建。Android 11 及以上会按系统要求显示“所有文件访问”页面。"
        },
        granted = fileAccessGranted,
        buttonLabel = "去授权",
        onRequest = onRequestStorageAccess,
    )

    PermissionCard(
        icon = Icons.Outlined.NotificationsActive,
        iconTint = MaterialTheme.colorScheme.primary,
        title = "任务通知",
        detail = if (state.notificationPermissionGranted) {
            "已允许显示 Agent、构建和长任务的运行进度。"
        } else {
            "用于在 Agent、编译和测试于后台运行时显示进度。点击“允许通知”会直接请求系统通知权限弹窗。"
        },
        granted = state.notificationPermissionGranted,
        buttonLabel = "允许通知",
        onRequest = onRequestNotificationAccess,
    )

    RootAccessCard(state)

    InfoNote(
        icon = Icons.Outlined.Security,
        title = "执行权限默认受控",
        detail = "Agent 默认使用审计模式：写入、执行命令和联网操作逐项确认。进入 Agent 页面后可调整权限，完整权限需要另行确认风险。",
    )
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    detail: String,
    granted: Boolean,
    buttonLabel: String,
    onRequest: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.34f), RoundedCornerShape(18.dp)),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(44.dp).background(iconTint.copy(alpha = 0.13f), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(23.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PermissionStatus(granted)
                Spacer(Modifier.weight(1f))
                if (!granted) {
                    Button(
                        onClick = onRequest,
                        shape = RoundedCornerShape(11.dp),
                        contentPadding = ButtonDefaults.ContentPadding,
                    ) {
                        Text(buttonLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionStatus(granted: Boolean) {
    val color = if (granted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            if (granted) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = color,
        )
        Text(
            if (granted) "已授权" else "等待授权",
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun RootAccessCard(state: AppUiState) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f), RoundedCornerShape(18.dp)),
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.06f),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(44.dp).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.13f), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.DeveloperMode,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(23.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Root 自动检测", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "启动时会检测 Root。已授权后，系统级操作自动启用；普通开发命令仍在 Termux 环境中以普通 UID 运行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        state.capabilities.rootGranted -> "Root 已授权"
                        state.rootProbing -> "正在检测 Root 授权"
                        else -> "当前未检测到 Root，可使用普通 App 权限"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.capabilities.rootGranted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun EnvironmentSetup(
    state: AppUiState,
    onInstallRuntime: () -> Unit,
) {
    val ready = state.capabilities.internalTermuxReady
    StepIntroduction(
        stepLabel = "第二步 · 开发环境",
        title = "开发环境",
        description = "首次使用会初始化内置工具链。环境就绪后，可以在手机上运行项目命令、测试与构建。",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (ready) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)
                },
                RoundedCornerShape(20.dp),
            ),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .background(
                            if (ready) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            } else {
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
                            },
                            RoundedCornerShape(14.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (ready) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(27.dp),
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(27.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        if (ready) "开发环境已就绪" else if (state.internalRuntimeInstalling) {
                            "正在初始化环境"
                        } else {
                            "等待初始化环境"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (ready) "可以继续配置模型服务" else "请保持应用在前台，初始化可能需要一些时间",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!ready) {
                RuntimeInstallProgressView(
                    message = state.internalRuntimeInstallMessage,
                    progress = state.internalRuntimeInstallProgress,
                    installing = state.internalRuntimeInstalling,
                )
            } else {
                LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }

    Text("内置工具", style = MaterialTheme.typography.titleSmall)
    ToolGroup(
        icon = Icons.Outlined.Code,
        title = "语言与运行环境",
        tools = listOf("Python", "Node.js", "Java", "C / C++"),
    )
    ToolGroup(
        icon = Icons.Outlined.Terminal,
        title = "开发与远程工具",
        tools = listOf("Git", "SSH", "Terminal"),
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.Memory,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Text(
                if (state.capabilities.rootGranted) {
                    "Root 已授权，环境可根据任务使用扩展系统能力。"
                } else {
                    "当前使用普通 App 权限；Root 不是使用内置环境的必要条件。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (!ready && !state.internalRuntimeInstalling) {
        OutlinedButton(
            onClick = onInstallRuntime,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("重试环境初始化")
        }
    }
}

@Composable
private fun ToolGroup(icon: ImageVector, title: String, tools: List<String>) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(16.dp)),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    tools.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    StepIntroduction(
        stepLabel = "第三步 · 模型服务",
        title = "模型服务",
        description = "填写兼容 OpenAI 接口的服务地址和 API Key。也可以暂时跳过，稍后在设置中配置。",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), RoundedCornerShape(18.dp)),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("模型服务", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Base URL") },
                placeholder = { Text("https://api.deepseek.com/v1") },
                leadingIcon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("上下文窗口", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(
                formatContextWindow(contextWindow),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CONTEXT_WINDOW_OPTIONS) { option ->
                AssistChip(
                    onClick = { onContextWindowChange(option) },
                    label = { Text(formatContextWindow(option)) },
                    leadingIcon = if (contextWindow == option) {
                        {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
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

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (modelsLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    Icons.Outlined.Code,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (modelsError == null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when {
                        modelsLoading -> "正在获取模型列表"
                        modelsError != null -> "模型列表暂不可用"
                        else -> "默认模型"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when {
                        modelsError != null -> modelsError
                        else -> model
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (modelsError != null) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }

    InfoNote(
        icon = Icons.Outlined.Security,
        title = "发送范围",
        detail = "使用时，对话内容、Agent 读取的代码和工具输出可能发送到你填写的模型服务。请只处理你有权访问的数据，并避免发送密钥、密码或敏感资料。",
    )
}

@Composable
private fun InfoNote(icon: ImageVector, title: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
