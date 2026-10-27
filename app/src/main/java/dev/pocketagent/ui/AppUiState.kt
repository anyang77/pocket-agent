package dev.pocketagent.ui

import dev.pocketagent.data.AiSettings
import dev.pocketagent.data.AgentGoal
import dev.pocketagent.agent.AgentChoice
import dev.pocketagent.agent.AgentPermissions
import dev.pocketagent.agent.AgentPlanStep
import dev.pocketagent.data.AuditEntry
import dev.pocketagent.data.ConversationSummary
import dev.pocketagent.data.DirectoryEntry
import dev.pocketagent.data.FileEntry
import dev.pocketagent.data.GitState
import dev.pocketagent.data.GitCommitDetail
import dev.pocketagent.data.GitAccount
import dev.pocketagent.data.GitHubRepository
import dev.pocketagent.data.ManagedRepository
import dev.pocketagent.data.ModelProfile
import dev.pocketagent.agent.TokenUsage
import dev.pocketagent.agent.ToolActivity
import dev.pocketagent.runtime.RuntimeCapabilities
import dev.pocketagent.runtime.RuntimeKind
import dev.pocketagent.ui.theme.ThemePreference

enum class AppTab {
    AGENT,
    FILES,
    GIT,
    TERMINAL,
    SETTINGS,
}

enum class ChatRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

enum class AgentTurnPhase {
    IDLE,
    THINKING,
    RECEIVING,
    TOOL_RUNNING,
    WAITING_APPROVAL,
    RETRYING,
    COMPACTING,
    COMPLETED,
    FAILED,
    STOPPED,
}

data class ChatEntry(
    val id: Long,
    val role: ChatRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val pending: Boolean = false,
    val activities: List<ToolActivity> = emptyList(),
    val usage: TokenUsage? = null,
    val lastRequestUsage: TokenUsage? = null,
    val lastRequestAt: Long? = null,
    val plan: List<AgentPlanStep> = emptyList(),
    val error: String? = null,
    val retryCount: Int = 0,
    val goal: String? = null,
    val phase: AgentTurnPhase = AgentTurnPhase.IDLE,
    val reasoning: String = "",
)

data class AgentRetryState(
    val attempt: Int,
    val maxAttempts: Int,
    val reason: String,
    val retryAt: Long,
)

data class PendingUserChoice(
    val question: String,
    val options: List<AgentChoice>,
)

data class PendingApproval(
    val id: Long,
    val title: String,
    val detail: String,
    val risk: String,
)

data class GitHubLoginState(
    val userCode: String = "",
    val verificationUri: String = "https://github.com/login/device",
    val status: String = "正在连接 GitHub",
    val expiresAt: Long? = null,
)

data class AppUiState(
    val welcomeAccepted: Boolean = false,
    val welcomeVisible: Boolean = false,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val tab: AppTab = AppTab.FILES,
    val onboardingCompleted: Boolean = false,
    val capabilities: RuntimeCapabilities = RuntimeCapabilities(),
    val settings: AiSettings = AiSettings(),
    val modelProfiles: List<ModelProfile> = emptyList(),
    val activeProfileId: String = "",
    val permissions: AgentPermissions = AgentPermissions(),
    val pendingApproval: PendingApproval? = null,
    val pendingUserChoice: PendingUserChoice? = null,
    val auditEntries: List<AuditEntry> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
    val currentConversationId: Long? = null,
    val availableModels: List<String> = emptyList(),
    val modelReasoningEfforts: Map<String, List<String>> = emptyMap(),
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
    val storageAccessGranted: Boolean = false,
    val notificationPermissionGranted: Boolean = false,
    val gitState: GitState = GitState(),
    val gitDiff: String = "",
    val gitDiffTitle: String? = null,
    val gitLoading: Boolean = false,
    val gitError: String? = null,
    val gitCommitDetail: GitCommitDetail? = null,
    val gitCommitDetailLoading: Boolean = false,
    val gitAccounts: List<GitAccount> = emptyList(),
    val activeGitAccountId: String? = null,
    val githubLogin: GitHubLoginState? = null,
    val githubLoginRunning: Boolean = false,
    val githubLoginError: String? = null,
    val githubOAuthClientConfigured: Boolean = false,
    val githubRepositories: List<GitHubRepository> = emptyList(),
    val githubRepositoriesLoading: Boolean = false,
    val githubRepositoriesError: String? = null,
    val managedRepositories: List<ManagedRepository> = emptyList(),
    val chat: List<ChatEntry> = emptyList(),
    val agentInputDraft: String = "",
    val agentRunning: Boolean = false,
    val agentStatus: String? = null,
    /** Non-null while context compaction is running; rendered inline instead of as a card. */
    val agentCompactStatus: String? = null,
    /** Prompt size published by the engine — the same number that triggers compaction. */
    val agentContextTokens: Int = 0,
    /** Output speed in tokens/second; live while streaming, final once the request ends. */
    val agentTokensPerSecond: Float? = null,
    val agentRequestStartedAt: Long? = null,
    val agentRetry: AgentRetryState? = null,
    val agentGoal: AgentGoal? = null,
    val queuedPrompts: List<String> = emptyList(),
    val files: List<FileEntry> = emptyList(),
    val currentDirectory: String = ".",
    val workspacePath: String = "",
    val defaultWorkspacePath: String = "",
    val workspacePickerVisible: Boolean = false,
    val workspacePickerPath: String = "",
    val workspacePickerEntries: List<DirectoryEntry> = emptyList(),
    val workspacePickerLoading: Boolean = false,
    val workspacePickerError: String? = null,
    val activeFile: String? = null,
    val editorContent: String = "",
    val editorDirty: Boolean = false,
    val editorDiagnostics: List<EditorDiagnostic> = emptyList(),
    val scriptRunning: Boolean = false,
    val scriptOutput: String? = null,
    val scriptTitle: String? = null,
    val terminalOutput: String = "",
    val terminalRunning: Boolean = false,
    val terminalRoot: Boolean = false,
    val terminalRuntime: RuntimeKind = RuntimeKind.TERMUX,
    val rootProbing: Boolean = false,
    val internalRuntimeInstalling: Boolean = false,
    val internalRuntimeInstallMessage: String? = null,
    val internalRuntimeInstallProgress: Float = 0f,
    val notice: String? = null,
)
