package dev.pocketagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenEstimatorTest {
    @Test
    fun estimatesTokensFromCharacters() {
        assertEquals(0, TokenEstimator.estimate(0))
        assertEquals(0, TokenEstimator.estimate(-10))
        assertEquals(100, TokenEstimator.estimate(300))
    }

    @Test
    fun reportsSpeedOnceThereIsEnoughData() {
        // 300 chars ≈ 100 tokens in 2s = 50 t/s
        assertEquals(50f, TokenEstimator.tokensPerSecond(300, 2_000) ?: 0f, 0.001f)
    }

    @Test
    fun withholdsSpeedBeforeAnyOutput() {
        assertNull(TokenEstimator.tokensPerSecond(0, 5_000))
        assertNull(TokenEstimator.tokensPerSecond(300, 0))
        assertNull(TokenEstimator.tokensPerSecond(300, 100))
        assertNull(TokenEstimator.tokensPerSecond(2, 1_000))
    }

    @Test
    fun keepsLastMeasuredSpeedWhenANewRequestHasNoMeasurementYet() {
        assertEquals(42.5f, retainLastMeasuredSpeed(42.5f, null))
        assertEquals(58.2f, retainLastMeasuredSpeed(42.5f, 58.2f))
        assertNull(retainLastMeasuredSpeed(null, null))
    }
}
