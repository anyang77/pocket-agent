package dev.pocketagent.agent

import dev.pocketagent.data.AiSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class InlineImageChatTest {
    private val data = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6cKAAAAAASUVORK5CYII="

    private fun withServer(done: Boolean, delayMs: Long = 0, action: (String) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                socket.getInputStream().read(ByteArray(8192))
                val output = socket.getOutputStream()
                if (delayMs > 0) Thread.sleep(delayMs)
                output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                for (piece in ("before ![image_1]($data) after").chunked(7)) {
                    val delta = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("content", piece))))
                    output.write("data: $delta\n\n".toByteArray())
                }
                if (done) output.write("data: [DONE]\n\n".toByteArray())
                output.flush()
            }
        }
        try { action("http://127.0.0.1:${server.localPort}/v1") }
        finally { server.close(); worker.join(2000) }
    }

    @Test fun streamingChatImageIsSavedWithoutLeakingBytesIntoPreviewOrHistory() = withServer(true) { url ->
        runBlocking {
            val preview = InlineImagePreview()
            val visible = StringBuilder()
            val client = AiClient(OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build())
            val turn = client.complete(AiSettings(baseUrl = url, model = "image-test", apiKey = "local"),
                listOf(AgentMessage("user", "draw")), emptyList(), onDelta = { visible.append(preview.append(it)) })
            visible.append(preview.finish())
            assertFalse(visible.contains("base64"))
            val dir = Files.createTempDirectory("chat-image-test").toFile()
            try {
                val result = InlineImages.extract(turn.content.orEmpty(), dir)
                assertEquals(1, result.paths.size)
                assertTrue(File(result.paths.single()).length() > 0)
                assertFalse(result.text.contains("data:image"))
                assertTrue(result.text.endsWith(" after"))
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun imageModelWaitsPastNormalChatReadTimeoutWithoutRetrying() = withServer(true, delayMs = 300) { url ->
        runBlocking {
            var retries = 0
            val client = AiClient(OkHttpClient.Builder().readTimeout(50, TimeUnit.MILLISECONDS)
                .callTimeout(3, TimeUnit.SECONDS).build())
            val turn = client.complete(AiSettings(baseUrl = url, model = "gpt-image-2.6", apiKey = "local"),
                listOf(AgentMessage("user", "draw")), emptyList(), onRetry = { retries++ })
            assertTrue(turn.content.orEmpty().contains("data:image"))
            assertEquals(0, retries)
        }
    }

    @Test fun interruptedImageTransferNeverAutomaticallyRetries() = withServer(false) { url ->
        runBlocking {
            var retries = 0
            val client = AiClient(OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build())
            try {
                client.complete(AiSettings(baseUrl = url, model = "image-test", apiKey = "local"),
                    listOf(AgentMessage("user", "draw")), emptyList(), onRetry = { retries++ })
                fail("Expected interrupted image failure")
            } catch (error: AgentException) {
                assertTrue(error.message.orEmpty().contains("不会自动重试"))
                assertEquals(0, retries)
            }
        }
    }
}
