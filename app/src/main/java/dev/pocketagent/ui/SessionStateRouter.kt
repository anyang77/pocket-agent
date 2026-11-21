package dev.pocketagent.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Coroutine-bound events belong to their session, not whichever conversation is on screen. */
internal class SessionStateRouter(initial: AppUiState) {
    private val contextId = ThreadLocal<Long?>()
    private val sessions = mutableMapOf<Long, AppUiState>()
    private val visible = MutableStateFlow(initial)

    @Synchronized
    fun snapshot(id: Long): AppUiState? = sessions[id]?.let { visible.value.withConversation(it) }

    val value: AppUiState
        @Synchronized get() {
            val id = contextId.get() ?: return visible.value
            return visible.value.withConversation(sessions[id]
                ?: throw CancellationException("Conversation $id was removed"))
        }

    @Synchronized
    fun update(transform: (AppUiState) -> AppUiState) {
        val id = contextId.get()
        // A delayed save may finish after its conversation was deleted. Never
        // apply that callback to whichever conversation happens to be visible.
        if (id != null && sessions[id] == null) return
        if (id == null) {
            val next = transform(visible.value).bindModelChange(visible.value)
            visible.value = next
            next.currentConversationId?.let { sessions[it] = next }
        } else {
            val before = value
            val next = transform(before).bindModelChange(before)
            sessions[id] = next
            if (next.modelProfiles != before.modelProfiles) visible.value = visible.value.copy(modelProfiles = next.modelProfiles)
            if (visible.value.currentConversationId == id) {
                visible.value = visible.value.withConversation(next)
            }
        }
    }

    @Synchronized
    fun show(state: AppUiState) {
        val id = requireNotNull(state.currentConversationId)
        val global = visible.value
        // Start from current application state; restore only conversation-owned fields.
        // A session snapshot may predate onboarding, editor changes or extension settings.
        val next = global.withConversation(sessions[id] ?: state)
        sessions[id] = next
        visible.value = next
    }

    @Synchronized
    fun remove(id: Long) { sessions.remove(id) }

    /** Global publications must remain visible even when a hidden session produced them. */
    @Synchronized
    fun publish(transform: (AppUiState) -> AppUiState) { visible.value = transform(visible.value) }

    fun asStateFlow(): StateFlow<AppUiState> = visible.asStateFlow()
    fun context(id: Long) = contextId.asContextElement(id)

    fun <T> forSession(id: Long, block: () -> T): T {
        val previous = contextId.get()
        contextId.set(id)
        return try { block() } finally { contextId.set(previous) }
    }
}

internal fun AppUiState.withConversation(session: AppUiState): AppUiState = copy(
    currentConversationId = session.currentConversationId, chat = session.chat,
    conversationModel = session.conversationModel, pendingResumeDeepId = session.pendingResumeDeepId,
    settings = session.conversationModel?.let { selection ->
        selection.resolve(modelProfiles, workspacePath) ?: settings.copy(model = selection.model,
            reasoningEffort = selection.reasoningEffort, apiKey = "")
    } ?: settings,
    activeProfileId = session.conversationModel?.profileId ?: activeProfileId,
    availableModels = session.availableModels, modelReasoningEfforts = session.modelReasoningEfforts,
    modelsLoading = session.modelsLoading, modelsError = session.modelsError,
    modelSettingsSaving = session.modelSettingsSaving, modelSettingsError = session.modelSettingsError,
    agentInputDraft = session.agentInputDraft, agentAttachments = session.agentAttachments,
    agentPlanMode = session.agentPlanMode, agentActiveTaskMode = session.agentActiveTaskMode,
    agentPlanToExecute = session.agentPlanToExecute, agentPlanWorkspacePath = session.agentPlanWorkspacePath,
    queuedPrompts = session.queuedPrompts, queuePaused = session.queuePaused,
    agentRunning = session.agentRunning, agentStatus = session.agentStatus,
    agentCompactStatus = session.agentCompactStatus, agentContextTokens = session.agentContextTokens,
    agentTokensPerSecond = session.agentTokensPerSecond, agentRequestStartedAt = session.agentRequestStartedAt,
    agentRetry = session.agentRetry, pendingApproval = session.pendingApproval,
    pendingUserChoice = session.pendingUserChoice, subagentTasks = session.subagentTasks, mcpStates = session.mcpStates,
    agentAttachmentsLoading = session.agentAttachmentsLoading,
    mentionFiles = session.mentionFiles, mentionFilesLoading = session.mentionFilesLoading,
    notice = session.notice ?: notice,
)

/** Capture explicit picker edits atomically with settings, before a session can be switched. */
private fun AppUiState.bindModelChange(previous: AppUiState): AppUiState =
    if (currentConversationId != null && (settings.model != previous.settings.model ||
            settings.reasoningEffort != previous.settings.reasoningEffort || activeProfileId != previous.activeProfileId)) {
        copy(conversationModel = dev.pocketagent.data.ConversationModelSelection(activeProfileId, settings.model, settings.reasoningEffort))
    } else this
