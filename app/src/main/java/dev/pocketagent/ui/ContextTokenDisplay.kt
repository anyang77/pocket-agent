package dev.pocketagent.ui

import dev.pocketagent.agent.AgentEvent

/** One context-occupancy source for the header and both compaction entry points. */
internal fun AgentEvent.contextTokensForDisplay(current: Int): Int = when (this) {
    is AgentEvent.ModelRequestStarted -> contextTokens.takeIf { it > 0 } ?: current
    is AgentEvent.Usage -> if (updatesContext) usage.promptTokens.takeIf { it > 0 } ?: current else current
    is AgentEvent.ContextCompactionStarted -> beforeTokens.coerceAtLeast(0)
    is AgentEvent.ContextCompacted -> afterTokens.coerceAtLeast(0)
    else -> current
}
