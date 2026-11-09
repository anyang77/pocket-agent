package dev.pocketagent.agent

import dev.pocketagent.data.AiSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ImageChatPolicyTest {
    @Test fun detectsExplicitImageNamesWithoutChangingChatModels() {
        listOf("gpt-image-2.6", "gemini-3.1-flash-image", "imagen-4", "vendor/image-model", " GPT-IMAGE-2 ").forEach {
            assertTrue(it, AiClient.isImageGenerationModel(it))
        }
        listOf("gpt-6.1-sol", "deepseek-chat", "imagetext-chat", "vision-chat").forEach {
            assertFalse(it, AiClient.isImageGenerationModel(it))
        }
    }

    @Test fun imageServiceErrorIsNotRetried() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                socket.getInputStream().read(ByteArray(8192))
                val body = "{\"error\":{\"message\":\"upstream overloaded\"}}"
                socket.getOutputStream().apply {
                    write(("HTTP/1.1 503 Service Unavailable\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n" + body).toByteArray())
                    flush()
                }
            }
        }
        var retries = 0
        try {
            val client = AiClient(OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build())
            client.complete(AiSettings(baseUrl = "http://127.0.0.1:${server.localPort}/v1", model = "gpt-image-2.6", apiKey = "local"),
                listOf(AgentMessage("user", "draw")), emptyList(), onRetry = { retries++ })
            fail("Expected service error")
        } catch (error: AgentException) {
            assertTrue(error.message.orEmpty().contains("503"))
            assertEquals(0, retries)
        } finally {
            server.close()
            worker.join(2000)
        }
    }
}
