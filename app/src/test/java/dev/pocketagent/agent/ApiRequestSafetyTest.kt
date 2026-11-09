package dev.pocketagent.agent

import dev.pocketagent.data.AiSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ApiRequestSafetyTest {
    private class Server(private val response: String) : AutoCloseable {
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}/v1"
        val requests = AtomicInteger()
        private val worker = thread(isDaemon = true) {
            try {
                socket.soTimeout = 1000
                while (!socket.isClosed) {
                    val peer = try { socket.accept() } catch (_: SocketTimeoutException) { break }
                    peer.use {
                        it.soTimeout = 2000
                        val input = it.getInputStream()
                        fun line(): String {
                            val result = StringBuilder()
                            while (true) {
                                val b = input.read()
                                check(b >= 0)
                                if (b == 10) return result.toString().trimEnd('\r')
                                result.append(b.toChar())
                            }
                        }
                        line()
                        var length = 0
                        while (true) {
                            val h = line()
                            if (h.isEmpty()) break
                            if (h.startsWith("Content-Length:", true)) length = h.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { check(input.read() >= 0) }
                        requests.incrementAndGet()
                        it.getOutputStream().apply { write(response.toByteArray()); flush() }
                    }
                }
            } catch (_: java.net.SocketException) {
                // Test teardown closes the listener.
            }
        }
        fun awaitRequests(): Int { worker.join(3000); return requests.get() }
        override fun close() { socket.close(); worker.join(3000) }
    }

    private fun http() = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
    private fun settings(url: String, model: String = "gpt-image-test") =
        AiSettings(baseUrl = url, model = model, apiKey = "private-service-key")

    @Test fun retryAfterZeroCannotReplayGenerationOrImageChatPosts() = runBlocking {
        val body = """{"error":{"message":"upstream overloaded"}}"""
        val response = "HTTP/1.1 503 Unavailable\r\nRetry-After: 0\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        for (chat in listOf(false, true)) Server(response).use { server ->
            try {
                if (chat) AiClient(http()).complete(settings(server.url), listOf(AgentMessage("user", "draw")), emptyList())
                else ImageGenerationClient(http()).generate(settings(server.url), "draw", "auto")
                fail("Expected service rejection")
            } catch (error: AgentException) {
                assertTrue(error.message.orEmpty().contains("503"))
            }
            assertEquals("chat=$chat must send exactly one POST", 1, server.awaitRequests())
        }
    }

    @Test fun httpErrorsAreRedactedBeforeReachingCaller() = runBlocking {
        val body = """{"error":{"message":"private-service-key Bearer other-secret https://example.org/image?token=secret"}}"""
        val response = "HTTP/1.1 400 Bad Request\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        Server(response).use { server ->
            try {
                AiClient(http()).complete(settings(server.url), listOf(AgentMessage("user", "draw")), emptyList())
                fail("Expected service rejection")
            } catch (error: AgentException) {
                val message = error.message.orEmpty()
                assertFalse(message.contains("private-service-key"))
                assertFalse(message.contains("other-secret"))
                assertFalse(message.contains("https://"))
                assertTrue(message.contains("REDACTED"))
            }
        }
    }

    @Test fun customModelImageThenStreamErrorDoesNotRetryAndRedactsCredentials() = runBlocking {
        val image = "data:" + "image/png;base64,AAAA"
        val delta = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", image))))
        val error = JSONObject().put("error", JSONObject().put("message", "upstream overloaded private-service-key https://example.org/signed?key=secret"))
        val body = "data: $delta\n\ndata: $error\n\n"
        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
        Server(response).use { server ->
            var retries = 0
            try {
                AiClient(http()).complete(settings(server.url, "custom-art"), listOf(AgentMessage("user", "draw")), emptyList(),
                    onRetry = { retries++ })
                fail("Expected stream error")
            } catch (error: AgentException) {
                assertFalse(error.message.orEmpty().contains("private-service-key"))
                assertFalse(error.message.orEmpty().contains("https://"))
                assertEquals(0, retries)
            }
            assertEquals(1, server.awaitRequests())
        }
    }
}
