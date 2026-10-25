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

class AiClientStreamingTest {
    @Test
    fun assemblesStreamingTextReasoningUsageAndToolCalls() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(isDaemon = true, name = "streaming-model-server") {
            server.accept().use { socket ->
                socket.getInputStream().read(ByteArray(8_192))
                val events = listOf(
                    """data: {"choices":[{"delta":{"reasoning_content":"think "}}]}""",
                    """data: {"choices":[{"delta":{"content":"Hello "}}]}""",
                    """data: {"choices":[{"delta":{"content":"world","tool_calls":[{"index":0,"id":"call_1","function":{"name":"shell","arguments":"{\"cmd\":"}}]}}]}""",
                    """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"pwd\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14}}""",
                    "data: [DONE]",
                    "",
                ).joinToString("\n")
                socket.getOutputStream().write(
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream\r\n" +
                            "Connection: close\r\n\r\n" +
                            events
                        ).toByteArray(),
                )
                socket.getOutputStream().flush()
            }
        }
        val client = AiClient(
            OkHttpClient.Builder()
                .callTimeout(3, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
        )
        val deltas = StringBuilder()
        val reasoning = StringBuilder()
        try {
            val turn = client.complete(
                settings = AiSettings(
                    baseUrl = "http://127.0.0.1:${server.localPort}/v1",
                    model = "test-model",
                    apiKey = "test-key",
                ),
                messages = listOf(AgentMessage(role = "user", content = "hello")),
                tools = emptyList(),
                onDelta = deltas::append,
                onReasoningDelta = reasoning::append,
            )

            assertEquals("Hello world", deltas.toString())
            assertEquals("think ", reasoning.toString())
            assertTrue(turn.contentWasStreamed)
            assertEquals("tool_calls", turn.finishReason)
            assertEquals(10, turn.usage?.promptTokens)
            assertEquals("shell", turn.toolCalls.single().name)
            assertEquals("""{"cmd":"pwd"}""", turn.toolCalls.single().arguments)
        } finally {
            server.close()
            responder.join(1_000)
        }
    }
}
