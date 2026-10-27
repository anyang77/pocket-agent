package dev.pocketagent.agent

import dev.pocketagent.data.AiSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AiClientTimeoutTest {
    @Test
    fun reportsEveryRetryBeforeFailingWhenResponseNeverCompletes() = runBlocking {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val responder = thread(isDaemon = true, name = "hanging-model-server") {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.getInputStream().read(ByteArray(4_096))
                        socket.getOutputStream().write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: 1000\r\n\r\n"
                                ).toByteArray(),
                        )
                        socket.getOutputStream().flush()
                        Thread.sleep(700)
                    }
                } catch (_: SocketException) {
                    break
                }
            }
        }
        val client = AiClient(
            OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(200, TimeUnit.MILLISECONDS)
                .callTimeout(200, TimeUnit.MILLISECONDS)
                .build(),
        )
        val settings = AiSettings(
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            model = "test-model",
            apiKey = "test-key",
        )
        val startedAt = System.nanoTime()
        val retries = mutableListOf<RetryNotice>()
        try {
            client.complete(
                settings = settings,
                messages = listOf(AgentMessage(role = "user", content = "hello")),
                tools = emptyList(),
                onRetry = retries::add,
            )
            fail("Hanging response should time out")
        } catch (error: AgentException) {
            assertTrue(error.message.orEmpty().contains("秒内没有响应"))
        } finally {
            server.close()
            responder.join(1_000)
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertEquals(listOf(1, 2, 3, 4, 5), retries.map { it.attempt })
        assertEquals(listOf(5, 5, 5, 5, 5), retries.map { it.maxAttempts })
        assertEquals(listOf(1, 2, 4, 8, 16), retries.map { it.delaySeconds })
        assertTrue("Expected bounded retry time, was ${elapsedMs}ms", elapsedMs < 35_000)
    }
}
