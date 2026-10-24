package dev.pocketagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUsageTest {
    @Test
    fun mergesUsageAcrossAgentRequests() {
        val merged = TokenUsage(
            promptTokens = 1_000,
            completionTokens = 200,
            totalTokens = 1_200,
            cacheHitTokens = 600,
            cacheMissTokens = 400,
            cacheMetricsAvailable = true,
        ) + TokenUsage(
            promptTokens = 2_000,
            completionTokens = 300,
            totalTokens = 2_300,
            cacheHitTokens = 1_800,
            cacheMissTokens = 200,
            cacheMetricsAvailable = true,
        )

        assertEquals(3_000, merged.promptTokens)
        assertEquals(500, merged.completionTokens)
        assertEquals(2_400, merged.cacheHitTokens)
        assertEquals(2, merged.requests)
        assertEquals(0.8f, merged.cacheHitRate ?: 0f, 0.0001f)
        assertTrue(merged.cacheMetricsAvailable)
    }

    @Test
    fun resolvesContextWindowsForCommonModels() {
        assertEquals(128_000, ModelContextBudget.limitFor("deepseek-chat"))
        assertEquals(128_000, ModelContextBudget.limitFor("deepseek-flash"))
        assertEquals(1_000_000, ModelContextBudget.limitFor("gpt-4.1-mini"))
        assertEquals(200_000, ModelContextBudget.limitFor("claude-sonnet"))
        assertEquals(1_000_000, ModelContextBudget.limitFor("gemini-2.5-pro"))
        assertEquals(512_000, ModelContextBudget.limitFor("deepseek-chat", 512_000))
    }

    @Test
    fun normalizesAndInfersReasoningEfforts() {
        assertEquals(
            listOf("low", "medium", "high"),
            ReasoningEffortSupport.normalize(listOf("HIGH", "low", "medium", "high")),
        )
        assertEquals(
            listOf("minimal", "low", "medium", "high"),
            ReasoningEffortSupport.infer("gpt-5.2"),
        )
        assertEquals(
            listOf("low", "medium", "high"),
            ReasoningEffortSupport.infer("deepseek-flash"),
        )
        assertTrue(ReasoningEffortSupport.infer("plain-model").isEmpty())
    }
}
