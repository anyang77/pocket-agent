package dev.pocketagent.agent

/**
 * Finish interrupted tool turns without replaying tools or claiming their side effects rolled back.
 * Providers require one result for every tool call before the next user/assistant turn.
 */
internal fun repairInterruptedToolCalls(history: List<AgentMessage>): List<AgentMessage> = buildList {
    val pending = linkedMapOf<String, AgentToolCall>()
    fun finishPending() {
        pending.values.forEach { call ->
            add(AgentMessage(
                role = "tool",
                name = call.name,
                toolCallId = call.id,
                content = "TOOL_INTERRUPTED: No result was recorded before this task stopped. " +
                    "The tool may have partially executed; inspect actual state before retrying. " +
                    "Do not assume changes were rolled back.",
            ))
        }
        pending.clear()
    }
    history.forEach { message ->
        if (message.role == "tool") {
            // Ignore orphan/duplicate results from older interrupted histories.
            if (pending.remove(message.toolCallId) != null) add(message)
        } else {
            finishPending()
            add(message)
            message.toolCalls.forEach { pending[it.id] = it }
        }
    }
    finishPending()
}
