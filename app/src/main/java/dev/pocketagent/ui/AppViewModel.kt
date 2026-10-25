package dev.pocketagent.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.app.NotificationManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pocketagent.BuildConfig
import dev.pocketagent.agent.AgentEngine
import dev.pocketagent.agent.AgentEvent
import dev.pocketagent.agent.AgentPermissions
import dev.pocketagent.agent.AgentPlanStep
import dev.pocketagent.agent.AgentMessage
import dev.pocketagent.agent.AiClient
import dev.pocketagent.agent.PermissionDecision
import dev.pocketagent.agent.PermissionKind
import dev.pocketagent.agent.PermissionMode
import dev.pocketagent.agent.PermissionRequest
import dev.pocketagent.agent.TokenUsage
import dev.pocketagent.agent.ToolActivity
import dev.pocketagent.data.AiSettings
import dev.pocketagent.data.AuditLog
import dev.pocketagent.data.ConversationStore
import dev.pocketagent.data.ConversationSummary
import dev.pocketagent.data.CurrentConversationRepository
import dev.pocketagent.data.GitChange
import dev.pocketagent.data.GitHubDeviceAuthClient
import dev.pocketagent.data.GitHubDevicePollResult
import dev.pocketagent.data.GitHubOAuthConfigStore
import dev.pocketagent.data.GitHubRepository
import dev.pocketagent.data.GitOperationResult
import dev.pocketagent.data.GitRepository
import dev.pocketagent.data.ModelProfile
import dev.pocketagent.data.ManagedRepositoryStore
import dev.pocketagent.data.OnboardingRepository
import dev.pocketagent.data.PermissionSettingsRepository
import dev.pocketagent.data.SettingsRepository
import dev.pocketagent.data.WorkspaceRepository
import dev.pocketagent.data.StoredConversation
import dev.pocketagent.data.StoredMessage
import dev.pocketagent.runtime.CommandRunner
import dev.pocketagent.runtime.RootDetector
import dev.pocketagent.runtime.RuntimeCapabilities
import dev.pocketagent.runtime.SystemAccessRepository
import dev.pocketagent.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val internalRuntime = TermuxRuntime(application)
    private val auditLog = AuditLog(application)
    @Volatile
    private var autoUseRoot = false
    private val rootDetector = RootDetector()
    private val commandRunner = CommandRunner(
        rootDetector = rootDetector,
        internalRuntime = internalRuntime,
        useRoot = { autoUseRoot },
    )
    private val workspace = WorkspaceRepository(
        context = application,
        rootDetector = rootDetector,
        commandRunner = commandRunner,
        useRoot = { autoUseRoot },
    )
    val terminalManager = TerminalSessionManager(
        context = application,
        runtime = internalRuntime,
        workspace = { workspace.root },
        auditLog = auditLog,
        rootDetector = rootDetector,
    )
    private val systemAccess = SystemAccessRepository(
        context = application,
        commandRunner = commandRunner,
        useRoot = { autoUseRoot },
    )
    private val gitRepository = GitRepository(
        application,
        workspace,
        commandRunner,
        internalRuntime.home,
    )
    private val managedRepositoryStore = ManagedRepositoryStore(application)
    private val githubOAuthConfigStore = GitHubOAuthConfigStore(application)
    private val settingsRepository = SettingsRepository(application)
    private val permissionSettingsRepository = PermissionSettingsRepository(application)
    private val conversationStore = ConversationStore(application)
    private val currentConversationRepository = CurrentConversationRepository(application)
    private val onboardingRepository = OnboardingRepository(application)
    private val aiClient = AiClient()
    private val agentEngine = AgentEngine(
        workspace = workspace,
        commandRunner = commandRunner,
        systemAccess = systemAccess,
        rootAvailable = { autoUseRoot },
        aiClient = aiClient,
        auditLog = auditLog,
    )
    private var pendingApprovalDeferred: CompletableDeferred<Boolean>? = null
    private var agentJob: Job? = null
    private var editorLintJob: Job? = null
    private var githubLoginJob: Job? = null
    private val githubAuthClient = GitHubDeviceAuthClient()
    private val nextId = AtomicLong(1)
    private val nextConversationId = AtomicLong(1)
    private val conversationCache = linkedMapOf<Long, StoredConversation>()
    private var currentConversation: StoredConversation? = null

    private val _state = MutableStateFlow(
        AppUiState(
            onboardingCompleted = onboardingRepository.isCompleted(),
            capabilities = RuntimeCapabilities(
                internalTermuxReady = internalRuntime.isReady(),
            ),
            storageAccessGranted = hasStorageAccess(),
            notificationPermissionGranted = hasNotificationPermission(),
            gitAccounts = gitRepository.accounts(),
            activeGitAccountId = gitRepository.activeGithubAccount()?.id,
            githubOAuthClientConfigured = githubOAuthClientId().isNotBlank(),
            managedRepositories = managedRepositoryStore.load(),
            chat = emptyList(),
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init {
        auditLog.append("app", "应用启动", "Pocket Agent 启动")
        viewModelScope.launch {
            auditLog.entries.collect { entries ->
                _state.update { it.copy(auditEntries = entries) }
            }
        }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                LoadedState(
                    settings = settingsRepository.load(),
                    modelProfiles = settingsRepository.loadProfiles(),
                    activeProfileId = settingsRepository.activeProfileId(),
                    permissions = permissionSettingsRepository.load(),
                    conversations = conversationStore.load(),
                )
            }
            var settings = loaded.settings
            var workspaceResult = runCatching {
                workspace.configure(settings.workspacePath)
            }
            if (workspaceResult.isFailure) {
                val probe = rootDetector.probe()
                if (probe.granted) {
                    autoUseRoot = true
                    terminalManager.setUseRoot(true)
                    workspaceResult = runCatching {
                        workspace.configure(settings.workspacePath)
                    }
                }
                if (workspaceResult.isFailure) {
                    workspace.configure("")
                    settings = settings.copy(workspacePath = "")
                    auditLog.append(
                        "workspace",
                        "工作区回退默认目录",
                        workspaceResult.exceptionOrNull()?.message.orEmpty(),
                    )
                }
            }
            workspace.ensureWorkspace()
            conversationCache.clear()
            loaded.conversations.forEach { conversationCache[it.id] = it }
            nextConversationId.set(
                (conversationCache.keys.maxOrNull() ?: 0L) + 1,
            )
            val maxMessageId = conversationCache.values
                .flatMap { it.messages }
                .maxOfOrNull { it.id } ?: 0L
            nextId.set(maxMessageId + 1)
            _state.update {
                it.copy(
                    settings = settings,
                    modelProfiles = loaded.modelProfiles,
                    activeProfileId = loaded.activeProfileId,
                    permissions = loaded.permissions,
                    workspacePath = workspace.displayRoot,
                    defaultWorkspacePath = "/sdcard",
                )
            }
            auditLog.append(
                "permission",
                "加载权限",
                "mode=${loaded.permissions.mode} fileWrite=${loaded.permissions.allowFileWrite} " +
                    "commands=${loaded.permissions.allowCommands} network=${loaded.permissions.allowNetwork} " +
                    "systemRead=${loaded.permissions.allowSystemRead}",
            )
            val selected = currentConversationRepository.load()
                ?.let(conversationCache::get)
                ?: conversationCache.values.maxByOrNull { it.updatedAt }
            if (selected != null) {
                loadConversation(selected)
            } else {
                newConversation()
            }
            if (settings.apiKey.isNotBlank()) {
                refreshModels()
            }
            probeRoot(announce = false)
            refreshFiles()
        }
    }

    fun newConversation() {
        if (_state.value.agentRunning) {
            _state.update { it.copy(notice = "请等待当前任务结束") }
            return
        }
        persistCurrentConversation()
        val now = System.currentTimeMillis()
        val id = nextConversationId.getAndIncrement()
        val welcome = ChatEntry(
            id = nextId.getAndIncrement(),
            role = ChatRole.ASSISTANT,
            content = "新对话已开始。可以让我检查项目、改代码并运行测试。",
            timestamp = now,
        )
        val conversation = StoredConversation(
            id = id,
            title = "新对话",
            createdAt = now,
            updatedAt = now,
            messages = listOf(welcome.toStoredMessage()),
        )
        conversationCache[id] = conversation
        currentConversation = conversation
        currentConversationRepository.save(id)
        agentEngine.reset()
        _state.update {
            it.copy(
                chat = listOf(welcome),
                currentConversationId = id,
                conversations = conversationSummaries(),
            )
        }
        saveConversationCache()
        auditLog.append("conversation", "新建对话", "id=$id")
    }

    fun selectConversation(id: Long) {
        if (_state.value.agentRunning) {
            _state.update { it.copy(notice = "请等待当前任务结束") }
            return
        }
        val conversation = conversationCache[id] ?: return
        if (currentConversation?.id == id) return
        persistCurrentConversation()
        loadConversation(conversation)
    }

    fun deleteConversation(id: Long) {
        if (_state.value.agentRunning && currentConversation?.id == id) {
            _state.update { it.copy(notice = "不能删除正在执行的对话") }
            return
        }
        conversationCache.remove(id)
        if (currentConversation?.id == id) {
            currentConversation = null
            val next = conversationCache.values.maxByOrNull { it.updatedAt }
            if (next != null) loadConversation(next) else newConversation()
        } else {
            _state.update { it.copy(conversations = conversationSummaries()) }
        }
        saveConversationCache()
        auditLog.append("conversation", "删除对话", "id=$id")
    }

    private fun loadConversation(conversation: StoredConversation) {
        currentConversation = conversation
        currentConversationRepository.save(conversation.id)
        val chat = conversation.messages.mapNotNull { message ->
            val role = runCatching { ChatRole.valueOf(message.role) }.getOrNull() ?: return@mapNotNull null
            val content = if (role == ChatRole.ASSISTANT && message.activities.isEmpty()) {
                cleanLegacyToolTrace(message.content)
            } else {
                message.content
            }
            ChatEntry(
                id = message.id,
                role = role,
                content = content,
                timestamp = message.timestamp,
                activities = message.activities,
                usage = message.usage,
                lastRequestUsage = message.lastRequestUsage,
                lastRequestAt = message.lastRequestAt,
                plan = message.plan,
                error = message.error,
                retryCount = message.retryCount,
            )
        }
        val history = chat.mapNotNull { message ->
            when (message.role) {
                ChatRole.USER -> AgentMessage("user", message.content)
                ChatRole.ASSISTANT -> AgentMessage("assistant", message.content)
                else -> null
            }
        }
        if (conversation.agentMessages.isNotEmpty()) {
            agentEngine.loadConversation(conversation.agentMessages)
        } else {
            agentEngine.loadHistory(history)
        }
        _state.update {
            it.copy(
                chat = chat,
                currentConversationId = conversation.id,
                conversations = conversationSummaries(),
            )
        }
        auditLog.append(
            "conversation",
            "切换对话",
            "id=${conversation.id} title=${conversation.title} messages=${conversation.messages.size}",
        )
    }

    private fun cleanLegacyToolTrace(content: String): String =
        content.lineSequence()
            .filterNot { line ->
                line.trimStart().startsWith("⚙ ") ||
                    line.trimStart().startsWith("↳ ")
            }
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()

    private fun persistCurrentConversation() {
        val conversation = currentConversation ?: return
        val chat = _state.value.chat
            .filter { it.role != ChatRole.SYSTEM }
            .filter { it.content.isNotBlank() || it.error != null }
        if (chat.isEmpty()) return
        val firstUser = chat.firstOrNull { it.role == ChatRole.USER }?.content
        val title = if (conversation.title == "新对话" && firstUser != null) {
            firstUser.lineSequence().firstOrNull()?.trim()?.take(28)
                ?.ifBlank { "新对话" } ?: "新对话"
        } else {
            conversation.title
        }
        val updated = conversation.copy(
            title = title,
            updatedAt = System.currentTimeMillis(),
            messages = chat.map { it.toStoredMessage() },
            agentMessages = agentEngine.snapshotHistory(),
        )
        conversationCache[updated.id] = updated
        currentConversation = updated
        _state.update { it.copy(conversations = conversationSummaries()) }
        saveConversationCache()
    }

    private fun saveConversationCache() {
        val snapshot = conversationCache.values.toList()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { conversationStore.save(snapshot) }
        }
    }

    private fun conversationSummaries(): List<ConversationSummary> =
        conversationCache.values
            .sortedByDescending { it.updatedAt }
            .map { conversation ->
                val preview = conversation.messages
                    .lastOrNull { it.content.isNotBlank() }
                    ?.content
                    ?.replace('\n', ' ')
                    ?.take(80)
                    .orEmpty()
                ConversationSummary(
                    id = conversation.id,
                    title = conversation.title,
                    updatedAt = conversation.updatedAt,
                    messageCount = conversation.messages.size,
                    preview = preview,
                )
            }

    private fun ChatEntry.toStoredMessage(): StoredMessage = StoredMessage(
        id = id,
        role = role.name,
        content = content,
        timestamp = timestamp,
        activities = activities,
        usage = usage,
        lastRequestUsage = lastRequestUsage,
        lastRequestAt = lastRequestAt,
        plan = plan,
        error = error,
        retryCount = retryCount,
    )

    private data class LoadedState(
        val settings: AiSettings,
        val modelProfiles: List<ModelProfile>,
        val activeProfileId: String,
        val permissions: AgentPermissions,
        val conversations: List<StoredConversation>,
    )

    fun selectTab(tab: AppTab) {
        _state.update { it.copy(tab = tab) }
        if (tab == AppTab.GIT) {
            refreshGit()
            if (
                _state.value.activeGitAccountId != null &&
                _state.value.githubRepositories.isEmpty()
            ) {
                refreshGitHubRepositories()
            }
        }
    }

    fun refreshSystemPermissions() {
        _state.update {
            it.copy(
                storageAccessGranted = hasStorageAccess(),
                notificationPermissionGranted = hasNotificationPermission(),
            )
        }
    }

    fun completeOnboarding() {
        onboardingRepository.complete()
        _state.update { it.copy(onboardingCompleted = true, tab = AppTab.FILES) }
        auditLog.append("app", "完成首次引导", "权限、模型和环境初始化完成")
    }

    fun saveSettings(settings: AiSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            val previousWorkspace = _state.value.workspacePath
            val workspaceResult = runCatching {
                workspace.configure(settings.workspacePath)
                workspace.ensureWorkspace()
            }
            val updatedSettings = if (workspaceResult.isSuccess) {
                settings.copy(workspacePath = workspace.displayRoot)
            } else {
                settings.copy(workspacePath = previousWorkspace)
            }
            val workspaceChanged = workspaceResult.isSuccess &&
                previousWorkspace != workspace.displayRoot
            settingsRepository.save(updatedSettings)
            val refreshedProfiles = settingsRepository.loadProfiles()
            val refreshedProfileId = settingsRepository.activeProfileId()
            auditLog.append(
                "settings",
                "保存模型设置",
                "baseUrl=${updatedSettings.baseUrl} model=${updatedSettings.model} " +
                    "context=${updatedSettings.contextWindow} " +
                    "workspace=${updatedSettings.workspacePath}",
            )
            if (workspaceChanged) {
                withContext(Dispatchers.Main) {
                    terminalManager.restart()
                }
            }
            _state.update {
                it.copy(
                    settings = updatedSettings,
                    modelProfiles = refreshedProfiles,
                    activeProfileId = refreshedProfileId,
                    workspacePath = workspace.displayRoot,
                    activeFile = if (workspaceChanged) null else it.activeFile,
                    editorContent = if (workspaceChanged) "" else it.editorContent,
                    editorDirty = if (workspaceChanged) false else it.editorDirty,
                    currentDirectory = if (workspaceChanged) "." else it.currentDirectory,
                    notice = if (workspaceResult.isSuccess) {
                        "模型与工作区设置已保存"
                    } else {
                        workspaceResult.exceptionOrNull()?.message ?: "工作区无法切换"
                    },
                )
            }
            if (workspaceResult.isSuccess) refreshFiles()
            refreshModels()
        }
    }

    fun savePermissions(permissions: AgentPermissions) {
        _state.update { it.copy(permissions = permissions) }
        viewModelScope.launch(Dispatchers.IO) {
            permissionSettingsRepository.save(permissions)
            auditLog.append(
                "permission",
                "修改权限",
                "mode=${permissions.mode} fileWrite=${permissions.allowFileWrite} " +
                    "commands=${permissions.allowCommands} network=${permissions.allowNetwork} " +
                    "systemRead=${permissions.allowSystemRead}",
            )
        }
    }

    fun refreshModels() {
        val settings = _state.value.settings
        if (_state.value.modelsLoading) return
        if (settings.apiKey.isBlank() || settings.baseUrl.isBlank()) {
            _state.update {
                it.copy(
                    availableModels = emptyList(),
                    modelsError = "填写 API Key 和 Base URL 后自动获取模型",
                )
            }
            return
        }

        _state.update { it.copy(modelsLoading = true, modelsError = null) }
        viewModelScope.launch {
            runCatching { aiClient.listModels(settings) }
                .onSuccess { modelInfos ->
                    val models = modelInfos.map { it.id }
                    val reasoningEfforts = modelInfos.associate {
                        it.id to it.reasoningEfforts
                    }
                    val preferred = when {
                        settings.model in models -> settings.model
                        "deepseek-chat" in models -> "deepseek-chat"
                        else -> models.firstOrNull() ?: settings.model
                    }
                    val availableEfforts = reasoningEfforts[preferred].orEmpty()
                    val effort = settings.reasoningEffort.takeIf {
                        it == "auto" || it in availableEfforts
                    } ?: "auto"
                    val updated = settings.copy(
                        model = preferred,
                        reasoningEffort = effort,
                    )
                    if (updated != settings) {
                        withContext(Dispatchers.IO) { settingsRepository.save(updated) }
                    }
                    val refreshedProfiles = withContext(Dispatchers.IO) {
                        settingsRepository.loadProfiles()
                    }
                    val refreshedProfileId = withContext(Dispatchers.IO) {
                        settingsRepository.activeProfileId()
                    }
                    _state.update {
                        it.copy(
                            settings = updated,
                            modelProfiles = refreshedProfiles,
                            activeProfileId = refreshedProfileId,
                            availableModels = models,
                            modelReasoningEfforts = reasoningEfforts,
                            modelsLoading = false,
                            modelsError = if (models.isEmpty()) "服务商没有返回可用模型" else null,
                        )
                    }
                    auditLog.append(
                        "model",
                        "获取模型列表",
                        "count=${models.size} selected=$preferred effort=$effort",
                    )
                }
                .onFailure { error ->
                    auditLog.append("model", "获取模型列表失败", error.message.orEmpty())
                    _state.update {
                        it.copy(
                            modelsLoading = false,
                            modelsError = error.message ?: "获取模型列表失败",
                        )
                    }
                }
        }
    }

    fun selectModel(model: String) {
        val supported = _state.value.modelReasoningEfforts[model].orEmpty()
        val currentEffort = _state.value.settings.reasoningEffort
        val effort = currentEffort.takeIf { it == "auto" || it in supported } ?: "auto"
        val updated = _state.value.settings.copy(
            model = model,
            reasoningEffort = effort,
        )
        _state.update { it.copy(settings = updated) }
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.save(updated)
            val profiles = settingsRepository.loadProfiles()
            _state.update { it.copy(modelProfiles = profiles) }
            auditLog.append("model", "切换模型", model)
        }
    }

    fun selectReasoningEffort(effort: String) {
        val settings = _state.value.settings
        val supported = _state.value.modelReasoningEfforts[settings.model].orEmpty()
        if (effort != "auto" && effort !in supported) return
        val updated = settings.copy(reasoningEffort = effort)
        _state.update { it.copy(settings = updated) }
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.save(updated)
            val profiles = settingsRepository.loadProfiles()
            _state.update { it.copy(modelProfiles = profiles) }
            auditLog.append(
                "model",
                "切换思考等级",
                "model=${settings.model} effort=$effort",
            )
        }
    }

    fun selectModelProfile(profileId: String) {
        val profile = _state.value.modelProfiles.firstOrNull { it.id == profileId } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.setActiveProfile(profile.id)
            val settings = profile.toSettings(_state.value.workspacePath)
            _state.update {
                it.copy(
                    settings = settings,
                    activeProfileId = profile.id,
                    availableModels = emptyList(),
                    modelReasoningEfforts = emptyMap(),
                    modelsError = null,
                )
            }
            auditLog.append("model", "切换模型配置", "profile=${profile.name} id=${profile.id}")
            refreshModels()
        }
    }

    fun createModelProfile(
        name: String,
        baseUrl: String,
        apiKey: String,
        contextWindow: Int,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val profile = settingsRepository.createProfile(name, baseUrl, apiKey, contextWindow)
            val profiles = settingsRepository.loadProfiles()
            val settings = profile.toSettings(_state.value.workspacePath)
            _state.update {
                it.copy(
                    settings = settings,
                    modelProfiles = profiles,
                    activeProfileId = profile.id,
                    availableModels = emptyList(),
                    modelReasoningEfforts = emptyMap(),
                    modelsError = null,
                    notice = "已创建模型配置 ${profile.name}",
                )
            }
            auditLog.append("model", "新建模型配置", "name=${profile.name}")
            refreshModels()
        }
    }

    fun deleteModelProfile(profileId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { settingsRepository.deleteProfile(profileId) }
                .onSuccess {
                    val profiles = settingsRepository.loadProfiles()
                    val activeId = settingsRepository.activeProfileId()
                    val profile = profiles.first { it.id == activeId }
                    _state.update {
                        it.copy(
                            settings = profile.toSettings(_state.value.workspacePath),
                            modelProfiles = profiles,
                            activeProfileId = activeId,
                            availableModels = emptyList(),
                            modelReasoningEfforts = emptyMap(),
                            notice = "模型配置已删除",
                        )
                    }
                    auditLog.append("model", "删除模型配置", "id=$profileId")
                    refreshModels()
                }
                .onFailure { error ->
                    _state.update { it.copy(notice = error.message ?: "无法删除模型配置") }
                }
        }
    }

    fun renameModelProfile(profileId: String, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.renameProfile(profileId, name)
            _state.update { it.copy(modelProfiles = settingsRepository.loadProfiles()) }
        }
    }

    fun approvePending() {
        pendingApprovalDeferred?.complete(true)
    }

    fun denyPending() {
        pendingApprovalDeferred?.complete(false)
    }

    private suspend fun authorizeAgentAction(request: PermissionRequest): PermissionDecision {
        val permissions = _state.value.permissions
        fun deny(reason: String): PermissionDecision {
            auditLog.append("permission", "拒绝 ${request.title}", request.detail, reason)
            return PermissionDecision(false, reason)
        }
        fun allow(reason: String): PermissionDecision {
            auditLog.append("permission", "允许 ${request.title}", request.detail, reason)
            return PermissionDecision(true, reason)
        }

        if (request.kind == PermissionKind.ROOT) {
            return deny("Agent 没有 root 权限")
        }

        return when (request.kind) {
            PermissionKind.WRITE_FILES -> {
                if (permissions.mode == PermissionMode.READ_ONLY) {
                    deny("当前是只读模式")
                } else if (!permissions.allowFileWrite) {
                    deny("文件写入权限已关闭")
                } else if (permissions.mode == PermissionMode.AUTO) {
                    allow("自动模式")
                } else {
                    requestApproval(request)
                }
            }
            PermissionKind.RUN_COMMAND -> {
                if (permissions.mode == PermissionMode.READ_ONLY) {
                    deny("当前是只读模式")
                } else if (!permissions.allowCommands) {
                    deny("命令执行权限已关闭")
                } else if (permissions.mode == PermissionMode.AUTO) {
                    allow("自动模式")
                } else {
                    requestApproval(request)
                }
            }
            PermissionKind.NETWORK -> {
                if (permissions.mode == PermissionMode.READ_ONLY) {
                    deny("当前是只读模式")
                } else if (!permissions.allowCommands) {
                    deny("命令执行权限已关闭")
                } else if (permissions.mode == PermissionMode.AUTO && permissions.allowNetwork) {
                    allow("自动模式且已允许联网")
                } else {
                    requestApproval(request)
                }
            }
            PermissionKind.DESTRUCTIVE -> {
                if (permissions.mode == PermissionMode.READ_ONLY) {
                    deny("当前是只读模式")
                } else if (!permissions.allowCommands) {
                    deny("命令执行权限已关闭")
                } else if (permissions.mode == PermissionMode.AUTO) {
                    allow("自动模式 · 命令已完整审计")
                } else {
                    requestApproval(request)
                }
            }
            PermissionKind.SYSTEM_READ -> {
                if (!permissions.allowSystemRead) {
                    deny("系统信息读取权限已关闭")
                } else if (permissions.mode == PermissionMode.ASK) {
                    requestApproval(request)
                } else {
                    allow(
                        if (autoUseRoot) {
                            "允许系统只读 · 自动使用 Root"
                        } else {
                            "允许系统只读 · 普通 App 权限"
                        },
                    )
                }
            }
            PermissionKind.ROOT -> deny("Agent 没有 root 权限")
        }
    }

    private suspend fun requestApproval(request: PermissionRequest): PermissionDecision {
        if (pendingApprovalDeferred != null) {
            auditLog.append("permission", "授权冲突", request.title, "已有待审批请求")
            return PermissionDecision(false, "已有待审批请求")
        }
        val deferred = CompletableDeferred<Boolean>()
        pendingApprovalDeferred = deferred
        val requestId = nextId.getAndIncrement()
        auditLog.append(
            "permission",
            "请求授权",
            "${request.title}\n${request.detail}",
            "risk=${request.risk}",
        )
        _state.update {
            it.copy(
                pendingApproval = PendingApproval(
                    id = requestId,
                    title = request.title,
                    detail = request.detail,
                    risk = request.risk,
                ),
            )
        }
        val allowed = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { deferred.await() } ?: false
        pendingApprovalDeferred = null
        _state.update { it.copy(pendingApproval = null) }
        auditLog.append(
            "permission",
            if (allowed) "用户允许" else "用户拒绝",
            request.detail,
            if (allowed) "allowed" else "denied",
        )
        return PermissionDecision(
            allowed = allowed,
            reason = if (allowed) "用户允许" else "用户拒绝或审批超时",
        )
    }

    fun probeRoot(announce: Boolean = true) {
        if (_state.value.rootProbing) return
        auditLog.append("root", "请求 Root 探测", "由用户手动触发")
        _state.update {
            it.copy(
                rootProbing = true,
                notice = if (announce) "正在请求 root 授权…" else it.notice,
            )
        }
        viewModelScope.launch {
            val probe = rootDetector.probe()
            autoUseRoot = probe.granted
            terminalManager.setUseRoot(probe.granted)
            auditLog.append(
                "root",
                "Root 探测结果",
                "uid=${probe.uid} data=${probe.canReadAppData} " +
                    "chroot=${probe.chrootAvailable} mount=${probe.mountAvailable}",
                probe.error ?: "granted=${probe.granted}",
            )
            _state.update { current ->
                current.copy(
                    rootProbing = false,
                    capabilities = current.capabilities.copy(
                        rootGranted = probe.granted,
                        systemReadable = probe.canReadAppData,
                        chrootAvailable = probe.chrootAvailable,
                        mountAvailable = probe.mountAvailable,
                        error = probe.error,
                    ),
                    terminalRoot = if (probe.granted) current.terminalRoot else false,
                    notice = if (announce && probe.granted) {
                        "Root 已授权，Agent 和终端会自动使用 Root"
                    } else if (announce) {
                        probe.error ?: "Root 不可用"
                    } else {
                        current.notice
                    },
                )
            }
            if (probe.granted) {
                repairRuntimeOwnership()
            }
            installInternalRuntime()
        }
    }

    private suspend fun repairRuntimeOwnership() {
        val owner = appContext.applicationInfo.uid
        val path = shellQuote(internalRuntime.runtimeRoot.absolutePath)
        val contextSource = shellQuote(
            internalRuntime.runtimeRoot.parentFile?.absolutePath
                ?: appContext.filesDir.absolutePath,
        )
        val result = commandRunner.run(
            command = buildString {
                append("chown -R $owner:$owner -- $path")
                append(" && context=${'$'}(stat -c '%C' $contextSource)")
                append(" && [ -n \"${'$'}context\" ]")
                append(" && /system/bin/chcon -Rh \"${'$'}context\" $path")
                append(" && if [ -x /system/bin/restorecon ]; then ")
                append("/system/bin/restorecon -RF $path; fi")
            },
            workingDir = File("/"),
            asRoot = true,
            timeoutMs = 60_000,
        )
        auditLog.append(
            "runtime",
            "修复运行环境所有权",
            result.output.ifBlank { result.error ?: "owner=$owner path=$path" },
            if (result.exitCode == 0) "success" else "failed",
        )
    }

    fun installInternalRuntime() {
        if (_state.value.internalRuntimeInstalling) return
        if (internalRuntime.isReady()) {
            _state.update {
                it.copy(
                    capabilities = it.capabilities.copy(internalTermuxReady = true),
                    internalRuntimeInstallMessage = "内置开发环境已就绪",
                    internalRuntimeInstallProgress = 1f,
                )
            }
            return
        }
        _state.update {
            it.copy(
                internalRuntimeInstalling = true,
                internalRuntimeInstallMessage = "准备内置开发环境",
                internalRuntimeInstallProgress = 0f,
            )
        }
        auditLog.append("runtime", "安装开发环境", "开始解压运行环境和工具链")
        viewModelScope.launch {
            val installed = internalRuntime.ensureInstalled { message, progress ->
                _state.update {
                    it.copy(
                        internalRuntimeInstallMessage = message,
                        internalRuntimeInstallProgress = progress.coerceIn(0f, 1f),
                    )
                }
            }
            _state.update {
                it.copy(
                    internalRuntimeInstalling = false,
                    internalRuntimeInstallMessage = if (installed) {
                        "内置开发环境已就绪"
                    } else {
                        "内置开发环境安装失败"
                    },
                    internalRuntimeInstallProgress = if (installed) 1f else 0f,
                    capabilities = it.capabilities.copy(
                        internalTermuxReady = internalRuntime.isReady(),
                    ),
                    notice = if (installed) {
                        "内置开发环境已就绪"
                    } else {
                        "内置开发环境安装失败"
                    },
                )
            }
            auditLog.append(
                "runtime",
                if (installed) "内置开发环境就绪" else "内置开发环境安装失败",
                "prefix=${internalRuntime.prefix.absolutePath}",
            )
        }
    }

    private suspend fun ensureGitToolchain(): Boolean {
        if (internalRuntime.isGitReady()) return true
        _state.update {
            it.copy(
                internalRuntimeInstalling = true,
                internalRuntimeInstallProgress = 0.02f,
                internalRuntimeInstallMessage = "准备 Git 与 SSH 工具链",
            )
        }
        val installed = internalRuntime.ensureInstalled { message, progress ->
            _state.update {
                it.copy(
                    internalRuntimeInstallMessage = message,
                    internalRuntimeInstallProgress = progress.coerceIn(0f, 1f),
                )
            }
        }
        val gitReady = internalRuntime.isGitReady()
        val success = installed || gitReady
        _state.update {
            it.copy(
                internalRuntimeInstalling = false,
                internalRuntimeInstallProgress = if (success) 1f else 0f,
                internalRuntimeInstallMessage = if (success) {
                    "Git、SSH 和开发工具链已就绪"
                } else {
                    "Git 工具链安装失败"
                },
                capabilities = it.capabilities.copy(
                    internalTermuxReady = internalRuntime.isReady(),
                ),
            )
        }
        auditLog.append(
            "runtime",
            if (success) "Git 工具链就绪" else "Git 工具链安装失败",
            "prefix=${internalRuntime.prefix.absolutePath} " +
                "git=${internalRuntime.isGitReady()} installed=$installed",
        )
        return success
    }

    fun refreshRuntimeCapabilities() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    capabilities = it.capabilities.copy(
                        internalTermuxReady = internalRuntime.isReady(),
                    ),
                )
            }
        }
    }

    fun refreshFiles() {
        viewModelScope.launch {
            runCatching {
                val normalized = workspace.normalizeWorkspacePath(
                    _state.value.currentDirectory,
                )
                if (normalized != _state.value.currentDirectory) {
                    _state.update { it.copy(currentDirectory = normalized) }
                }
                workspace.listChildren(normalized)
            }.onSuccess { files ->
                _state.update { it.copy(files = files) }
            }.onFailure { error ->
                val message = error.message ?: "文件访问被系统拒绝"
                val permissionHint = if (
                    error is SecurityException ||
                    message.contains("permission", ignoreCase = true) ||
                    message.contains("EACCES", ignoreCase = true)
                ) {
                    "。请在系统设置中开启“所有文件访问”"
                } else {
                    ""
                }
                _state.update {
                    it.copy(
                        files = emptyList(),
                        notice = "无法读取目录：$message$permissionHint",
                    )
                }
            }
        }
    }

    fun openDirectory(path: String) {
        viewModelScope.launch {
            runCatching { workspace.normalizeWorkspacePath(path) }
                .onSuccess { normalized ->
                    _state.update { it.copy(currentDirectory = normalized) }
                    refreshFiles()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(notice = error.message ?: "无法进入目录")
                    }
                }
        }
    }

    fun goToParentDirectory() {
        val current = _state.value.currentDirectory
        if (current == ".") return
        val parent = current.substringBeforeLast('/', missingDelimiterValue = "")
            .ifBlank { "." }
        openDirectory(parent)
    }

    fun openWorkspacePicker() {
        _state.update {
            it.copy(
                workspacePickerVisible = true,
                workspacePickerPath = workspace.displayRoot,
                workspacePickerEntries = emptyList(),
                workspacePickerError = null,
            )
        }
        browseWorkspaceDirectory(workspace.displayRoot)
    }

    fun browseWorkspaceDirectory(path: String) {
        _state.update {
            it.copy(
                workspacePickerPath = path,
                workspacePickerLoading = true,
                workspacePickerError = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { workspace.listDirectories(path) }
                .onSuccess { entries ->
                    _state.update {
                        it.copy(
                            workspacePickerEntries = entries,
                            workspacePickerLoading = false,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            workspacePickerEntries = emptyList(),
                            workspacePickerLoading = false,
                            workspacePickerError = error.message ?: "无法浏览目录",
                        )
                    }
                }
        }
    }

    fun chooseWorkspaceDirectory(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val previous = workspace.displayRoot
            runCatching {
                workspace.configure(path)
                workspace.ensureWorkspace()
            }.onSuccess {
                val updated = _state.value.settings.copy(
                    workspacePath = workspace.displayRoot,
                )
                settingsRepository.save(updated)
                auditLog.append(
                    "workspace",
                    "切换工作区",
                    "from=$previous to=${workspace.displayRoot}",
                    "success",
                )
                withContext(Dispatchers.Main) {
                    terminalManager.restart()
                }
                _state.update {
                    it.copy(
                        settings = updated,
                        workspacePath = workspace.displayRoot,
                        workspacePickerVisible = false,
                        currentDirectory = ".",
                        activeFile = null,
                        editorContent = "",
                        editorDirty = false,
                        notice = "工作区已切换到 ${workspace.displayRoot}",
                    )
                }
                refreshFiles()
                refreshGit()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        workspacePickerError = error.message ?: "无法切换工作区",
                        workspacePickerLoading = false,
                    )
                }
            }
        }
    }

    fun closeWorkspacePicker() {
        _state.update { it.copy(workspacePickerVisible = false) }
    }

    fun refreshGit() {
        if (_state.value.gitLoading) return
        _state.update {
            it.copy(
                gitLoading = true,
                gitError = if (internalRuntime.isGitReady()) null
                else "正在安装 Git 与 SSH 工具链",
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (!ensureGitToolchain()) {
                _state.update {
                    it.copy(
                        gitLoading = false,
                        gitError = "Git 工具链安装失败，请在设置中重试环境初始化",
                    )
                }
                return@launch
            }
            runCatching { gitRepository.state() }
                .onSuccess { git ->
                    val repositories = if (git.isRepository) {
                        managedRepositoryStore.touch(workspace.displayRoot)
                    } else {
                        managedRepositoryStore.load()
                    }
                    _state.update {
                        it.copy(
                            gitState = git,
                            gitLoading = false,
                            gitError = git.error,
                            managedRepositories = repositories,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            gitLoading = false,
                            gitError = error.message ?: "无法读取 Git 状态",
                        )
                    }
                }
        }
    }

    fun openGitDiff(change: GitChange) {
        _state.update {
            it.copy(
                gitLoading = true,
                gitDiffTitle = "${change.status}  ${change.path}",
                gitDiff = "",
                gitError = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { gitRepository.diff(change) }
                .onSuccess { diff ->
                    _state.update {
                        it.copy(gitDiff = diff, gitLoading = false)
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            gitLoading = false,
                            gitDiff = error.message ?: "无法读取 diff",
                            gitError = error.message ?: "无法读取 diff",
                        )
                    }
                }
        }
    }

    fun closeGitDiff() {
        _state.update { it.copy(gitDiff = "", gitDiffTitle = null) }
    }

    fun openGitCommitDetail(hash: String) {
        if (_state.value.gitCommitDetailLoading) return
        _state.update {
            it.copy(
                gitCommitDetail = null,
                gitCommitDetailLoading = true,
                gitError = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { gitRepository.commitDetail(hash) }
                .onSuccess { detail ->
                    _state.update {
                        it.copy(gitCommitDetail = detail, gitCommitDetailLoading = false)
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            gitCommitDetailLoading = false,
                            gitError = error.message ?: "无法读取提交详情",
                        )
                    }
                }
        }
    }

    fun closeGitCommitDetail() {
        _state.update { it.copy(gitCommitDetail = null, gitCommitDetailLoading = false) }
    }

    fun switchManagedRepository(path: String) {
        chooseWorkspaceDirectory(path)
    }

    fun removeManagedRepository(path: String) {
        val repositories = managedRepositoryStore.remove(path)
        _state.update {
            it.copy(
                managedRepositories = repositories,
                notice = "已从本地仓库列表移除",
            )
        }
    }

    fun gitInit() = runGitAction { gitRepository.init() }

    fun gitStage(change: GitChange) = runGitAction { gitRepository.stage(change) }

    fun gitStageAll() = runGitAction { gitRepository.stageAll() }

    fun gitUnstage(change: GitChange) = runGitAction { gitRepository.unstage(change) }

    fun gitCommit(message: String) = runGitAction { gitRepository.commit(message) }

    fun gitSwitchBranch(branch: String, create: Boolean) = runGitAction {
        gitRepository.switchBranch(branch, create)
    }

    fun gitPull() = runGitAction { gitRepository.pull() }

    fun gitPush() = runGitAction { gitRepository.push() }

    fun removeGitAccount(accountId: String) {
        gitRepository.removeAccount(accountId)
        refreshGitAccounts()
        if (gitRepository.activeGithubAccount() == null) {
            _state.update {
                it.copy(
                    githubRepositories = emptyList(),
                    githubRepositoriesError = null,
                )
            }
        } else {
            refreshGitHubRepositories()
        }
    }

    fun startGitHubLogin() {
        if (_state.value.githubLoginRunning) return
        val clientId = githubOAuthClientId()
        if (clientId.isBlank()) {
            _state.update {
                it.copy(
                    githubLoginError = "GitHub OAuth 尚未配置 Client ID",
                    notice = "需要在构建时配置 POCKET_GITHUB_CLIENT_ID",
                )
            }
            return
        }
        githubLoginJob?.cancel()
        githubAuthClient.cancel()
        _state.update {
            it.copy(
                githubLoginRunning = true,
                githubLoginError = null,
                githubLogin = GitHubLoginState(),
            )
        }
        auditLog.append("git", "GitHub 登录", "通过 OAuth Device Flow 开始授权")
        githubLoginJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val grant = githubAuthClient.requestDeviceGrant(clientId)
                val expiresAt = System.currentTimeMillis() + grant.expiresInSeconds * 1_000L
                var intervalMs = grant.intervalSeconds * 1_000L
                _state.update {
                    it.copy(
                        githubLogin = GitHubLoginState(
                            userCode = grant.userCode,
                            verificationUri = grant.verificationUri,
                            status = "等待浏览器确认授权",
                            expiresAt = expiresAt,
                        ),
                    )
                }
                auditLog.append("git", "GitHub 登录等待授权", "code=${grant.userCode}")
                while (System.currentTimeMillis() < expiresAt) {
                    delay(intervalMs)
                    if (System.currentTimeMillis() >= expiresAt) break
                    when (val result = githubAuthClient.poll(clientId, grant.deviceCode)) {
                        GitHubDevicePollResult.Pending -> {
                            val remaining = (
                                (expiresAt - System.currentTimeMillis()) / 1_000
                                ).coerceAtLeast(0)
                            _state.update {
                                it.copy(
                                    githubLogin = it.githubLogin?.copy(
                                        status = "等待确认 · 剩余 ${remaining}s",
                                    ),
                                )
                            }
                        }
                        GitHubDevicePollResult.SlowDown -> {
                            intervalMs = (intervalMs + 5_000).coerceAtMost(30_000)
                            _state.update {
                                it.copy(
                                    githubLogin = it.githubLogin?.copy(
                                        status = "GitHub 要求降低轮询频率，继续等待…",
                                    ),
                                )
                            }
                        }
                        GitHubDevicePollResult.Expired -> {
                            throw IllegalStateException("GitHub 授权码已过期，请重新登录")
                        }
                        GitHubDevicePollResult.Denied -> {
                            throw IllegalStateException("GitHub 授权已被拒绝")
                        }
                        is GitHubDevicePollResult.Authorized -> {
                            _state.update {
                                it.copy(
                                    githubLogin = it.githubLogin?.copy(
                                        status = "授权成功，正在读取账号…",
                                    ),
                                )
                            }
                            val user = githubAuthClient.currentUser(result.accessToken)
                            val account = gitRepository.saveGithubOAuthAccount(
                                accessToken = result.accessToken,
                                login = user.login,
                                displayName = user.name,
                            ) ?: throw IllegalStateException("无法保存 GitHub 凭据")
                            refreshGitAccounts()
                            refreshGitHubRepositories()
                            _state.update {
                                it.copy(
                                    githubLogin = null,
                                    githubLoginRunning = false,
                                    githubLoginError = null,
                                    notice = "已登录 GitHub：${account.label}",
                                )
                            }
                            auditLog.append(
                                "git",
                                "GitHub 登录成功",
                                "account=${user.login}",
                                "success",
                            )
                            return@launch
                        }
                    }
                }
                throw IllegalStateException("GitHub 授权码已过期，请重新登录")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (githubLoginJob == null) return@launch
                _state.update {
                    it.copy(
                        githubLogin = null,
                        githubLoginRunning = false,
                        githubLoginError = error.message ?: "GitHub 登录失败",
                    )
                }
                auditLog.append(
                    "git",
                    "GitHub 登录失败",
                    error.message ?: error.javaClass.simpleName,
                    "failed",
                )
            } finally {
                githubLoginJob = null
            }
        }
    }

    fun cancelGitHubLogin() {
        githubAuthClient.cancel()
        githubLoginJob?.cancel()
        githubLoginJob = null
        _state.update {
            it.copy(
                githubLogin = null,
                githubLoginRunning = false,
                githubLoginError = null,
            )
        }
        auditLog.append("git", "GitHub 登录取消", "用户关闭设备授权窗口")
    }

    fun saveGitHubOAuthClientId(clientId: String) {
        val normalized = clientId.trim()
        if (normalized.isBlank()) return
        githubOAuthConfigStore.saveClientId(normalized)
        _state.update {
            it.copy(
                githubOAuthClientConfigured = githubOAuthClientId().isNotBlank(),
                githubLoginError = null,
                notice = "GitHub OAuth Client ID 已保存",
            )
        }
    }

    private fun githubOAuthClientId(): String =
        BuildConfig.GITHUB_OAUTH_CLIENT_ID.trim().ifBlank {
            githubOAuthConfigStore.clientId()
        }

    fun refreshGitHubRepositories() {
        if (_state.value.githubRepositoriesLoading) return
        val token = gitRepository.githubAccessToken()
        if (token.isNullOrBlank()) {
            _state.update {
                it.copy(
                    githubRepositories = emptyList(),
                    githubRepositoriesLoading = false,
                    githubRepositoriesError = "登录 GitHub 后可查看仓库",
                )
            }
            return
        }
        _state.update {
            it.copy(
                githubRepositoriesLoading = true,
                githubRepositoriesError = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { githubAuthClient.repositories(token) }
                .onSuccess { repositories ->
                    _state.update {
                        it.copy(
                            githubRepositories = repositories,
                            githubRepositoriesLoading = false,
                            githubRepositoriesError = null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            githubRepositoriesLoading = false,
                            githubRepositoriesError = error.message ?: "无法读取 GitHub 仓库",
                        )
                    }
                }
        }
    }

    fun cloneGitHubRepository(repository: GitHubRepository) {
        gitClone(
            url = repository.cloneUrl,
            folder = repository.name,
            username = "",
            token = "",
            sshAccountId = null,
        )
    }

    private fun refreshGitAccounts() {
        val accounts = gitRepository.accounts()
        val active = gitRepository.activeGithubAccount()
        _state.update {
            it.copy(
                gitAccounts = accounts,
                activeGitAccountId = active?.id,
            )
        }
    }

    fun gitClone(
        url: String,
        folder: String,
        username: String,
        token: String,
        sshAccountId: String?,
    ) {
        if (_state.value.gitLoading) return
        _state.update { it.copy(gitLoading = true, gitError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            if (!ensureGitToolchain()) {
                _state.update {
                    it.copy(
                        gitLoading = false,
                        gitError = "Git 工具链安装失败",
                    )
                }
                return@launch
            }
            val result = runCatching {
                gitRepository.clone(url, folder, username, token, sshAccountId)
            }
                .getOrElse { GitOperationResult(false, it.message ?: "克隆失败") }
            _state.update {
                it.copy(
                    gitLoading = false,
                    gitError = if (result.success) null else result.message,
                    notice = result.message,
                )
            }
            auditLog.append(
                "git",
                "克隆仓库",
                "url=$url folder=$folder",
                if (result.success) "success" else result.message,
            )
            if (result.success) {
                refreshGitAccounts()
                chooseWorkspaceDirectory(File(workspace.root, folder).absolutePath)
            }
        }
    }

    private fun runGitAction(action: suspend () -> GitOperationResult) {
        if (_state.value.gitLoading) return
        _state.update { it.copy(gitLoading = true, gitError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            if (!ensureGitToolchain()) {
                _state.update {
                    it.copy(
                        gitLoading = false,
                        gitError = "Git 工具链安装失败",
                    )
                }
                return@launch
            }
            val result = runCatching { action() }
                .getOrElse { GitOperationResult(false, it.message ?: "Git 操作失败") }
            _state.update {
                it.copy(
                    gitLoading = false,
                    gitError = if (result.success) null else result.message,
                    notice = result.message,
                )
            }
            auditLog.append(
                "git",
                "Git 操作",
                result.message,
                if (result.success) "success" else "failed",
            )
            if (result.success) refreshGit()
        }
    }

    fun openFile(path: String) {
        viewModelScope.launch {
            runCatching { workspace.readText(path) }
                .onSuccess { content ->
                    _state.update {
                        it.copy(
                            activeFile = path,
                            editorContent = content,
                            editorDirty = false,
                            editorDiagnostics = emptyList(),
                        )
                    }
                    scheduleEditorLint(path, content)
                }
                .onFailure { error ->
                    _state.update { it.copy(notice = error.message ?: "无法读取文件") }
                }
        }
    }

    fun createWorkspaceEntry(name: String, directory: Boolean) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val parent = _state.value.currentDirectory
        val path = if (parent == ".") trimmed else "$parent/$trimmed"
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (directory) workspace.createDirectory(path)
                else workspace.createFile(path)
            }.onSuccess {
                auditLog.append(
                    "file",
                    if (directory) "新建目录" else "新建文件",
                    path,
                    "success",
                )
                _state.update {
                    it.copy(notice = if (directory) "目录已创建" else "文件已创建")
                }
                refreshFiles()
                refreshGit()
                if (!directory) openFile(path)
            }.onFailure { error ->
                _state.update { it.copy(notice = error.message ?: "创建失败") }
            }
        }
    }

    fun renameWorkspaceEntry(path: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { workspace.renameEntry(path, newName.trim()) }
                .onSuccess {
                    auditLog.append("file", "重命名", "$path -> ${newName.trim()}", "success")
                    _state.update {
                        it.copy(
                            notice = "重命名成功",
                            activeFile = if (it.activeFile == path) null else it.activeFile,
                        )
                    }
                    refreshFiles()
                    refreshGit()
                }
                .onFailure { error ->
                    _state.update { it.copy(notice = error.message ?: "重命名失败") }
                }
        }
    }

    fun deleteWorkspaceEntry(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { workspace.deleteEntry(path) }
                .onSuccess {
                    auditLog.append("file", "删除", path, "success")
                    _state.update {
                        it.copy(
                            notice = "已删除 $path",
                            activeFile = if (it.activeFile == path) null else it.activeFile,
                        )
                    }
                    refreshFiles()
                    refreshGit()
                }
                .onFailure { error ->
                    _state.update { it.copy(notice = error.message ?: "删除失败") }
                }
        }
    }

    fun closeFile() {
        editorLintJob?.cancel()
        editorLintJob = null
        _state.update {
            it.copy(
                activeFile = null,
                editorContent = "",
                editorDirty = false,
                editorDiagnostics = emptyList(),
            )
        }
    }

    fun updateEditor(content: String) {
        val path = _state.value.activeFile
        _state.update {
            it.copy(
                editorContent = content,
                editorDirty = true,
                editorDiagnostics = emptyList(),
            )
        }
        if (path != null) scheduleEditorLint(path, content)
    }

    fun saveEditor(editorContent: String) {
        val path = _state.value.activeFile ?: return
        _state.update { it.copy(editorContent = editorContent, editorDirty = true) }
        viewModelScope.launch {
            runCatching { workspace.writeText(path, editorContent) }
                .onSuccess {
                    _state.update { it.copy(editorDirty = false, notice = "已保存 $path") }
                    scheduleEditorLint(path, editorContent, delayMs = 0)
                    refreshFiles()
                    refreshGit()
                }
                .onFailure { error ->
                    _state.update { it.copy(notice = error.message ?: "保存失败") }
                }
        }
    }

    private fun scheduleEditorLint(
        path: String,
        content: String,
        delayMs: Long = 450,
    ) {
        editorLintJob?.cancel()
        editorLintJob = viewModelScope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (
                _state.value.activeFile != path ||
                _state.value.editorContent != content
            ) {
                return@launch
            }
            val diagnostics = runEditorLint(path, content)
            if (_state.value.activeFile == path) {
                _state.update { it.copy(editorDiagnostics = diagnostics) }
            }
        }
    }

    private suspend fun runEditorLint(
        path: String,
        content: String,
    ): List<EditorDiagnostic> {
        if (content.length > 500_000) {
            return emptyList()
        }
        val language = EditorLintLanguageResolver.resolve(path, content) ?: return emptyList()
        val characterDiagnostics = if (
            language == EditorLintLanguage.SHELL ||
            language == EditorLintLanguage.BASH
        ) {
            ShellCharacterChecker.check(content)
        } else {
            emptyList()
        }
        if (!internalRuntime.isBaseReady()) {
            return characterDiagnostics
        }
        if (
            language !in setOf(EditorLintLanguage.SHELL, EditorLintLanguage.BASH) &&
            !internalRuntime.isToolchainsReady()
        ) {
            return emptyList()
        }
        val commandFor: (String) -> String = when (language) {
            EditorLintLanguage.PYTHON -> { file ->
                "python3 -m py_compile ${shellQuote(file)}"
            }
            EditorLintLanguage.JAVASCRIPT -> { file ->
                "node --check ${shellQuote(file)}"
            }
            EditorLintLanguage.JAVA -> { file ->
                val classesDir = File(File(file).parentFile, "classes")
                "javac -encoding UTF-8 -proc:none -XDshould-stop.at=PARSE " +
                    "-d ${shellQuote(classesDir.absolutePath)} " +
                    shellQuote(file)
            }
            EditorLintLanguage.SHELL, EditorLintLanguage.BASH -> { file ->
                val dialect = language.shellDialect ?: "sh"
                val fallback = if (language == EditorLintLanguage.BASH) "bash -n" else "sh -n"
                "if command -v shellcheck >/dev/null 2>&1; then " +
                    "out=${'$'}(shellcheck -s $dialect -S error -f gcc ${shellQuote(file)} 2>&1); " +
                    "status=${'$'}?; " +
                    "case \"${'$'}out\" in " +
                    "*'CANNOT LINK EXECUTABLE'*|*'Permission denied'*|*'not found'*) " +
                    "$fallback ${shellQuote(file)}; exit ${'$'}? ;; " +
                    "*) printf '%s\\n' \"${'$'}out\"; exit ${'$'}status ;; esac; " +
                    "else $fallback ${shellQuote(file)}; fi"
            }
            EditorLintLanguage.JSON -> { file ->
                "python3 -m json.tool ${shellQuote(file)} >/dev/null"
            }
            EditorLintLanguage.C -> { file ->
                "clang -fsyntax-only -x c ${shellQuote(file)}"
            }
            EditorLintLanguage.CPP -> { file ->
                "clang --driver-mode=g++ -fsyntax-only -x c++ ${shellQuote(file)}"
            }
        }
        val lintDir = File(
            appContext.cacheDir,
            "editor-lint/${UUID.randomUUID()}",
        )
        val lintFilename = if (language == EditorLintLanguage.JAVA) {
            EditorLintLanguageResolver.javaSourceFilename(path, content)
        } else {
            "source.${language.sourceExtension}"
        }
        val lintFile = File(lintDir, lintFilename)
        return try {
            lintDir.mkdirs()
            lintFile.writeText(content)
            val command = commandFor(lintFile.absolutePath)
            val result = internalRuntime.run(
                command = command,
                workingDir = lintDir,
                timeoutMs = 30_000,
            )
            Log.d(
                EDITOR_LINT_TAG,
                "check=${language.name.lowercase()} exit=${result.exitCode} " +
                    "error=${result.error.orEmpty()} " +
                    "output=${result.output.take(500)}",
            )
            if (result.exitCode == 0) {
                characterDiagnostics
            } else {
                (
                    characterDiagnostics +
                        EditorDiagnosticParser.parse(result.output, lintFile.absolutePath)
                    ).distinctBy { it.line to it.column to it.message }
            }
        } catch (error: Exception) {
            Log.w(EDITOR_LINT_TAG, "Unable to check $path", error)
            emptyList()
        } finally {
            lintDir.deleteRecursively()
        }
    }

    fun runEditorFile(editorContent: String) {
        if (_state.value.scriptRunning) return
        val path = _state.value.activeFile ?: return
        val extension = path.substringAfterLast('.', "").lowercase()
        val runner = when (extension) {
            "sh", "shell" -> "sh"
            "bash" -> "bash"
            "py", "python" -> "python3"
            "js", "mjs", "cjs" -> "node"
            "java" -> "java"
            else -> {
                _state.update {
                    it.copy(notice = "当前文件类型不支持直接运行：.$extension")
                }
                return
            }
        }
        val file = workspace.resolve(path)
        val command = if (extension == "java") {
            "java --source 17 ${shellQuote(file.absolutePath)}"
        } else {
            "$runner -- ${shellQuote(file.absolutePath)}"
        }
        _state.update {
            it.copy(
                editorContent = editorContent,
                editorDirty = true,
                scriptRunning = true,
                scriptTitle = path,
                scriptOutput = "正在运行：\n$command\n\n",
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val output = runCatching {
                workspace.writeText(path, editorContent)
                commandRunner.run(
                    command = command,
                    workingDir = file.parentFile ?: workspace.root,
                    timeoutMs = 10 * 60_000L,
                )
            }.fold(
                onSuccess = { result ->
                    buildString {
                        append(result.output.trimEnd())
                        if (result.timedOut) append("\n\n运行超时，进程已终止")
                        append("\n\n[退出码 ${result.exitCode}]")
                    }.trim()
                },
                onFailure = { error ->
                    "运行失败：${error.message ?: error.javaClass.simpleName}"
                },
            )
            auditLog.append(
                "script",
                "运行脚本",
                "$command\n${output.takeLast(2_000)}",
                if (output.startsWith("运行失败")) "failed" else "success",
            )
            _state.update {
                it.copy(
                    scriptRunning = false,
                    scriptOutput = output,
                    editorDirty = false,
                )
            }
            refreshFiles()
            refreshGit()
        }
    }

    fun closeScriptOutput() {
        _state.update { it.copy(scriptOutput = null, scriptTitle = null) }
    }

    fun setTerminalRoot(enabled: Boolean) {
        if (enabled && !_state.value.capabilities.rootGranted) {
            _state.update { it.copy(notice = "请先在设置里完成 root 授权") }
            return
        }
        _state.update { it.copy(terminalRoot = enabled) }
    }

    fun runTerminal(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank() || _state.value.terminalRunning) return
        val asRoot = _state.value.terminalRoot
        if (asRoot && !_state.value.capabilities.rootGranted) {
            _state.update { it.copy(notice = "Root 当前不可用") }
            return
        }

        _state.update {
            it.copy(
                terminalRunning = true,
                terminalOutput = it.terminalOutput + "\n❯ ${if (asRoot) "[root] " else ""}$trimmed\n",
            )
        }
        auditLog.append(
            "terminal",
            "命令运行器",
            "root=$asRoot command=$trimmed",
        )
        viewModelScope.launch {
            val result = commandRunner.run(
                command = trimmed,
                workingDir = workspace.root,
                asRoot = asRoot,
                onOutput = ::appendTerminal,
            )
            val footer = buildString {
                if (result.error != null) append("\n[error] ${result.error}\n")
                append("\n[exit ${result.exitCode} · ${result.durationMs} ms")
                if (result.timedOut) append(" · timeout")
                append("]\n")
            }
            _state.update {
                it.copy(
                    terminalRunning = false,
                    terminalOutput = limitTerminal(it.terminalOutput + footer),
                )
            }
            auditLog.append(
                "terminal",
                "命令运行器结束",
                "command=$trimmed",
                "exit=${result.exitCode} timed_out=${result.timedOut}",
            )
        }
    }

    fun runDeviceInfoPreview() {
        selectTab(AppTab.TERMINAL)
        viewModelScope.launch {
            runCatching { systemAccess.deviceInfo() }
                .onSuccess { info ->
                    _state.update {
                        it.copy(
                            terminalOutput = limitTerminal(
                                it.terminalOutput + "\n[设备信息 · 只读]\n$info\n",
                            ),
                            notice = "设备信息读取成功",
                        )
                    }
                    auditLog.append(
                        "system",
                        "设备信息自检",
                        "chars=${info.length}",
                        "success",
                    )
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(notice = error.message ?: "设备信息读取失败")
                    }
                    auditLog.append(
                        "system",
                        "设备信息自检失败",
                        error.message.orEmpty(),
                    )
                }
        }
    }

    fun runRootDiagnostic() {
        selectTab(AppTab.TERMINAL)
        viewModelScope.launch(Dispatchers.IO) {
            val result = commandRunner.run(
                command = "echo ROOT_DIAG_OK; id -u; pwd",
                workingDir = workspace.root,
                asRoot = true,
                timeoutMs = 20_000,
            )
            appendTerminal(
                "\n[root diagnostic]\n${result.output}" +
                    "\nexit=${result.exitCode} error=${result.error.orEmpty()}\n",
            )
            auditLog.append(
                "root",
                "Root 命令自检",
                "cwd=${workspace.displayRoot} exit=${result.exitCode}",
                result.error ?: if (result.exitCode == 0) "success" else "failed",
            )
        }
    }

    fun runCacheProbe() {
        viewModelScope.launch {
            repeat(100) {
                if (_state.value.settings.apiKey.isNotBlank() && !_state.value.agentRunning) {
                    sendChat("只回复 CACHE_PROBE_OK，不要调用工具。")
                    return@launch
                }
                kotlinx.coroutines.delay(100)
            }
        }
    }

    fun runReasoningToolProbe() {
        viewModelScope.launch {
            repeat(150) {
                val state = _state.value
                if (
                    state.settings.apiKey.isNotBlank() &&
                    !state.agentRunning &&
                    state.modelReasoningEfforts[state.settings.model].orEmpty().contains("high")
                ) {
                    selectReasoningEffort("high")
                    sendChat("必须调用 device_info 读取设备型号，然后只回复设备型号。")
                    return@launch
                }
                kotlinx.coroutines.delay(100)
            }
        }
    }

    fun runToolchainDiagnostic() {
        selectTab(AppTab.TERMINAL)
        viewModelScope.launch(Dispatchers.IO) {
            val ok = internalRuntime.installToolchains(force = true) { message, _ ->
                appendTerminal("\n[toolchain] $message\n")
            }
            appendTerminal(
                "\n[toolchain diagnostic] ready=${internalRuntime.isToolchainsReady()} " +
                    "result=$ok\n",
            )
            auditLog.append(
                "runtime",
                "工具链安装自检",
                "force=true",
                if (ok) "success" else "failed",
            )
        }
    }

    fun stopTerminal() {
        commandRunner.cancel()
        appendTerminal("\n[已请求停止]\n")
    }

    fun sendChat(text: String) {
        val prompt = text.trim()
        if (prompt.isBlank() || _state.value.agentRunning) return
        if (_state.value.settings.apiKey.isBlank()) {
            _state.update { it.copy(notice = "请先在设置里填写 API Key") }
            return
        }

        val user = ChatEntry(nextId.getAndIncrement(), ChatRole.USER, prompt)
        val assistantId = nextId.getAndIncrement()
        val assistant = ChatEntry(
            id = assistantId,
            role = ChatRole.ASSISTANT,
            content = "",
            pending = true,
        )
        _state.update {
            it.copy(
                chat = it.chat + user + assistant,
                agentRunning = true,
                agentStatus = "正在分析项目",
                agentRequestStartedAt = System.currentTimeMillis(),
                agentRetry = null,
            )
        }
        persistCurrentConversation()

        agentJob = viewModelScope.launch {
            try {
                agentEngine.run(
                    userText = prompt,
                    settings = _state.value.settings,
                    authorize = ::authorizeAgentAction,
                ) { event ->
                    when (event) {
                        is AgentEvent.AssistantText -> appendChat(assistantId, event.text)
                        is AgentEvent.AssistantDelta -> appendChatDelta(assistantId, event.text)
                        is AgentEvent.Usage -> addUsage(assistantId, event.usage)
                        is AgentEvent.ModelRequestStarted -> {
                            startToolActivity(
                                assistantId,
                                "模型请求",
                                "${event.model} · 第 ${event.round} 轮 · " +
                                    "${event.messageCount} 条上下文",
                            )
                            _state.update {
                                it.copy(
                                    agentStatus = "正在等待模型响应 · 第 ${event.round} 轮",
                                    agentRequestStartedAt = System.currentTimeMillis(),
                                    agentRetry = null,
                                )
                            }
                        }
                        is AgentEvent.RetryScheduled -> {
                            val notice = event.notice
                            val retry = AgentRetryState(
                                attempt = notice.attempt,
                                maxAttempts = notice.maxAttempts,
                                reason = notice.reason,
                                retryAt = System.currentTimeMillis() +
                                    notice.delaySeconds * 1_000L,
                            )
                            updateRunningActivitySummary(
                                assistantId,
                                "模型请求",
                                "${notice.reason} · ${notice.delaySeconds} 秒后重试",
                            )
                            _state.update { current ->
                                current.copy(
                                    agentStatus = "准备重新连接",
                                    agentRetry = retry,
                                    chat = current.chat.map { entry ->
                                        if (entry.id == assistantId) {
                                            entry.copy(retryCount = notice.attempt)
                                        } else {
                                            entry
                                        }
                                    },
                                )
                            }
                        }
                        is AgentEvent.ModelRequestFinished -> {
                            finishToolActivity(
                                id = assistantId,
                                name = "模型请求",
                                summary = "响应 ${event.elapsedMs / 1_000.0}s · " +
                                    "${event.contentChars} 字符 · " +
                                    "${event.toolCallCount} 个工具调用",
                                success = true,
                                activitySummary = "第 ${event.round} 轮模型响应已返回",
                            )
                            _state.update {
                                it.copy(
                                    agentStatus = "模型已响应，正在处理结果",
                                    agentRequestStartedAt = null,
                                    agentRetry = null,
                                )
                            }
                        }
                        is AgentEvent.ModelReasoningProgress -> {
                            val summary = "模型正在思考 · 已生成 ${event.chars} 字符推理"
                            updateRunningActivitySummary(
                                assistantId,
                                "模型请求",
                                summary,
                            )
                            _state.update {
                                it.copy(agentStatus = summary)
                            }
                        }
                        is AgentEvent.PlanUpdated -> updatePlan(assistantId, event.steps)
                        is AgentEvent.ContextCompactionStarted -> {
                            val percent = (
                                event.beforeTokens.toFloat() /
                                    event.contextLimit.coerceAtLeast(1).toFloat() *
                                    100f
                                ).toInt().coerceIn(0, 100)
                            startToolActivity(
                                assistantId,
                                "上下文压缩",
                                "上下文 $percent% · 已超过自动压缩线，正在生成检查点以继续任务",
                            )
                            _state.update {
                                it.copy(
                                    agentStatus = "正在压缩上下文 · $percent% · 可随时停止",
                                    agentCompacting = true,
                                    agentCompactionStartedAt = System.currentTimeMillis(),
                                    agentCompactionBeforeTokens = event.beforeTokens,
                                    agentCompactionLimit = event.contextLimit,
                                )
                            }
                        }
                        is AgentEvent.ContextCompacted -> finishToolActivity(
                            id = assistantId,
                            name = "上下文压缩",
                            summary = "${formatKTokens(event.beforeTokens)} → " +
                                "${formatKTokens(event.afterTokens)} · " +
                                "${event.elapsedMs / 1_000.0}s · " +
                                "检查点 ${event.summaryChars} 字符",
                            success = true,
                            activitySummary = if (event.fallback) {
                                "模型压缩不可用，已使用本地兜底检查点继续"
                            } else {
                                "上下文压缩完成，任务已继续"
                            },
                        ).also {
                            _state.update {
                                it.copy(
                                    agentCompacting = false,
                                    agentCompactionStartedAt = null,
                                    agentCompactionBeforeTokens = 0,
                                    agentCompactionLimit = 0,
                                )
                            }
                            persistCurrentConversation()
                        }
                        is AgentEvent.ToolStarted -> {
                            startToolActivity(
                                assistantId,
                                event.name,
                                event.summary,
                            )
                            _state.update {
                                it.copy(
                                    agentStatus = "正在执行工具 · ${event.name}",
                                    agentRequestStartedAt = null,
                                    agentRetry = null,
                                )
                            }
                        }
                        is AgentEvent.ToolFinished -> {
                            finishToolActivity(
                                assistantId,
                                event.name,
                                event.summary,
                                event.success,
                            )
                            _state.update {
                                it.copy(
                                    agentStatus = if (event.success) {
                                        "${event.name} 已完成"
                                    } else {
                                        "${event.name} 执行失败"
                                    },
                                )
                            }
                            persistCurrentConversation()
                        }
                        is AgentEvent.State -> {
                            updateRunningActivitySummary(assistantId, "模型请求", event.text)
                            _state.update { it.copy(agentStatus = event.text) }
                        }
                        is AgentEvent.Stopped -> {
                            finishToolActivity(
                                id = assistantId,
                                name = "模型请求",
                                summary = event.message,
                                success = false,
                                activitySummary = "模型请求已停止",
                            )
                            if (_state.value.agentCompacting) {
                                finishToolActivity(
                                    id = assistantId,
                                    name = "上下文压缩",
                                    summary = event.message,
                                    success = false,
                                    activitySummary = "上下文压缩已停止",
                                )
                            }
                            appendChat(assistantId, "\n\n■ ${event.message}\n")
                            _state.update {
                                it.copy(
                                    notice = event.message,
                                    agentRequestStartedAt = null,
                                    agentRetry = null,
                                    agentCompacting = false,
                                    agentCompactionStartedAt = null,
                                )
                            }
                        }
                        is AgentEvent.Failed -> {
                            finishToolActivity(
                                id = assistantId,
                                name = "模型请求",
                                summary = event.message,
                                success = false,
                                activitySummary = "模型请求失败",
                            )
                            if (_state.value.agentCompacting) {
                                finishToolActivity(
                                    id = assistantId,
                                    name = "上下文压缩",
                                    summary = event.message,
                                    success = false,
                                    activitySummary = "上下文压缩失败",
                                )
                            }
                            _state.update { current ->
                                current.copy(
                                    chat = current.chat.map { entry ->
                                        if (entry.id == assistantId) {
                                            entry.copy(
                                                error = event.message,
                                                retryCount = event.retryCount,
                                            )
                                        } else {
                                            entry
                                        }
                                    },
                                )
                            }
                            _state.update {
                                it.copy(
                                    notice = event.message,
                                    agentRequestStartedAt = null,
                                    agentRetry = null,
                                    agentCompacting = false,
                                    agentCompactionStartedAt = null,
                                )
                            }
                        }
                        AgentEvent.Completed -> {
                            _state.update {
                                it.copy(
                                    agentRequestStartedAt = null,
                                    agentRetry = null,
                                )
                            }
                        }
                    }
                }
            } finally {
                agentJob = null
                _state.update {
                    it.copy(
                        agentRunning = false,
                        agentStatus = null,
                        agentRequestStartedAt = null,
                        agentRetry = null,
                        agentCompacting = false,
                        agentCompactionStartedAt = null,
                        agentCompactionBeforeTokens = 0,
                        agentCompactionLimit = 0,
                        chat = it.chat.map { entry ->
                            if (entry.id == assistantId) {
                                entry.copy(
                                    pending = false,
                                    content = entry.content.ifBlank {
                                        if (entry.error == null) {
                                            "任务已结束，没有返回文本。"
                                        } else {
                                            ""
                                        }
                                    },
                                )
                            } else {
                                entry
                            }
                        },
                    )
                }
                persistCurrentConversation()
                refreshGit()
            }
        }
    }

    fun stopAgent() {
        if (!_state.value.agentRunning) return
        auditLog.append("agent", "请求停止任务", "用户点击停止")
        _state.update { it.copy(agentStatus = "正在停止任务…") }
        pendingApprovalDeferred?.complete(false)
        pendingApprovalDeferred = null
        aiClient.cancel()
        commandRunner.cancel()
        agentJob?.cancel()
    }

    fun runMockAgent() {
        val settings = _state.value.settings.copy(
            baseUrl = "http://127.0.0.1:37900/v1",
            model = "mock-coding-model",
            apiKey = "local-test-key",
        )
        _state.update {
            it.copy(
                settings = settings,
                tab = AppTab.AGENT,
                chat = emptyList(),
            )
        }
        agentEngine.reset()
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.save(settings)
            refreshModels()
            sendChat("请运行 hello.py 并确认输出。")
        }
    }

    fun clearChat() {
        pendingApprovalDeferred?.complete(false)
        pendingApprovalDeferred = null
        newConversation()
    }

    fun consumeNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun appendChatDelta(id: Long, text: String) {
        if (text.isEmpty()) return
        _state.update { current ->
            current.copy(
                chat = current.chat.map { entry ->
                    if (entry.id == id) {
                        entry.copy(content = entry.content + text)
                    } else {
                        entry
                    }
                },
            )
        }
    }

    private fun appendChat(id: Long, text: String) {
        if (text.isBlank()) return
        val cleaned = text.trim()
        _state.update { current ->
            current.copy(
                chat = current.chat.map { entry ->
                    if (entry.id == id) {
                        val separator = if (
                            entry.content.isBlank() || entry.content.endsWith("\n\n")
                        ) {
                            ""
                        } else {
                            "\n\n"
                        }
                        entry.copy(content = entry.content + separator + cleaned)
                    } else {
                        entry
                    }
                },
            )
        }
    }

    private fun addUsage(id: Long, usage: TokenUsage) {
        _state.update { current ->
            current.copy(
                chat = current.chat.map { entry ->
                    if (entry.id == id) {
                        entry.copy(
                            usage = entry.usage?.plus(usage) ?: usage,
                            lastRequestUsage = usage,
                            lastRequestAt = System.currentTimeMillis(),
                        )
                    } else {
                        entry
                    }
                },
            )
        }
    }

    private fun startToolActivity(id: Long, name: String, summary: String) {
        _state.update { current ->
            current.copy(
                chat = current.chat.map { entry ->
                    if (entry.id == id) {
                        entry.copy(
                            activities = entry.activities + ToolActivity(
                                name = name,
                                summary = summary,
                                startedAt = System.currentTimeMillis(),
                            ),
                        )
                    } else {
                        entry
                    }
                },
            )
        }
    }

    private fun addActivity(id: Long, activity: ToolActivity) {
        _state.update { current ->
            current.copy(
                chat = current.chat.map { entry ->
                    if (entry.id == id) {
                        entry.copy(activities = entry.activities + activity)
                    } else {
                        entry
                    }
                },
            )
        }
    }

    private fun updatePlan(id: Long, plan: List<AgentPlanStep>) {
        _state.update { current ->
            current.copy(
                chat = current.chat.map { entry ->
                    if (entry.id == id) entry.copy(plan = plan) else entry
                },
            )
        }
    }

    private fun updateRunningActivitySummary(
        id: Long,
        name: String,
        summary: String,
    ) {
        _state.update { current ->
            current.copy(
                chat = current.chat.map { entry ->
                    if (entry.id != id) return@map entry
                    val index = entry.activities.indexOfLast {
                        it.name == name && it.success == null
                    }
                    if (index < 0) return@map entry
                    entry.copy(
                        activities = entry.activities.mapIndexed { activityIndex, activity ->
                            if (activityIndex == index) {
                                activity.copy(summary = summary)
                            } else {
                                activity
                            }
                        },
                    )
                },
            )
        }
    }

    private fun finishToolActivity(
        id: Long,
        name: String,
        summary: String,
        success: Boolean,
        activitySummary: String? = null,
    ) {
        _state.update { current ->
            current.copy(
                chat = current.chat.map { entry ->
                    if (entry.id != id) return@map entry
                    val index = entry.activities.indexOfLast {
                        it.name == name && it.success == null
                    }
                    if (index < 0) return@map entry
                    entry.copy(
                        activities = entry.activities.mapIndexed { activityIndex, activity ->
                            if (activityIndex == index) {
                                val finishedAt = System.currentTimeMillis()
                                activity.copy(
                                    summary = activitySummary ?: activity.summary,
                                    result = summary,
                                    success = success,
                                    durationMs = activity.startedAt
                                        ?.let { (finishedAt - it).coerceAtLeast(0) },
                                )
                            } else {
                                activity
                            }
                        },
                    )
                },
            )
        }
    }

    private fun appendTerminal(text: String) {
        _state.update { it.copy(terminalOutput = limitTerminal(it.terminalOutput + text)) }
    }

    private fun limitTerminal(text: String): String =
        if (text.length <= MAX_TERMINAL_CHARS) text else text.takeLast(MAX_TERMINAL_CHARS)

    private fun formatKTokens(tokens: Int): String =
        when {
            tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000f)
            tokens >= 1_000 -> String.format("%.1fk", tokens / 1_000f)
            else -> tokens.toString()
        }

    private fun ModelProfile.toSettings(workspacePath: String): AiSettings = AiSettings(
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
        contextWindow = contextWindow,
        workspacePath = workspacePath,
        reasoningEffort = reasoningEffort,
    )

    override fun onCleared() {
        aiClient.cancel()
        commandRunner.cancel()
        agentJob?.cancel()
        super.onCleared()
    }

    private fun hasStorageAccess(): Boolean =
        if (
            appContext.applicationInfo.targetSdkVersion <= Build.VERSION_CODES.P
        ) {
            (
                Environment.isExternalStorageManager() ||
                    (
                        appContext.checkSelfPermission(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                        ) == PackageManager.PERMISSION_GRANTED &&
                            appContext.checkSelfPermission(
                                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            ) == PackageManager.PERMISSION_GRANTED
                        )
                ) &&
                canWritePublicStorage()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager() && canWritePublicStorage()
        } else {
            appContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED &&
                appContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED &&
                canWritePublicStorage()
        }

    private fun canWritePublicStorage(): Boolean = runCatching {
        val directory = Environment.getExternalStorageDirectory()
        require(directory.isDirectory) { "共享存储不可用" }
        val probe = File(directory, ".pocketagent-write-probe-${System.nanoTime()}")
        try {
            check(probe.createNewFile()) { "无法创建访问探测文件" }
            probe.writeText("ok")
            check(probe.readText() == "ok") { "无法读取访问探测文件" }
        } finally {
            probe.delete()
        }
        true
    }.getOrDefault(false)

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun hasNotificationPermission(): Boolean =
        appContext.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    appContext.applicationInfo.targetSdkVersion < Build.VERSION_CODES.TIRAMISU ||
                    appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                )

    companion object {
        private const val EDITOR_LINT_TAG = "PocketEditorLint"
        private const val MAX_TERMINAL_CHARS = 180_000
        private const val APPROVAL_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
