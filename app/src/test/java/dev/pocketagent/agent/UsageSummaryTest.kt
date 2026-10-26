package dev.pocketagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The header pill must always report the cumulative (overall) cache hit rate. */
class UsageSummaryTest {
    @Test
    fun overallRateUsesCumulativeHitsOverCumulativeInput() {
        val session = TokenUsage(
            promptTokens = 10_000,
            cacheHitTokens = 9_000,
            cacheMissTokens = 1_000,
            cacheMetricsAvailable = true,
            requests = 5,
        )
        val reply = session + TokenUsage(
            promptTokens = 1_000,
            cacheHitTokens = 100,
            cacheMissTokens = 900,
            cacheMetricsAvailable = true,
        )

        assertEquals(0.9f, session.cacheHitRate ?: 0f, 0.0001f)
        assertEquals(9_100f / 11_000f, reply.cacheHitRate ?: 0f, 0.0001f)
        assertEquals(6, reply.requests)
    }

    @Test
    fun rateIsUnknownWithoutInputTokens() {
        assertNull(TokenUsage().cacheHitRate)
    }
}
