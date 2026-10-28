package dev.pocketagent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketagent.runtime.AgentForegroundService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketAgentApp(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.welcomeAccepted || state.welcomeVisible) {
        WelcomeScreen(onContinue = viewModel::acceptWelcome)
        return
    }
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
    ) { viewModel.refreshSystemPermissions() }

    val launchFirstAvailable: (List<Intent>) -> Boolean = { intents ->
        var opened = false
        if (
            lifecycleOwner.lifecycle.currentState.isAtLeast(
                Lifecycle.State.RESUMED,
            )
        ) {
            for (intent in intents) {
                if (runCatching { storageActivityLauncher.launch(intent) }.isSuccess) {
                    opened = true
                    break
                }
            }
        }
        opened
    }
    val openStorageSettings: () -> Boolean = {
        val packageUri = Uri.parse("package:${context.packageName}")
        val opened = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            launchFirstAvailable(
                listOf(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        packageUri,
                    ),
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
                ),
            )
        } else {
            launchFirstAvailable(
                listOf(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
                ),
            )
        }
        if (!opened) {
            Toast.makeText(
                context,
                "无法打开系统权限页，请在“设置 > 应用 > CodeMate”中授权",
                Toast.LENGTH_LONG,
            ).show()
        }
        opened
    }
    val openNotificationSettings: () -> Unit = {
        val packageUri = Uri.parse("package:${context.packageName}")
        val opened = launchFirstAvailable(
            listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
            ),
        )
        if (!opened) {
            Toast.makeText(
                context,
                "无法打开通知设置，请手动进入系统“应用通知”页开启",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    val requestStorageAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            openStorageSettings()
        } else {
            val launched = runCatching {
                storagePermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ),
                )
            }.isSuccess
            if (!launched) openStorageSettings()
        }
        Unit
    }
    val requestNotificationAccess: () -> Unit = {
        AgentForegroundService.ensureNotificationChannel(context)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val launched = runCatching {
                notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }.isSuccess
            if (!launched) openNotificationSettings()
        } else {
            openNotificationSettings()
        }
        Unit
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSystemPermissions()
                viewModel.refreshRootAccess()
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
            onOpenStorageSettings = { openStorageSettings() },
            onRequestNotifications = requestNotificationAccess,
            onRequestRootAccess = { viewModel.probeRoot() },
            rootProbing = state.rootProbing,
            onRefresh = viewModel::refreshSystemPermissions,
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // Files and terminal own their toolbars; the editor uses the whole workspace.
            if (state.tab != AppTab.FILES && state.tab != AppTab.TERMINAL) {
                TopAppBar(
                    expandedHeight = 52.dp,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                    title = {
                        Text(
                            when (state.tab) {
                                AppTab.AGENT -> "Agent"
                                AppTab.GIT -> "Git"
                                AppTab.SETTINGS -> "设置"
                                else -> "CodeMate"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding().height(64.dp),
                windowInsets = WindowInsets(0, 0, 0, 0),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                NavigationBarItem(
                    selected = state.tab == AppTab.FILES,
                    onClick = { viewModel.selectTab(AppTab.FILES) },
                    icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                    label = { Text("文件") },
                )
                NavigationBarItem(
                    selected = state.tab == AppTab.AGENT,
                    onClick = { viewModel.selectTab(AppTab.AGENT) },
                    icon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null) },
                    label = { Text("Agent") },
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
                onInputDraftChange = viewModel::updateAgentInputDraft,
                onStopAgent = viewModel::stopAgent,
                onStopSubagent = viewModel::stopSubagent,
                onImages = viewModel::addAgentImages,
                onMentionQuery = viewModel::searchMentionFiles,
                onFileAttachment = viewModel::addAgentFile,
                onRemoveAttachment = viewModel::removeAgentAttachment,
                onRemoveQueued = viewModel::removeQueuedPrompt,
                onEditQueued = viewModel::editQueuedPrompt,
                onSteerQueued = viewModel::steerQueuedPrompt,
                onResumeQueue = viewModel::resumePromptQueue,
                onPauseQueue = viewModel::pausePromptQueue,
                onNewConversation = viewModel::newConversation,
                onModelSelected = viewModel::selectModel,
                onProfileSelected = viewModel::selectModelProfile,
                onReasoningEffortSelected = viewModel::selectReasoningEffort,
                onRefreshModels = viewModel::refreshModels,
                onSelectConversation = viewModel::selectConversation,
                onDeleteConversation = viewModel::deleteConversation,
                onApprovePending = viewModel::approvePending,
                onDenyPending = viewModel::denyPending,
                onSelectChoice = viewModel::selectPendingChoice,
                onDismissChoice = viewModel::dismissPendingChoice,
                onPauseGoal = viewModel::pauseGoal,
                onResumeGoal = viewModel::resumeGoal,
                onClearGoal = viewModel::clearGoal,
                onPermissionsChanged = viewModel::savePermissions,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
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
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            )
            AppTab.GIT -> GitScreen(
                state = state,
                onRefresh = viewModel::refreshGit,
                onInit = viewModel::gitInit,
                onClone = viewModel::gitClone,
                onStage = viewModel::gitStage,
                onStageAll = viewModel::gitStageAll,
                onUnstage = viewModel::gitUnstage,
                onDiscard = viewModel::gitDiscard,
                onDiscardAll = viewModel::gitDiscardAll,
                onCommit = viewModel::gitCommit,
                onSwitchBranch = viewModel::gitSwitchBranch,
                onPull = viewModel::gitPull,
                onPush = viewModel::gitPush,
                onOpenDiff = viewModel::openGitDiff,
                onCloseDiff = viewModel::closeGitDiff,
                onOpenCommitDetail = viewModel::openGitCommitDetail,
                onCloseCommitDetail = viewModel::closeGitCommitDetail,
                onGitAccountRemoved = viewModel::removeGitAccount,
                onGitHubLogin = viewModel::startGitHubLogin,
                onCancelGitHubLogin = viewModel::cancelGitHubLogin,
                onSaveGitHubClientId = viewModel::saveGitHubOAuthClientId,
                onRefreshGitHubRepositories = viewModel::refreshGitHubRepositories,
                onLoadCloneBranches = viewModel::loadGitCloneBranches,
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
                onRuntimeSelected = viewModel::setTerminalRuntime,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            )
            AppTab.SETTINGS -> SettingsScreen(
                state = state,
                onSaveSettings = viewModel::saveSettings,
                onProfileSelected = viewModel::selectModelProfile,
                onCreateProfile = viewModel::createModelProfile,
                onDeleteProfile = viewModel::deleteModelProfile,
                onThemePreferenceChanged = viewModel::setThemePreference,
                extensionActions = ExtensionActions(
                    viewModel::refreshExtensions, viewModel::saveExtensionSettings, viewModel::setSkillEnabled,
                    viewModel::saveSkill, viewModel::removeSkill, viewModel::saveMcpServer,
                    viewModel::removeMcpServer, viewModel::importMcpConfig, viewModel::testMcpServer,
                    { kind, source, query, more -> viewModel.browseCatalog(kind, source, query, more) }, viewModel::refreshCatalog, viewModel::inspectCatalogSkill, viewModel::installCatalogSkill, viewModel::inspectCatalogServer, viewModel::readCatalogDocument,
                ),
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
    onRequestRootAccess: () -> Unit,
    rootProbing: Boolean,
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
                "点击“去授权”后申请相应权限，授权完成后返回应用即可继续。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "文件读写需要“所有文件访问”，也可前往 " +
                    "设置 > 应用 > CodeMate > 权限",
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
                    Text("文件读写：去授权")
                }
                OutlinedButton(onClick = onOpenStorageSettings) {
                    Text("打开应用设置")
                }
                OutlinedButton(onClick = onRequestRootAccess, enabled = !rootProbing) {
                    Text(if (rootProbing) "正在请求 Root…" else "使用 Root 授权")
                }
            }
            Text(
                "通知权限：${if (notificationGranted) "已完成" else "未完成"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!notificationGranted) {
                Button(onClick = onRequestNotifications) {
                    Text("通知权限：去授权")
                }
            }
            OutlinedButton(onClick = onRefresh) {
                Text("重新检测权限")
            }
        }
    }
}
