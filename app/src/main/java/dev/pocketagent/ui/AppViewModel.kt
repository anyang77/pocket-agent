package dev.pocketagent.ui

import android.net.Uri
import dev.pocketagent.data.ChatAttachment
import dev.pocketagent.data.ChatAttachmentStore
import dev.pocketagent.data.AgentComposerStore
import dev.pocketagent.data.StoredComposer
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
import dev.pocketagent.agent.AgentChoice
import dev.pocketagent.agent.UserQuestion
import dev.pocketagent.agent.AiClient
import dev.pocketagent.agent.PermissionDecision
import dev.pocketagent.agent.PermissionKind
import dev.pocketagent.agent.PermissionMode
import dev.pocketagent.agent.PermissionRequest
import dev.pocketagent.agent.TokenEstimator
import dev.pocketagent.agent.TokenUsage
import dev.pocketagent.agent.ToolActivity
import dev.pocketagent.agent.retainLastMeasuredSpeed
import dev.pocketagent.data.AiSettings
import dev.pocketagent.data.AppearanceRepository
import dev.pocketagent.data.AgentGoal
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
import dev.pocketagent.data.GoalPhase
import dev.pocketagent.data.GoalRepository
import dev.pocketagent.data.ModelProfile
import dev.pocketagent.data.ManagedRepositoryStore
import dev.pocketagent.data.OnboardingRepository
import dev.pocketagent.data.PermissionSettingsRepository
import dev.pocketagent.data.SettingsRepository
import dev.pocketagent.data.WorkspaceRepository
import dev.pocketagent.data.StoredConversation
import dev.pocketagent.data.StoredMessage
import dev.pocketagent.runtime.CommandRunner
import dev.pocketagent.runtime.AgentForegroundService
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.pocketagent.extensions.*
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
    private val appearanceRepository = AppearanceRepository(application)
    private val permissionSettingsRepository = PermissionSettingsRepository(application)
    private val conversationStore = ConversationStore(application)
    private val attachmentStore = ChatAttachmentStore(application)
    private val composerStore = AgentComposerStore(application)
    private var composerSaveJob: Job? = null
    private var mentionSearchJob: Job? = null
    private var attachmentJobs = 0
    private val currentConversationRepository = CurrentConversationRepository(application)
    private val onboardingRepository = OnboardingRepository(application)
    private val goalRepository = GoalRepository(application)
    private val aiClient = AiClient()
    private val extensionRepository = ExtensionRepository(application)
    private val skillRepository = SkillRepository(application) { workspace.root }
    private val onlineCatalog = OnlineCatalog(File(application.cacheDir, "extension-catalog"))
    private val catalogPrefs = application.getSharedPreferences("codemate_catalog", android.content.Context.MODE_PRIVATE)
    private var catalogJob: kotlinx.coroutines.Job? = null
    private var catalogInstallJob: kotlinx.coroutines.Job? = null
    private val skillCatalogCache = java.util.concurrent.ConcurrentHashMap<String, List<CatalogSkill>>()
    private var catalogRevision = 0L
    private var catalogServerJob: kotlinx.coroutines.Job? = null
    private var catalogDocumentJob: kotlinx.coroutines.Job? = null

    private val subagentManager = SubagentManager(viewModelScope, extensionRepository::settings) { client, prompt, settings, onEvent ->
        // Each child owns its command runner and model client; cancelling it cannot cancel the parent.
        AgentEngine(workspace, CommandRunner(), systemAccess, permissions = { permissionSettingsRepository.load() },
            aiClient = client, auditLog = auditLog, readOnlySubagent = true).run(
                userText = prompt, settings = settings,
                authorize = { PermissionDecision(false, "子代理仅进行只读检查") }, onEvent = onEvent)
    }
    private val agentExtensions = AgentExtensions(extensionRepository, skillRepository, subagentManager,
        internalRuntime, { workspace.root }, auditLog)
    private val agentEngine = AgentEngine(
        workspace = workspace,
        commandRunner = commandRunner,
        systemAccess = systemAccess,
        rootAvailable = { autoUseRoot },
        aiClient = aiClient,
        auditLog = auditLog,
        extensions = agentExtensions,
    )
    private var pendingApprovalDeferred: CompletableDeferred<Boolean>? = null
    private var pendingChoiceDeferred: CompletableDeferred<AgentChoice?>? = null
    private var agentJob: Job? = null
    private var editorLintJob: Job? = null
    private var githubLoginJob: Job? = null
    private val githubAuthClient = GitHubDeviceAuthClient()
    private val nextId = AtomicLong(1)
    private val nextConversationId = AtomicLong(1)
    private val conversationCache = linkedMapOf<Long, StoredConversation>()
    private var currentConversation: StoredConversation? = null
    private var activeAssistantId: Long? = null
    private var settingsLoaded = false
    @Volatile
    private var workspaceNeedsConfiguration = true
    private val workspaceConfigurationMutex = Mutex()

    private val _state = MutableStateFlow(
        AppUiState(
            welcomeAccepted = onboardingRepository.isWelcomeAccepted(),
            welcomeVisible = false,
            themePreference = appearanceRepository.loadThemePreference(),
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
            extensionSettings = extensionRepository.settings(),
            mcpServers = extensionRepository.servers(),
            agentGoal = goalRepository.load(),
            chat = emptyList(),
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init {
        auditLog.append("app", "应用启动", "CodeMate 启动")
        viewModelScope.launch { agentExtensions.servers.collect { servers -> _state.update { it.copy(mcpStates = servers) } } }
        viewModelScope.launch { subagentManager.tasks.collect { tasks -> _state.update { it.copy(subagentTasks = tasks) } } }
        refreshExtensions()
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
            viewModelScope.launch(Dispatchers.IO) {
                val referenced = loaded.conversations.flatMap { conversation ->
                    conversation.messages.flatMap { it.attachments.map { attachment -> attachment.path } } +
                        conversation.agentMessages.flatMap { it.imagePaths }
                }.toSet() + composerStore.referencedPaths()
                attachmentStore.pruneUnused(referenced)
            }
            val settings = loaded.settings
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
            settingsLoaded = true
            retryWorkspaceConfiguration()
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
            if (_state.value.welcomeAccepted) {
                if (settings.apiKey.isNotBlank()) refreshModels()
                installInternalRuntime()
                refreshRootAccess()
            }
            refreshFiles()
        }
    }

    fun refreshExtensions() {
        viewModelScope.launch {
            _state.update { it.copy(extensionsLoading = true) }
            try {
                val (skills, versions) = withContext(Dispatchers.IO) { skillRepository.list() to skillRepository.onlineVersions() }
                _state.update { it.copy(skills = skills, mcpServers = extensionRepository.servers(),
                    extensionSettings = extensionRepository.settings(), onlineCatalog = it.onlineCatalog.copy(installed = versions)) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _state.update { it.copy(notice = error.message ?: "扩展加载失败") } }
            finally { _state.update { it.copy(extensionsLoading = false) } }
        }
    }

    fun saveExtensionSettings(settings: ExtensionSettings) {
        extensionRepository.saveSettings(settings)
        if (!settings.subagentsEnabled) subagentManager.stopAll()
        _state.update { it.copy(extensionSettings = settings) }
    }

    fun setSkillEnabled(id: String, enabled: Boolean) {
        skillRepository.setEnabled(id, enabled)
        refreshExtensions()
    }

    fun saveSkill(id: String?, name: String, description: String, content: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { skillRepository.save(name, description, content, id) }
                refreshExtensions()
                _state.update { it.copy(notice = "技能已保存") }
            } catch (error: Exception) { _state.update { it.copy(notice = error.message ?: "保存失败") } }
        }
    }

    fun removeSkill(id: String) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { skillRepository.remove(id) } }
                .onFailure { error -> _state.update { it.copy(notice = error.message ?: "删除失败") } }
            refreshExtensions()
        }
    }

    fun importMcpConfig(json: String) {
        if (_state.value.agentRunning) { _state.update { it.copy(notice = "请在当前任务结束后修改 MCP 配置") }; return }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { extensionRepository.importServers(json) }
                agentExtensions.close()
                refreshExtensions()
                _state.update { it.copy(notice = "MCP 配置已导入") }
            } catch (error: Exception) { _state.update { it.copy(notice = error.message ?: "配置无效") } }
        }
    }

    fun saveMcpServer(config: McpServerConfig) {
        if (_state.value.agentRunning) { _state.update { it.copy(notice = "请在当前任务结束后修改 MCP 配置") }; return }
        runCatching { extensionRepository.saveServer(config); agentExtensions.disconnect(config.id); refreshExtensions() }
            .onFailure { error -> _state.update { it.copy(notice = error.message ?: "配置无效") } }
    }

    fun removeMcpServer(id: String) {
        if (_state.value.agentRunning) { _state.update { it.copy(notice = "请在当前任务结束后修改 MCP 配置") }; return }
        agentExtensions.disconnect(id)
        extensionRepository.removeServer(id)
        refreshExtensions()
    }

    fun testMcpServer(id: String) {
        if (_state.value.agentRunning) { _state.update { it.copy(notice = "任务正在使用扩展，请稍后测试连接") }; return }
        val config = extensionRepository.servers().firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            try { agentExtensions.connect(config) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Error is shown on the server row without revealing secrets. */ }
        }
    }

    fun browseCatalog(kind: String, source: String, query: String, more: Boolean = false, refresh: Boolean = false) {
        val requestRevision = ++catalogRevision
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            val previous = _state.value.onlineCatalog
            val selected = if (kind == "skills") runCatching { normalizeSkillRepository(source.ifBlank { CatalogSources.skills.first() }) }.getOrElse {
                _state.update { state -> state.copy(onlineCatalog = previous.copy(error = it.message)) }; return@launch
            } else source.ifBlank { CatalogSources.chineseMcp }
            val sources = (CatalogSources.skills + catalogPrefs.getStringSet("sources", emptySet()).orEmpty() + listOfNotNull(selected.takeIf { kind == "skills" && it.isNotEmpty() })).distinct()
            _state.update { it.copy(onlineCatalog = previous.copy(kind = kind, source = selected, query = query, loading = true, error = null,
                skills = if (previous.source == selected && kind == "skills") previous.skills else emptyList(),
                servers = if (more) previous.servers else emptyList(), cursor = if (more) previous.cursor else "", sources = sources)) }
            try {
                if (kind == "skills") {
                    val items = withContext(Dispatchers.IO) { skillCatalogCache[selected] ?: onlineCatalog.skills(selected, refresh).also { skillCatalogCache[selected] = it } }
                    catalogPrefs.edit().putStringSet("sources", sources.toSet()).apply()
                    val versions = withContext(Dispatchers.IO) { skillRepository.onlineVersions() }
                    _state.update { it.copy(onlineCatalog = it.onlineCatalog.copy(skills = items, installed = versions)) }
                } else {
                    val page = withContext(Dispatchers.IO) {
                        if (selected == CatalogSources.chineseMcp) {
                            val all = onlineCatalog.chineseServers(refresh)
                            all.copy(items = all.items.filter { server -> listOf(server.title, server.description, server.category, server.notes).any { it.contains(query.trim(), true) } })
                        } else onlineCatalog.servers(query, if (more) previous.cursor else "")
                    }
                    _state.update { it.copy(onlineCatalog = it.onlineCatalog.copy(servers = (if (more) previous.servers + page.items else page.items).distinctBy { server -> server.name },
                        cursor = page.cursor.takeUnless { cursor -> more && cursor == previous.cursor }.orEmpty())) }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _state.update { it.copy(onlineCatalog = it.onlineCatalog.copy(error = error.message ?: "在线仓库加载失败")) } }
            finally { if (catalogRevision == requestRevision) _state.update { it.copy(onlineCatalog = it.onlineCatalog.copy(loading = false)) } }
        }
    }

    fun refreshCatalog() {
        val catalog = _state.value.onlineCatalog
        skillCatalogCache.remove(catalog.source)
        browseCatalog(catalog.kind, catalog.source, catalog.query, refresh = true)
    }

    fun inspectCatalogSkill(item: CatalogSkill?) {
        val requestRevision = ++catalogRevision
        catalogJob?.cancel()
        _state.update { it.copy(onlineCatalog = it.onlineCatalog.copy(selectedSkill = item, detail = null, error = null, loading = item != null)) }
        if (item == null) return
        catalogJob = viewModelScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) { onlineCatalog.detail(item) }
                _state.update { it.copy(onlineCatalog = it.onlineCatalog.copy(detail = detail)) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _state.update { it.copy(onlineCatalog = it.onlineCatalog.copy(error = error.message ?: "技能详情加载失败")) } }
            finally { if (catalogRevision == requestRevision) _state.update { it.copy(onlineCatalog = it.onlineCatalog.copy(loading = false)) } }
        }
    }

    fun installCatalogSkill(item: CatalogSkill) {
        if (catalogInstallJob?.isActive == true || _state.value.agentRunning) return
        catalogInstallJob = viewModelScope.launch {
            _state.update { it.copy(onlineCatalog = it.onlineCatalog.copy(busy = item.key, error = null)) }
            try {
                val versions = withContext(Dispatchers.IO) { skillRepository.installOnline(item, onlineCatalog); skillRepository.onlineVersions() }
                refreshExtensions()
                _state.update { it.copy(notice = "技能已安装", onlineCatalog = it.onlineCatalog.copy(installed = versions)) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _state.update { it.copy(onlineCatalog = it.onlineCatalog.copy(error = error.message ?: "安装失败")) } }
            finally { _state.update { it.copy(onlineCatalog = it.onlineCatalog.copy(busy = null)) } }
        }
    }

    fun inspectCatalogServer(item: CatalogMcp?) {
        catalogServerJob?.cancel()
        _state.update { it.copy(onlineCatalog = it.onlineCatalog.copy(selectedServer = item, serverError = null, serverLoading = item?.name?.startsWith("zh:") == true)) }
        if (item == null || !item.name.startsWith("zh:")) return
        catalogServerJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { onlineCatalog.resolveServer(item) }
                _state.update { it.copy(onlineCatalog = it.onlineCatalog.copy(selectedServer = result)) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _state.update { it.copy(onlineCatalog = it.onlineCatalog.copy(serverError = error.message ?: "配置查询失败")) } }
            finally { _state.update { it.copy(onlineCatalog = it.onlineCatalog.copy(serverLoading = false)) } }
        }
    }

    fun readCatalogDocument(repo: String?) {
        catalogDocumentJob?.cancel()
        _state.update { it.copy(onlineCatalog = it.onlineCatalog.copy(document = null, documentRepo = repo, documentLoading = repo != null, documentError = null)) }
        if (repo == null) return
        catalogDocumentJob = viewModelScope.launch {
            try {
                val document = withContext(Dispatchers.IO) { onlineCatalog.document(repo) }
                _state.update { it.copy(onlineCatalog = it.onlineCatalog.copy(document = document)) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _state.update { it.copy(onlineCatalog = it.onlineCatalog.copy(documentError = error.message ?: "文档读取失败")) } }
            finally { _state.update { it.copy(onlineCatalog = it.onlineCatalog.copy(documentLoading = false)) } }
        }
    }

    fun stopSubagent(id: String) { subagentManager.stop(id) }

    private fun saveComposer() {
        val current = _state.value
        val id = current.currentConversationId ?: return
        val revision = composerStore.reserveRevision(id)
        // Capture the snapshot; a superseded IO write must never overwrite a later draft.
        composerSaveJob?.cancel()
        composerSaveJob = viewModelScope.launch {
            delay(150)
            withContext(Dispatchers.IO) {
                composerStore.save(id, StoredComposer(current.agentInputDraft,
                    current.agentAttachments, current.queuedPrompts), revision)
            }
        }
    }

    private fun flushComposer() {
        composerSaveJob?.cancel()
        val current = _state.value
        current.currentConversationId?.let { id ->
            composerStore.save(id, StoredComposer(current.agentInputDraft,
                current.agentAttachments, current.queuedPrompts), composerStore.reserveRevision(id))
        }
    }

    fun updateAgentInputDraft(text: String) {
        _state.update { it.copy(agentInputDraft = text) }
        saveComposer()
    }

    fun searchMentionFiles(query: String?) {
        mentionSearchJob?.cancel()
        if (query == null) {
            _state.update { it.copy(mentionFiles = emptyList(), mentionFilesLoading = false) }
            return
        }
        val root = workspace.root.path
        _state.update { it.copy(mentionFilesLoading = true) }
        mentionSearchJob = viewModelScope.launch {
            delay(180)
            try {
                val results = workspace.searchMentionFiles(query)
                if (workspace.root.path == root) _state.update {
                    it.copy(mentionFiles = results, mentionFilesLoading = false)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                _state.update { it.copy(mentionFiles = emptyList(), mentionFilesLoading = false,
                    notice = error.message ?: "搜索文件失败") }
            }
        }
    }

    fun addAgentImages(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.agentAttachmentsLoading) return
        val conversationId = _state.value.currentConversationId
        val available = (4 - _state.value.agentAttachments.count { it.isImage }).coerceAtLeast(0)
        if (available == 0) {
            _state.update { it.copy(notice = "每条消息最多添加 4 张图片") }; return
        }
        attachmentJobs++
        _state.update { it.copy(agentAttachmentsLoading = true) }
        viewModelScope.launch {
            try {
                for (uri in uris.take(available)) {
                    val attachment = withContext(Dispatchers.IO) { attachmentStore.importImage(uri) }
                    if (_state.value.currentConversationId == conversationId) {
                        _state.update { it.copy(agentAttachments = it.agentAttachments + attachment) }
                        saveComposer()
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                _state.update { it.copy(notice = error.message ?: "图片添加失败") }
            } finally {
                attachmentJobs--
                _state.update { it.copy(agentAttachmentsLoading = attachmentJobs > 0) }
            }
        }
    }

    fun addAgentFile(path: String) {
        val current = _state.value
        if (current.agentAttachmentsLoading) return
        if (current.agentAttachments.any { it.sourcePath == path }) return
        if (current.agentAttachments.count { !it.isImage } >= 8) {
            _state.update { it.copy(notice = "每条消息最多引用 8 个文件") }; return
        }
        val root = workspace.root.path
        attachmentJobs++
        _state.update { it.copy(agentAttachmentsLoading = true) }
        viewModelScope.launch {
            try {
                val content = workspace.readText(path, maxChars = 48_001)
                val snapshot = withContext(Dispatchers.IO) {
                    attachmentStore.snapshotFile(path.substringAfterLast('/'), path, content.take(48_000) +
                        if (content.length > 48_000) "\n[内容过长，仅附前 48000 个字符；需要后续内容请读取原文件]" else "")
                }
                if (_state.value.currentConversationId == current.currentConversationId && workspace.root.path == root) {
                    _state.update { it.copy(agentAttachments = it.agentAttachments + snapshot) }
                    saveComposer()
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _state.update { it.copy(notice = error.message ?: "文件添加失败") } }
            finally {
                attachmentJobs--
                _state.update { it.copy(agentAttachmentsLoading = attachmentJobs > 0) }
            }
        }
    }

    fun removeAgentAttachment(id: String) {
        _state.update { it.copy(agentAttachments = it.agentAttachments.filterNot { item -> item.id == id }) }
        saveComposer()
    }

    fun removeQueuedPrompt(id: Long) {
        _state.update { it.copy(queuedPrompts = it.queuedPrompts.filterNot { item -> item.id == id }) }
        saveComposer()
    }

    fun editQueuedPrompt(id: Long, text: String) {
        _state.update { state ->
            state.copy(queuedPrompts = state.queuedPrompts.map { item ->
                if (item.id == id && !item.steering && (text.isNotBlank() || item.attachments.isNotEmpty())) {
                    item.copy(text = text)
                } else item
            })
        }
        saveComposer()
    }

    fun steerQueuedPrompt(id: Long) {
        if (!_state.value.agentRunning) return
        _state.update { state -> state.copy(queuedPrompts = state.queuedPrompts.map {
            if (it.id == id) it.copy(steering = true) else it
        }) }
        saveComposer()
    }

    fun pausePromptQueue() {
        _state.update { it.copy(queuePaused = true) }
        saveComposer()
    }

    fun resumePromptQueue() {
        if (_state.value.settings.apiKey.isBlank()) {
            _state.update { it.copy(notice = "请先在设置里填写 API Key") }; return
        }
        _state.update { it.copy(queuePaused = false) }
        if (!_state.value.agentRunning) dispatchQueuedPrompt()
        saveComposer()
    }

    private fun dispatchQueuedPrompt() {
        val current = _state.value
        if (current.agentRunning || current.queuePaused) return
        val next = current.queuedPrompts.firstOrNull() ?: return
        if (current.settings.apiKey.isBlank()) {
            _state.update { it.copy(queuePaused = true, notice = "请先在设置里填写 API Key") }; return
        }
        _state.update { it.copy(queuedPrompts = it.queuedPrompts.drop(1)) }
        startChat(next.text, next.attachments, fromQueue = true)
    }

    fun newConversation() {
        if (_state.value.agentRunning) {
            _state.update { it.copy(notice = "请等待当前任务结束") }
            return
        }
        flushComposer()
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
        subagentManager.reset()
        _state.update {
            it.copy(
                chat = listOf(welcome),
                agentInputDraft = "",
                agentAttachments = emptyList(), queuedPrompts = emptyList(), queuePaused = false,
                currentConversationId = id,
                agentContextTokens = 0,
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
        flushComposer()
        persistCurrentConversation()
        loadConversation(conversation)
    }

    fun deleteConversation(id: Long) {
        if (_state.value.agentRunning && currentConversation?.id == id) {
            _state.update { it.copy(notice = "不能删除正在执行的对话") }
            return
        }
        if (_state.value.currentConversationId == id) {
            composerSaveJob?.cancel()
            _state.update { it.copy(currentConversationId = null) }
        }
        composerStore.delete(id)
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
        subagentManager.reset()
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
                goal = message.goal,
                phase = runCatching { AgentTurnPhase.valueOf(message.phase) }
                    .getOrElse {
                        if (role == ChatRole.ASSISTANT) {
                            AgentTurnPhase.COMPLETED
                        } else {
                            AgentTurnPhase.IDLE
                        }
                    },
                reasoning = message.reasoning,
                attachments = message.attachments,
            )
        }
        val history = chat.mapNotNull { message ->
            when (message.role) {
                ChatRole.USER -> AgentMessage("user", runCatching {
                    attachmentStore.modelText(message.content, message.attachments)
                }.getOrDefault(message.content), imagePaths = message.attachments.filter { it.isImage }.map { it.path })
                ChatRole.ASSISTANT -> AgentMessage("assistant", message.content)
                else -> null
            }
        }
        if (conversation.agentMessages.isNotEmpty()) {
            agentEngine.loadConversation(conversation.agentMessages)
        } else {
            agentEngine.loadHistory(history)
        }
        val composer = composerStore.load(conversation.id)
        nextId.updateAndGet { maxOf(it, (composer.queue.maxOfOrNull { item -> item.id } ?: 0L) + 1) }
        _state.update {
            it.copy(
                chat = chat,
                agentInputDraft = composer.text,
                agentAttachments = composer.attachments,
                queuedPrompts = composer.queue,
                queuePaused = composer.queue.isNotEmpty(),
                currentConversationId = conversation.id,
                agentContextTokens = 0,
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

    private fun persistCurrentConversation(history: List<AgentMessage> = agentEngine.snapshotHistory()) {
        val conversation = currentConversation ?: return
        val chat = _state.value.chat
            .filter { it.role != ChatRole.SYSTEM }
            .filter { it.content.isNotBlank() || it.error != null || it.attachments.isNotEmpty() }
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
            agentMessages = history,
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
        goal = goal,
        phase = phase.name,
        reasoning = reasoning,
        attachments = attachments,
    )

    private data class LoadedState(
        val settings: AiSettings,
        val modelProfiles: List<ModelProfile>,
        val activeProfileId: String,
        val permissions: AgentPermissions,
        val conversations: List<StoredConversation>,
    )

    fun setThemePreference(preference: dev.pocketagent.ui.theme.ThemePreference) {
        appearanceRepository.saveThemePreference(preference)
        _state.update { it.copy(themePreference = preference) }
    }

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
        retryWorkspaceConfiguration()
    }

    private fun retryWorkspaceConfiguration() {
        if (!_state.value.welcomeAccepted) return
        if (!settingsLoaded || !workspaceNeedsConfiguration) return
        if (!_state.value.storageAccessGranted && !autoUseRoot) return
        viewModelScope.launch {
            workspaceConfigurationMutex.withLock {
                if (!workspaceNeedsConfiguration) return@withLock
                runCatching {
                    workspace.configure(_state.value.settings.workspacePath)
                    workspace.ensureWorkspace()
                }.onSuccess {
                    workspaceNeedsConfiguration = false
                    _state.update { it.copy(workspacePath = workspace.displayRoot) }
                    refreshFiles()
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    auditLog.append("workspace", "工作区初始化失败", error.message.orEmpty())
                    _state.update { it.copy(notice = error.message ?: "无法打开工作区") }
                }
            }
        }
    }

    fun completeOnboarding() {
        onboardingRepository.complete()
        _state.update { it.copy(onboardingCompleted = true, tab = AppTab.FILES) }
        auditLog.append("app", "完成首次引导", "权限、模型和环境初始化完成")
    }

    fun acceptWelcome() {
        _state.update { it.copy(welcomeVisible = false) }
        if (_state.value.welcomeAccepted) return
        onboardingRepository.acceptWelcome()
        _state.update { it.copy(welcomeAccepted = true) }
        observeRootAccess()
        if (settingsLoaded) {
            retryWorkspaceConfiguration()
            if (_state.value.settings.apiKey.isNotBlank()) refreshModels()
            installInternalRuntime()
        }
    }

    fun showWelcome() {
        _state.update { it.copy(welcomeVisible = true) }
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
            if (workspaceResult.isSuccess) workspaceNeedsConfiguration = false
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
        activeAssistantId?.let { setTurnPhase(it, AgentTurnPhase.TOOL_RUNNING) }
        pendingApprovalDeferred?.complete(true)
    }

    fun denyPending() {
        activeAssistantId?.let { setTurnPhase(it, AgentTurnPhase.THINKING) }
        pendingApprovalDeferred?.complete(false)
    }

    fun selectPendingChoice(choiceId: String) {
        val pending = _state.value.pendingUserChoice ?: return
        val choice = pending.options.firstOrNull { it.id == choiceId } ?: return
        auditLog.append(
            "agent",
            "选择任务方案",
            "${choice.id}: ${choice.title}",
            "selected",
        )
        _state.update { it.copy(pendingUserChoice = null) }
        activeAssistantId?.let { setTurnPhase(it, AgentTurnPhase.THINKING) }
        pendingChoiceDeferred?.complete(choice)
    }

    fun dismissPendingChoice() {
        if (_state.value.pendingUserChoice == null) return
        auditLog.append("agent", "取消任务方案选择", "用户关闭了选择卡片", "cancelled")
        _state.update { it.copy(pendingUserChoice = null) }
        activeAssistantId?.let { setTurnPhase(it, AgentTurnPhase.THINKING) }
        pendingChoiceDeferred?.complete(null)
    }

    private suspend fun requestUserChoice(question: UserQuestion): AgentChoice? {
        if (pendingChoiceDeferred != null) {
            return null
        }
        val deferred = CompletableDeferred<AgentChoice?>()
        pendingChoiceDeferred = deferred
        activeAssistantId?.let { setTurnPhase(it, AgentTurnPhase.WAITING_APPROVAL) }
        _state.update {
            it.copy(
                pendingUserChoice = PendingUserChoice(
                    question = question.question,
                    options = question.options,
                ),
                agentStatus = "等待你选择下一步",
            )
        }
        return try {
            withTimeoutOrNull(USER_CHOICE_TIMEOUT_MS) { deferred.await() }
        } finally {
            pendingChoiceDeferred = null
            _state.update { it.copy(pendingUserChoice = null) }
        }
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
            PermissionKind.ROOT -> {
                if (!_state.value.capabilities.rootGranted) {
                    deny("尚未授予 Root 权限")
                } else if (permissions.mode == PermissionMode.READ_ONLY) {
                    deny("当前是只读模式")
                } else if (permissions.mode == PermissionMode.ASK) {
                    requestApproval(request)
                } else {
                    allow("自动模式 · 明确请求 Root 环境")
                }
            }
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
        activeAssistantId?.let { setTurnPhase(it, AgentTurnPhase.WAITING_APPROVAL) }
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
                    terminalRoot = false,
                    terminalRuntime = current.terminalRuntime.takeUnless {
                        it == dev.pocketagent.runtime.RuntimeKind.ROOT_TERMUX
                    } ?: dev.pocketagent.runtime.RuntimeKind.TERMUX,
                    notice = if (announce && probe.granted) {
                        "Root 已授权，可在终端中输入 su 提权"
                    } else if (announce) {
                        probe.error ?: "Root 不可用"
                    } else {
                        current.notice
                    },
                )
            }
            if (probe.granted) {
                retryWorkspaceConfiguration()
                repairRuntimeOwnership()
            }
            installInternalRuntime()
        }
    }

    fun refreshRootAccess() {
        val wasGranted = _state.value.capabilities.rootGranted
        _state.update { it.copy(capabilities = it.capabilities.copy(rootGranted = false)) }
        observeRootAccess()
        if (wasGranted) {
            auditLog.append("root", "重新检测 Root", "系统恢复后刷新授权状态")
        }
    }

    private fun observeRootAccess() {
        if (!_state.value.welcomeAccepted) return
        if (_state.value.capabilities.rootGranted || _state.value.rootProbing) return
        val knownSu = rootDetector.findSuBinary() ?: return
        if (!knownSu.canExecute()) return
        viewModelScope.launch(Dispatchers.IO) {
            val probe = rootDetector.probe()
            withContext(Dispatchers.Main) {
                autoUseRoot = probe.granted
                _state.update { current ->
                    current.copy(
                        capabilities = current.capabilities.copy(
                            rootGranted = probe.granted,
                            systemReadable = probe.canReadAppData,
                            chrootAvailable = probe.chrootAvailable,
                            mountAvailable = probe.mountAvailable,
                            error = probe.error,
                        ),
                        terminalRoot = false,
                        terminalRuntime = current.terminalRuntime.takeUnless {
                            it == dev.pocketagent.runtime.RuntimeKind.ROOT_TERMUX
                        } ?: dev.pocketagent.runtime.RuntimeKind.TERMUX,
                    )
                }
                if (probe.granted) {
                    retryWorkspaceConfiguration()
                    repairRuntimeOwnership()
                }
            }
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
        if (workspaceNeedsConfiguration) return
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
                workspaceNeedsConfiguration = false
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
            language !in setOf(EditorLintLanguage.SHELL, EditorLintLanguage.BASH, EditorLintLanguage.RUST) &&
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
                val projectRoot = workspace.root.absolutePath
                val sourceRoots = listOf(
                    workspace.root,
                    File(workspace.root, "src/main/java"),
                    File(workspace.root, "src/test/java"),
                ).filter { it.isDirectory }
                    .joinToString(File.pathSeparator) { it.absolutePath }
                    .ifBlank { projectRoot }
                // The embedded editor checks Java syntax without the project's full Gradle
                // classpath. Stop before attribution once parsing succeeds so missing Android
                // or dependency symbols are not reported as errors in an otherwise valid file.
                "javac -encoding UTF-8 -proc:none " +
                    "-XDshould-stop.ifNoError=PARSE " +
                    "-sourcepath ${shellQuote(sourceRoots)} " +
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
            EditorLintLanguage.RUST -> { file ->
                val edition = RustEditorSupport.edition(workspace.resolve(path), workspace.root)
                // Parse the unsaved buffer without resolving Cargo dependencies or child modules.
                // rustfmt is optional; its absence must not appear as an error in the source.
                "if command -v rustfmt >/dev/null 2>&1; then " +
                    "rustfmt --edition $edition --emit stdout --config skip_children=true " +
                    "< ${shellQuote(file)} >/dev/null; fi"
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
                        EditorDiagnosticParser.parse(
                            if (language == EditorLintLanguage.RUST) {
                                result.output.replace("<stdin>", lintFile.absolutePath).replace("stdin:", "${lintFile.absolutePath}:")
                            } else result.output,
                            lintFile.absolutePath,
                        )
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

    fun setTerminalRuntime(kind: dev.pocketagent.runtime.RuntimeKind) {
        val supportedKind = when (kind) {
            dev.pocketagent.runtime.RuntimeKind.SYSTEM_SHELL,
            dev.pocketagent.runtime.RuntimeKind.TERMUX,
            -> kind
            dev.pocketagent.runtime.RuntimeKind.ROOT_TERMUX ->
                dev.pocketagent.runtime.RuntimeKind.TERMUX
        }
        _state.update { it.copy(terminalRuntime = supportedKind, terminalRoot = false) }
        terminalManager.setRuntimeKind(supportedKind)
    }

    fun runTerminal(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank() || _state.value.terminalRunning) return
        val runtimeKind = _state.value.terminalRuntime
        val asRoot = false

        _state.update {
            it.copy(
                terminalRunning = true,
                terminalOutput = it.terminalOutput + "\n❯ [${runtimeKind.name.lowercase()}] $trimmed\n",
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
                useInternalRuntime = runtimeKind != dev.pocketagent.runtime.RuntimeKind.SYSTEM_SHELL,
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

    fun pauseGoal() {
        val current = _state.value.agentGoal ?: return
        val updated = current.copy(phase = GoalPhase.PAUSED)
        goalRepository.save(updated)
        _state.update { it.copy(agentGoal = updated, notice = "目标已暂停") }
        auditLog.append("goal", "暂停目标", updated.objective)
    }

    fun resumeGoal() {
        val current = _state.value.agentGoal ?: return
        val updated = current.copy(phase = GoalPhase.ACTIVE)
        goalRepository.save(updated)
        _state.update { it.copy(agentGoal = updated, notice = "目标已继续") }
        auditLog.append("goal", "继续目标", updated.objective)
        if (!_state.value.agentRunning) sendChat(updated.objective)
    }

    fun clearGoal() {
        val current = _state.value.agentGoal ?: return
        goalRepository.save(null)
        _state.update { it.copy(agentGoal = null, notice = "目标已清除") }
        auditLog.append("goal", "清除目标", current.objective)
    }

    private fun handleGoalCommand(prompt: String): Boolean {
        if (!prompt.startsWith("/goal")) return false
        val argument = prompt.removePrefix("/goal").trim()
        when (argument.lowercase()) {
            "" -> _state.update {
                it.copy(notice = "用法：/goal <目标>，或 /goal pause、resume、clear")
            }
            "pause" -> pauseGoal()
            "resume" -> resumeGoal()
            "clear" -> clearGoal()
            else -> {
                val goal = AgentGoal(argument, GoalPhase.ACTIVE)
                goalRepository.save(goal)
                _state.update { it.copy(agentGoal = goal) }
                auditLog.append("goal", "创建目标", argument)
                if (!_state.value.agentRunning) sendChat(argument)
            }
        }
        return true
    }

    fun sendChat(text: String) {
        if (_state.value.agentAttachmentsLoading) {
            _state.update { it.copy(notice = "附件正在准备，请稍候") }; return
        }
        startChat(text, _state.value.agentAttachments)
    }

    private fun startChat(text: String, attachments: List<ChatAttachment>, fromQueue: Boolean = false) {
        val prompt = text.trim()
        if (attachments.isEmpty() && !fromQueue && !_state.value.agentRunning) when (prompt.lowercase()) {
            "/new" -> {
                _state.update { it.copy(agentInputDraft = "") }
                newConversation()
                return
            }
            "/clear" -> {
                _state.update { it.copy(agentInputDraft = "") }
                clearChat()
                return
            }
        }
        if (attachments.isEmpty() && !fromQueue && !_state.value.agentRunning && handleGoalCommand(prompt)) {
            _state.update { it.copy(agentInputDraft = "") }
            return
        }
        if (prompt.isBlank() && attachments.isEmpty()) return
        if (_state.value.settings.apiKey.isBlank()) {
            _state.update { it.copy(notice = "请先在设置里填写 API Key") }
            return
        }
        if (_state.value.agentRunning) {
            _state.update {
                it.copy(
                    agentInputDraft = "",
                    agentAttachments = emptyList(),
                    queuedPrompts = it.queuedPrompts + QueuedPrompt(nextId.getAndIncrement(), prompt, attachments),
                    notice = "已加入下一轮队列",
                )
            }
            saveComposer()
            return
        }

        subagentManager.reset()
        val user = ChatEntry(nextId.getAndIncrement(), ChatRole.USER, prompt, attachments = attachments)
        var assistantId = nextId.getAndIncrement()
        val assistant = ChatEntry(
            id = assistantId,
            role = ChatRole.ASSISTANT,
            content = "",
            pending = true,
            goal = prompt,
            phase = AgentTurnPhase.THINKING,
        )
        activeAssistantId = assistantId
        _state.update {
            it.copy(
                agentInputDraft = if (fromQueue) it.agentInputDraft else "",
                agentAttachments = if (fromQueue) it.agentAttachments else emptyList(),
                chat = it.chat + user + assistant,
                agentRunning = true,
                agentStatus = "正在分析项目",
                agentRequestStartedAt = System.currentTimeMillis(),
                agentRetry = null,
            )
        }
        runCatching {
            AgentForegroundService.start(appContext)
        }.onFailure { error ->
            _state.update {
                it.copy(notice = error.message ?: "无法启动后台任务服务")
            }
        }
        persistCurrentConversation()

        saveComposer()
        var streamChars = 0
        var streamStartedAt = System.currentTimeMillis()
        var speedUpdatedAt = 0L
        agentJob = viewModelScope.launch {
            try {
                val modelText = withContext(Dispatchers.IO) { attachmentStore.modelText(prompt, attachments) }
                val images = withContext(Dispatchers.IO) { attachmentStore.imagePaths(attachments) }
                agentEngine.run(
                    userText = modelText,
                    imagePaths = images,
                    settings = _state.value.settings,
                    authorize = ::authorizeAgentAction,
                    choose = ::requestUserChoice,
                    takeSteering = {
                        val queued = _state.value.queuedPrompts.filter { it.steering }
                        if (queued.isEmpty()) emptyList() else {
                            val admitted = mutableListOf<Pair<QueuedPrompt, AgentMessage>>()
                            for (item in queued) {
                                try {
                                    val message = withContext(Dispatchers.IO) {
                                        AgentMessage("user", attachmentStore.modelText(item.text, item.attachments),
                                            imagePaths = attachmentStore.imagePaths(item.attachments))
                                    }
                                    admitted += item to message
                                } catch (error: CancellationException) { throw error }
                                catch (error: Exception) {
                                    _state.update { current -> current.copy(
                                        queuedPrompts = current.queuedPrompts.map {
                                            if (it.id == item.id) it.copy(steering = false) else it
                                        }, queuePaused = true, notice = error.message ?: "插话附件不可用") }
                                }
                            }
                            if (admitted.isNotEmpty()) {
                                val previousId = assistantId
                                assistantId = nextId.getAndIncrement()
                                activeAssistantId = assistantId
                                _state.update { current -> current.copy(
                                    queuedPrompts = current.queuedPrompts.filterNot { pending -> admitted.any { it.first.id == pending.id } },
                                    chat = current.chat.map { entry ->
                                        if (entry.id == previousId) entry.copy(pending = false, phase = AgentTurnPhase.COMPLETED) else entry
                                    } + admitted.map { (item, _) ->
                                        ChatEntry(item.id, ChatRole.USER, item.text, attachments = item.attachments)
                                    } + ChatEntry(assistantId, ChatRole.ASSISTANT, "", pending = true,
                                        phase = AgentTurnPhase.THINKING),
                                ) }
                                streamChars = 0
                                streamStartedAt = System.currentTimeMillis()
                                speedUpdatedAt = 0L
                                persistCurrentConversation(agentEngine.snapshotHistory() + admitted.map { it.second })
                                saveComposer()
                            }
                            admitted.map { it.second }
                        }
                    },
                ) { event ->
                    when (event) {
                        is AgentEvent.AssistantText -> {
                            setTurnPhase(assistantId, AgentTurnPhase.RECEIVING)
                            appendChat(assistantId, event.text)
                        }
                        is AgentEvent.AssistantDelta -> {
                            setTurnPhase(assistantId, AgentTurnPhase.RECEIVING)
                            appendChatDelta(assistantId, event.text)
                            streamChars += event.text.length
                            val now = System.currentTimeMillis()
                            // Throttle the readout so it updates ~4x/s instead of per delta.
                            if (now - speedUpdatedAt >= 250) {
                                val speed = TokenEstimator.tokensPerSecond(
                                    streamChars,
                                    now - streamStartedAt,
                                )
                                if (speed != null) {
                                    speedUpdatedAt = now
                                    _state.update {
                                        it.copy(
                                            agentTokensPerSecond = retainLastMeasuredSpeed(
                                                it.agentTokensPerSecond,
                                                speed,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                        is AgentEvent.Usage -> addUsage(assistantId, event.usage)
                        is AgentEvent.ModelRequestStarted -> {
                            setTurnPhase(assistantId, AgentTurnPhase.THINKING)
                            startToolActivity(
                                assistantId,
                                "模型请求",
                                "${event.model} · 第 ${event.round} 轮 · " +
                                    "${event.messageCount} 条上下文",
                            )
                            streamChars = 0
                            streamStartedAt = System.currentTimeMillis()
                            _state.update {
                                it.copy(
                                    agentStatus = "正在等待模型响应 · 第 ${event.round} 轮",
                                    agentRequestStartedAt = System.currentTimeMillis(),
                                    agentRetry = null,
                                    agentContextTokens = event.contextTokens
                                        .takeIf { it > 0 }
                                        ?: it.agentContextTokens,
                                )
                            }
                        }
                        is AgentEvent.StreamOutputReset -> {
                            setTurnPhase(assistantId, AgentTurnPhase.RETRYING)
                            streamChars = 0
                            streamStartedAt = System.currentTimeMillis()
                            trimChatTail(assistantId, event.contentChars)
                            updateRunningActivitySummary(
                                assistantId,
                                "模型请求",
                                "输出连接中断，正在清理未完成内容并重新生成",
                            )
                            _state.update {
                                it.copy(agentStatus = "输出中断，正在重新生成")
                            }
                        }
                        is AgentEvent.RetryScheduled -> {
                            setTurnPhase(assistantId, AgentTurnPhase.RETRYING)
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
                            setTurnPhase(assistantId, AgentTurnPhase.THINKING)
                            finishToolActivity(
                                id = assistantId,
                                name = "模型请求",
                                summary = "响应 ${event.elapsedMs / 1_000.0}s · " +
                                    "${event.contentChars} 字符 · " +
                                    "${event.toolCallCount} 个工具调用",
                                success = true,
                                activitySummary = "第 ${event.round} 轮模型响应已返回",
                            )
                            val measuredSpeed = if (event.completionTokens > 0 &&
                                event.elapsedMs > 0
                            ) {
                                event.completionTokens / (event.elapsedMs / 1_000f)
                            } else {
                                null
                            }
                            _state.update {
                                it.copy(
                                    agentStatus = "模型已响应，正在处理结果",
                                    agentRequestStartedAt = null,
                                    agentRetry = null,
                                    agentTokensPerSecond = retainLastMeasuredSpeed(
                                        it.agentTokensPerSecond,
                                        measuredSpeed,
                                    ),
                                )
                            }
                        }
                        is AgentEvent.ModelReasoningProgress -> {
                            setTurnPhase(assistantId, AgentTurnPhase.THINKING)
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
                        is AgentEvent.ReasoningCompleted -> {
                            appendReasoning(assistantId, event.text)
                        }
                        is AgentEvent.PlanUpdated -> updatePlan(assistantId, event.steps)
                        is AgentEvent.ContextCompactionStarted -> {
                            setTurnPhase(assistantId, AgentTurnPhase.COMPACTING)
                            val percent = (
                                event.beforeTokens.toFloat() /
                                    event.contextLimit.coerceAtLeast(1).toFloat() *
                                    100f
                                ).toInt().coerceIn(0, 100)
                            _state.update {
                                it.copy(
                                    agentStatus = "正在压缩上下文 · $percent%" +
                                        " · ${formatKTokens(event.beforeTokens)}",
                                    agentCompactStatus = "上下文 $percent%，正在整理检查点",
                                )
                            }
                        }
                        is AgentEvent.ContextCompacted -> {
                            _state.update {
                                it.copy(
                                    agentCompactStatus = null,
                                    agentStatus = "上下文压缩完成，继续执行",
                                )
                            }
                            persistCurrentConversation()
                            setTurnPhase(assistantId, AgentTurnPhase.THINKING)
                        }
                        is AgentEvent.ToolStarted -> {
                            setTurnPhase(assistantId, AgentTurnPhase.TOOL_RUNNING)
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
                            setTurnPhase(assistantId, AgentTurnPhase.THINKING)
                            finishToolActivity(
                                assistantId,
                                event.name,
                                event.summary,
                                event.success,
                                output = event.output,
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
                            setTurnPhase(assistantId, AgentTurnPhase.STOPPED)
                            finishToolActivity(
                                id = assistantId,
                                name = "模型请求",
                                summary = event.message,
                                success = false,
                                activitySummary = "模型请求已停止",
                            )
                            appendChat(assistantId, "\n\n■ ${event.message}\n")
                            _state.update {
                                it.copy(
                                    agentRequestStartedAt = null,
                                    agentRetry = null,
                                )
                            }
                        }
                        is AgentEvent.Failed -> {
                            setTurnPhase(assistantId, AgentTurnPhase.FAILED)
                            finishToolActivity(
                                id = assistantId,
                                name = "模型请求",
                                summary = event.message,
                                success = false,
                                activitySummary = "模型请求失败",
                            )
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
                                )
                            }
                        }
                        AgentEvent.Completed -> {
                            setTurnPhase(assistantId, AgentTurnPhase.COMPLETED)
                            _state.update {
                                it.copy(
                                    agentRequestStartedAt = null,
                                    agentRetry = null,
                                )
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                setTurnPhase(assistantId, AgentTurnPhase.STOPPED)
                throw error
            } catch (error: Exception) {
                _state.update { state -> state.copy(chat = state.chat.map { entry ->
                    if (entry.id == assistantId) entry.copy(error = error.message ?: "附件准备失败",
                        phase = AgentTurnPhase.FAILED) else entry
                }) }
            } finally {
                agentJob = null
                if (activeAssistantId == assistantId) activeAssistantId = null
                _state.update {
                    it.copy(
                        agentRunning = false,
                        agentStatus = null,
                        agentCompactStatus = null,
                        agentRequestStartedAt = null,
                        agentRetry = null,
                        chat = it.chat.map { entry ->
                            if (entry.id == assistantId) {
                                entry.copy(
                                    pending = false,
                                    phase = when {
                                        entry.error != null -> AgentTurnPhase.FAILED
                                        entry.phase == AgentTurnPhase.STOPPED ->
                                            AgentTurnPhase.STOPPED
                                        else -> AgentTurnPhase.COMPLETED
                                    },
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
                subagentManager.stopAll()
                agentExtensions.close()
                val failed = _state.value.chat.firstOrNull { it.id == assistantId }?.let {
                    it.error != null || it.phase == AgentTurnPhase.STOPPED
                } == true
                if (failed) _state.update { state -> state.copy(queuePaused = true,
                    queuedPrompts = state.queuedPrompts.map { it.copy(steering = false) }) }
                val nextPrompt = _state.value.queuedPrompts.firstOrNull()
                if (nextPrompt == null || _state.value.queuePaused) {
                    AgentForegroundService.stop(appContext)
                } else {
                    // Dispatch synchronously on Main so a manual send cannot overtake this queue item.
                    dispatchQueuedPrompt()
                }
                saveComposer()
            }
        }
    }

    fun stopAgent() {
        if (!_state.value.agentRunning) return
        auditLog.append("agent", "请求停止任务", "用户点击停止")
        subagentManager.stopAll()
        agentExtensions.close()
        _state.update {
            it.copy(
                agentStatus = "正在停止任务…",
                queuePaused = true,
            )
        }
        saveComposer()
        pendingApprovalDeferred?.complete(false)
        pendingApprovalDeferred = null
        pendingChoiceDeferred?.complete(null)
        pendingChoiceDeferred = null
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
        refreshModels()
        sendChat("请运行 hello.py 并确认输出。")
    }

    fun clearChat() {
        pendingApprovalDeferred?.complete(false)
        pendingApprovalDeferred = null
        pendingChoiceDeferred?.complete(null)
        pendingChoiceDeferred = null
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

    private fun appendReasoning(id: Long, text: String) {
        if (text.isBlank()) return
        _state.update { current ->
            current.copy(
                chat = current.chat.map { entry ->
                    if (entry.id == id) {
                        val separator = if (entry.reasoning.isBlank()) "" else "\n\n"
                        entry.copy(reasoning = entry.reasoning + separator + text.trim())
                    } else {
                        entry
                    }
                },
            )
        }
    }

    private fun trimChatTail(id: Long, chars: Int) {
        if (chars <= 0) return
        _state.update { current ->
            current.copy(
                chat = current.chat.map { entry ->
                    if (entry.id == id) {
                        entry.copy(
                            content = entry.content.dropLast(chars.coerceAtMost(entry.content.length)),
                        )
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
                                contentOffset = entry.content.length,
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

    private fun setTurnPhase(id: Long, phase: AgentTurnPhase) {
        _state.update { current ->
            current.copy(
                chat = current.chat.map { entry ->
                    if (entry.id == id) entry.copy(phase = phase) else entry
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
        output: String? = null,
        activitySummary: String? = null,
        additions: Int = 0,
        deletions: Int = 0,
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
                                    result = output ?: summary,
                                    success = success,
                                    durationMs = activity.startedAt
                                        ?.let { (finishedAt - it).coerceAtLeast(0) },
                                    additions = additions,
                                    deletions = deletions,
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
        flushComposer()
        subagentManager.stopAll()
        agentExtensions.close()
        aiClient.cancel()
        commandRunner.cancel()
        agentJob?.cancel()
        AgentForegroundService.stop(appContext)
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
        private const val USER_CHOICE_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
