package dev.pocketagent.agent

import dev.pocketagent.data.AiSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AiClientStreamReconnectTest {
    @Test
    fun reconnectsAndInvalidatesPartialOutputAfterStreamBreaks() = runBlocking {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val responder = thread(isDaemon = true, name = "reconnecting-model-server") {
            repeat(2) { requestIndex ->
                server.accept().use { socket ->
                    socket.getInputStream().read(ByteArray(8_192))
                    val body = if (requestIndex == 0) {
                        """data: {"choices":[{"delta":{"content":"partial"}}]}""" + "\n\n"
                    } else {
                        """data: {"choices":[{"delta":{"content":"complete"},"finish_reason":"stop"}]}""" +
                            "\n\ndata: [DONE]\n\n"
                    }
                    socket.getOutputStream().write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/event-stream\r\n" +
                                "Connection: close\r\n\r\n" +
                                body
                            ).toByteArray(),
                    )
                    socket.getOutputStream().flush()
                }
            }
        }
        val client = AiClient(
            OkHttpClient.Builder()
                .callTimeout(3, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
        )
        val resets = mutableListOf<Int>()
        val retries = mutableListOf<RetryNotice>()
        try {
            val turn = client.complete(
                settings = AiSettings(
                    baseUrl = "http://127.0.0.1:${server.localPort}/v1",
                    model = "test-model",
                    apiKey = "test-key",
                ),
                messages = listOf(AgentMessage(role = "user", content = "hello")),
                tools = emptyList(),
                onRetry = retries::add,
                onStreamReset = resets::add,
            )

            assertEquals(listOf("partial".length), resets)
            assertEquals(1, retries.size)
            assertTrue(retries.single().reason.contains("重新生成"))
            assertEquals("complete", turn.content)
        } finally {
            server.close()
            responder.join(1_000)
        }
    }
}
