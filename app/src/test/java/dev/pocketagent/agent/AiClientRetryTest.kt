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
    @Test
    fun retriesConnectionFailuresByTypeAndThroughWrappedCauses() {
        listOf(
            java.net.ConnectException("Failed to connect to yujianwudi.net/104.21.84.94:443"),
            java.net.ConnectException(),
            java.net.NoRouteToHostException(),
            java.net.SocketException("Network is unreachable"),
            java.io.EOFException(),
            java.io.IOException("Failed to connect to yujianwudi.net/104.21.84.94:443"),
            java.io.IOException("Request failed", java.net.ConnectException()),
        ).forEach { assertTrue(it.isTransientNetworkError()) }
    }

    @Test
    fun doesNotReconnectCanceledRequestsOrInvalidCertificatesAndProtocols() {
        listOf(
            java.io.IOException("Canceled"),
            java.net.SocketException("Socket cancelled"),
            javax.net.ssl.SSLPeerUnverifiedException("Certificate pinning failure"),
            java.io.IOException("failed to connect", java.security.cert.CertificateException("Invalid certificate")),
            java.net.ProtocolException("Invalid HTTP response"),
            java.io.IOException("Attachment unavailable"),
        ).forEach { assertFalse(it.isTransientNetworkError()) }
    }

}
