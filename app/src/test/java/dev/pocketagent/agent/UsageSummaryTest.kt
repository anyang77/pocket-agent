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

    @Test
    fun missingCacheMetricsDoNotCountAsZeroHitsInAggregate() {
        val missing = TokenUsage(promptTokens = 6038)
        val measured = TokenUsage(promptTokens = 6038, cacheHitTokens = 5888,
            cacheMissTokens = 150, cacheMetricsAvailable = true)
        val merged = missing + missing + missing + measured + measured + measured
        assertEquals(36228, merged.promptTokens)
        assertEquals(18114, merged.cacheMeasuredPromptTokens)
        assertEquals(5888f / 6038f, merged.cacheHitRate!!, 0.0001f)
        assertNull(missing.cacheHitRate)
        val actualZero = TokenUsage(promptTokens = 6038, cacheMetricsAvailable = true)
        assertEquals(5888f / 12076f, (measured + actualZero).cacheHitRate!!, 0.0001f)
    }
}
