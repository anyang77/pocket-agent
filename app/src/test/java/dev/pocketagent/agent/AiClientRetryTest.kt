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
    fun doesNotRetryRequestValidationErrors() {
        assertFalse(AgentHttpException(400, "").isRetryable())
        assertFalse(AgentHttpException(401, "").isRetryable())
        assertFalse(AgentHttpException(404, "").isRetryable())
    }
}
