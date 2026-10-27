package dev.pocketagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiClientRetryTest {
    @Test
    fun retriesRateLimitsAndServerFailures() {
        assertTrue(AgentHttpException(429, "").isRetryable())
        assertTrue(AgentHttpException(503, "").isRetryable())
        assertTrue(AgentHttpException(524, "").isRetryable())
    }

    @Test
    fun retriesServiceErrorsReportedInsideSuccessfulStreams() {
        assertTrue(
            AgentException(
                "模型流式响应失败: Concurrency limit exceeded for user, please retry later",
            ).isRetryable(),
        )
        assertTrue(AgentException("Service temporarily unavailable").isRetryable())
        assertFalse(AgentException("模型流式响应结束，但没有返回内容").isRetryable())
    }

    @Test
    fun doesNotRetryRequestValidationErrors() {
        assertFalse(AgentHttpException(400, "").isRetryable())
        assertFalse(AgentHttpException(401, "").isRetryable())
        assertFalse(AgentHttpException(404, "").isRetryable())
    }
}
