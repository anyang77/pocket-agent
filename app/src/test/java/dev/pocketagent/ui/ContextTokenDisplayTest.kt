package dev.pocketagent.ui

import dev.pocketagent.agent.AgentEvent
import dev.pocketagent.agent.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextTokenDisplayTest {
    @Test fun providerUsageReplacesStaleRequestEstimate() {
        assertEquals(86_000, AgentEvent.Usage(TokenUsage(promptTokens = 86_000)).contextTokensForDisplay(68_000))
    }

    @Test fun compactionStartPublishesItsExactTriggerValue() {
        assertEquals(86_000, AgentEvent.ContextCompactionStarted(86_000, 100_000, 20).contextTokensForDisplay(68_000))
    }

    @Test fun completionPublishesSmallerContextAndBillingCannotOverwriteIt() {
        val compacted = AgentEvent.ContextCompacted(86_000, 12_000, 100_000, 8_000, 200, false)
        val after = compacted.contextTokensForDisplay(86_000)
        assertEquals(12_000, after)
        assertEquals(after, AgentEvent.Usage(TokenUsage(promptTokens = 90_000), updatesContext = false).contextTokensForDisplay(after))
    }

    @Test fun absentUsageDoesNotEraseKnownOccupancy() {
        assertEquals(68_000, AgentEvent.Usage(TokenUsage()).contextTokensForDisplay(68_000))
        assertEquals(68_000, AgentEvent.ModelRequestStarted("model", 1, 20, 0).contextTokensForDisplay(68_000))
    }

    @Test fun nextRequestRefreshesPostCompactionEstimate() {
        assertEquals(13_000, AgentEvent.ModelRequestStarted("model", 2, 3, 13_000).contextTokensForDisplay(12_000))
    }
}
