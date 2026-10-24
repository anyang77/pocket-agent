package dev.pocketagent.ui

import dev.pocketagent.data.AiSettings
import dev.pocketagent.agent.AgentPermissions
import dev.pocketagent.agent.AgentPlanStep
import dev.pocketagent.data.AuditEntry
import dev.pocketagent.data.ConversationSummary
import dev.pocketagent.data.DirectoryEntry
import dev.pocketagent.data.FileEntry
import dev.pocketagent.data.GitState
import dev.pocketagent.data.GitCommitDetail
import dev.pocketagent.data.GitAccount
import dev.pocketagent.data.SshAccount
import dev.pocketagent.data.ManagedRepository
import dev.pocketagent.data.ModelProfile
import dev.pocketagent.agent.TokenUsage
import dev.pocketagent.agent.ToolActivity
import dev.pocketagent.runtime.RuntimeCapabilities

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
)

data class PendingApproval(
    val id: Long,
    val title: String,
    val detail: String,
    val risk: String,
)

data class AppUiState(
    val tab: AppTab = AppTab.AGENT,
    val onboardingCompleted: Boolean = false,
    val capabilities: RuntimeCapabilities = RuntimeCapabilities(),
    val settings: AiSettings = AiSettings(),
    val modelProfiles: List<ModelProfile> = emptyList(),
    val activeProfileId: String = "",
    val permissions: AgentPermissions = AgentPermissions(),
    val pendingApproval: PendingApproval? = null,
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
    val sshAccounts: List<SshAccount> = emptyList(),
    val sshKeyGenerating: Boolean = false,
    val managedRepositories: List<ManagedRepository> = emptyList(),
    val chat: List<ChatEntry> = emptyList(),
    val agentRunning: Boolean = false,
    val agentStatus: String? = null,
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
    val terminalOutput: String = "",
    val terminalRunning: Boolean = false,
    val terminalRoot: Boolean = false,
    val rootProbing: Boolean = false,
    val internalRuntimeInstalling: Boolean = false,
    val internalRuntimeInstallMessage: String? = null,
    val internalRuntimeInstallProgress: Float = 0f,
    val notice: String? = null,
)
