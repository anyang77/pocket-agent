package dev.pocketagent.ui

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
            return visible.value.withConversation(sessions[id] ?: error("Unknown conversation $id"))
        }

    @Synchronized
    fun update(transform: (AppUiState) -> AppUiState) {
        val id = contextId.get()
        if (id == null || sessions[id] == null) {
            val next = transform(visible.value)
            visible.value = next
            next.currentConversationId?.let { sessions[it] = next }
        } else {
            val next = transform(value)
            sessions[id] = next
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
