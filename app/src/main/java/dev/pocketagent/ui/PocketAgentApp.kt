package dev.pocketagent.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketagent.runtime.AgentForegroundService
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketAgentApp(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val storageActivityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refreshSystemPermissions() }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshSystemPermissions() }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refreshSystemPermissions()
        if (!granted) openNotificationSettings(context)
    }

    val launchStorageSettings: (Intent) -> Boolean = { intent ->
        runCatching { storageActivityLauncher.launch(intent) }.isSuccess
    }
    val openStorageSettings: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appPermissionIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            val allPermissionIntent =
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            val appDetailsIntent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
            val target = listOf(
                appPermissionIntent,
                allPermissionIntent,
                appDetailsIntent,
            ).firstOrNull { intent ->
                context.packageManager.resolveActivity(intent, 0) != null
            }
            val opened = target?.let(launchStorageSettings) == true
            if (!opened) {
                Toast.makeText(
                    context,
                    "系统没有可用的文件权限页面，请在应用详情中开启存储权限",
                    Toast.LENGTH_LONG,
                ).show()
            }
        } else {
            launchStorageSettings(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
        Unit
    }
    val requestStorageAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            openStorageSettings()
        } else {
            storagePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
            )
        }
        Unit
    }
    val requestNotificationAccess: () -> Unit = {
        AgentForegroundService.ensureNotificationChannel(context)
        val targetSdk = context.applicationInfo.targetSdkVersion
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            targetSdk >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings(context)
        }
        Unit
    }
    var storagePromptOpened by rememberSaveable { mutableStateOf(false) }
    var notificationPromptOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(
        state.rootProbing,
        state.storageAccessGranted,
        state.capabilities.rootGranted,
        state.notificationPermissionGranted,
    ) {
        if (state.rootProbing) return@LaunchedEffect
        // Let the asynchronous root probe finish before showing an unnecessary prompt.
        delay(300)
        val fileAccessGranted =
            state.storageAccessGranted || state.capabilities.rootGranted
        if (!fileAccessGranted && !storagePromptOpened) {
            storagePromptOpened = true
            requestStorageAccess()
        } else if (fileAccessGranted && !state.notificationPermissionGranted &&
            !notificationPromptOpened
        ) {
            notificationPromptOpened = true
            requestNotificationAccess()
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSystemPermissions()
                if (viewModel.state.value.tab == AppTab.GIT) {
                    viewModel.refreshGit()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.notice) {
        state.notice?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeNotice()
        }
    }

    LaunchedEffect(state.agentRunning) {
        val intent = Intent(context, AgentForegroundService::class.java)
        if (state.agentRunning) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        } else {
            context.stopService(intent)
        }
    }

    if (!state.onboardingCompleted) {
        OnboardingScreen(
            state = state,
            onSaveSettings = viewModel::saveSettings,
            onPermissionsChanged = viewModel::savePermissions,
            onInstallRuntime = viewModel::installInternalRuntime,
            onRequestStorageAccess = requestStorageAccess,
            onRequestNotificationAccess = requestNotificationAccess,
            onFinish = viewModel::completeOnboarding,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    if (
        (!state.storageAccessGranted && !state.capabilities.rootGranted) ||
        !state.notificationPermissionGranted
    ) {
        FileAccessPermissionGate(
            fileAccessGranted = state.storageAccessGranted ||
                state.capabilities.rootGranted,
            notificationGranted = state.notificationPermissionGranted,
            onRequestAccess = requestStorageAccess,
            onOpenStorageSettings = openStorageSettings,
            onRequestNotifications = requestNotificationAccess,
            onRefresh = viewModel::refreshSystemPermissions,
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    Column {
                        Text("Pocket Agent", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = state.capabilities.runtimeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = state.tab == AppTab.AGENT,
                    onClick = { viewModel.selectTab(AppTab.AGENT) },
                    icon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null) },
                    label = { Text("Agent") },
                )
                NavigationBarItem(
                    selected = state.tab == AppTab.FILES,
                    onClick = { viewModel.selectTab(AppTab.FILES) },
                    icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                    label = { Text("文件") },
                )
                NavigationBarItem(
                    selected = state.tab == AppTab.GIT,
                    onClick = { viewModel.selectTab(AppTab.GIT) },
                    icon = { Icon(Icons.Outlined.AccountTree, contentDescription = null) },
                    label = { Text("Git") },
                )
                NavigationBarItem(
                    selected = state.tab == AppTab.TERMINAL,
                    onClick = { viewModel.selectTab(AppTab.TERMINAL) },
                    icon = { Icon(Icons.Outlined.Code, contentDescription = null) },
                    label = { Text("终端") },
                )
                NavigationBarItem(
                    selected = state.tab == AppTab.SETTINGS,
                    onClick = { viewModel.selectTab(AppTab.SETTINGS) },
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    label = { Text("设置") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (state.tab) {
            AppTab.AGENT -> AgentScreen(
                state = state,
                onSend = viewModel::sendChat,
                onStopAgent = viewModel::stopAgent,
                onNewConversation = viewModel::newConversation,
                onModelSelected = viewModel::selectModel,
                onProfileSelected = viewModel::selectModelProfile,
                onReasoningEffortSelected = viewModel::selectReasoningEffort,
                onRefreshModels = viewModel::refreshModels,
                onSelectConversation = viewModel::selectConversation,
                onDeleteConversation = viewModel::deleteConversation,
                onApprovePending = viewModel::approvePending,
                onDenyPending = viewModel::denyPending,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            AppTab.FILES -> FilesScreen(
                state = state,
                onRefresh = viewModel::refreshFiles,
                onOpenFile = viewModel::openFile,
                onOpenDirectory = viewModel::openDirectory,
                onGoParent = viewModel::goToParentDirectory,
                onCloseFile = viewModel::closeFile,
                onEditorChange = viewModel::updateEditor,
                onSave = viewModel::saveEditor,
                onRunScript = viewModel::runEditorFile,
                onCloseScriptOutput = viewModel::closeScriptOutput,
                onCreateEntry = viewModel::createWorkspaceEntry,
                onRenameEntry = viewModel::renameWorkspaceEntry,
                onDeleteEntry = viewModel::deleteWorkspaceEntry,
                onOpenWorkspacePicker = viewModel::openWorkspacePicker,
                onBrowseWorkspaceDirectory = viewModel::browseWorkspaceDirectory,
                onChooseWorkspaceDirectory = viewModel::chooseWorkspaceDirectory,
                onCloseWorkspacePicker = viewModel::closeWorkspacePicker,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            AppTab.GIT -> GitScreen(
                state = state,
                onRefresh = viewModel::refreshGit,
                onInit = viewModel::gitInit,
                onClone = viewModel::gitClone,
                onStage = viewModel::gitStage,
                onStageAll = viewModel::gitStageAll,
                onUnstage = viewModel::gitUnstage,
                onCommit = viewModel::gitCommit,
                onSwitchBranch = viewModel::gitSwitchBranch,
                onPull = viewModel::gitPull,
                onPush = viewModel::gitPush,
                onOpenDiff = viewModel::openGitDiff,
                onCloseDiff = viewModel::closeGitDiff,
                onOpenCommitDetail = viewModel::openGitCommitDetail,
                onCloseCommitDetail = viewModel::closeGitCommitDetail,
                onGitAccountSelected = viewModel::selectGitAccount,
                onGitAccountRemoved = viewModel::removeGitAccount,
                onGenerateSshAccount = viewModel::generateSshAccount,
                onRemoveSshAccount = viewModel::removeSshAccount,
                onSwitchRepository = viewModel::switchManagedRepository,
                onRemoveRepository = viewModel::removeManagedRepository,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            AppTab.TERMINAL -> TerminalScreen(
                state = state,
                terminalManager = viewModel.terminalManager,
                onInstallRuntime = viewModel::installInternalRuntime,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            AppTab.SETTINGS -> SettingsScreen(
                state = state,
                onSaveSettings = viewModel::saveSettings,
                onProfileSelected = viewModel::selectModelProfile,
                onCreateProfile = viewModel::createModelProfile,
                onDeleteProfile = viewModel::deleteModelProfile,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

@Composable
private fun FileAccessPermissionGate(
    fileAccessGranted: Boolean,
    notificationGranted: Boolean,
    onRequestAccess: () -> Unit,
    onOpenStorageSettings: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRefresh: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text("首次运行需要系统权限", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.padding(top = 8.dp))
            Text(
                "以下权限全部完成后才能继续。文件权限使用系统运行时授权弹窗，" +
                    "通知权限需要进入系统通知设置开启。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "首次会弹出系统读写授权。若系统未弹出，请前往 " +
                    "设置 > 应用 > Pocket Agent > 权限 > 所有文件访问",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.padding(top = 18.dp))
            Text(
                "所有文件访问：${if (fileAccessGranted) "已完成" else "未完成"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!fileAccessGranted) {
                Button(onClick = onRequestAccess) {
                    Text("打开所有文件访问")
                }
                OutlinedButton(onClick = onOpenStorageSettings) {
                    Text("旧版存储权限")
                }
            }
            Text(
                "通知权限：${if (notificationGranted) "已完成" else "未完成"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!notificationGranted) {
                Button(onClick = onRequestNotifications) {
                    Text("打开通知授权")
                }
            }
            OutlinedButton(onClick = onRefresh) {
                Text("重新检测权限")
            }
        }
    }
}

private fun openNotificationSettings(context: Context) {
    val packageUri = Uri.parse("package:${context.packageName}")
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
            )
        }
    }
}
