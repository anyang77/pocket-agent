package dev.pocketagent.agent

import dev.pocketagent.data.AiSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AiClientAttachmentTest {
    @Test fun sendsImageBytesAlongsideTextAndRetainsHistoryImages() = runBlocking {
        val image = File.createTempFile("chat-image", ".jpg")
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        image.writeBytes(bytes)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val captured = CompletableFuture<JSONObject>()
        val worker = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream()
                    fun line(): String {
                        val out = StringBuilder()
                        while (true) {
                            val b = input.read()
                            check(b != -1)
                            if (b == 10) return out.toString().trimEnd('\r')
                            out.append(b.toChar())
                        }
                    }
                    var length = 0
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val count = input.read(body, read, length - read)
                        check(count > 0)
                        read += count
                    }
                    captured.complete(JSONObject(body.toString(Charsets.UTF_8)))
                    val response = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.toByteArray().size}\r\nConnection: close\r\n\r\n$response").toByteArray())
                }
            } catch (error: Throwable) { captured.completeExceptionally(error) }
        }
        try {
            val client = AiClient(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build())
            val turn = client.complete(
                AiSettings(baseUrl = "http://127.0.0.1:${server.localPort}/v1", model = "vision-test", apiKey = "local"),
                listOf(
                    AgentMessage("system", "system"),
                    AgentMessage("user", "看图", imagePaths = listOf(image.path)),
                    AgentMessage("assistant", "已看到"),
                    AgentMessage("user", "再看这张", imagePaths = listOf(image.path)),
                ), emptyList(),
            )
            assertEquals("ok", turn.content)
            val messages = captured.get(5, TimeUnit.SECONDS).getJSONArray("messages")
            assertEquals("system", messages.getJSONObject(0).getString("content"))
            assertEquals("已看到", messages.getJSONObject(2).getString("content"))
            for (index in listOf(1, 3)) {
                val content = messages.getJSONObject(index).getJSONArray("content")
                assertEquals("text", content.getJSONObject(0).getString("type"))
                val data = content.getJSONObject(1).getJSONObject("image_url").getString("url")
                assertArrayEquals(bytes, Base64.getDecoder().decode(data.substringAfter(',')))
            }
        } finally { image.delete(); server.close(); worker.join(1000) }
    }

    @Test fun imageOnlyMessageAndMissingAttachment() {
        val image = File.createTempFile("chat-image", ".jpg")
        try {
            image.writeBytes(byteArrayOf(1))
            assertEquals("image_url", multimodalContent(AgentMessage("user", "", imagePaths = listOf(image.path)))
                .getJSONObject(0).getString("type"))
            image.delete()
            assertThrows(IllegalArgumentException::class.java) {
                multimodalContent(AgentMessage("user", "", imagePaths = listOf(image.path)))
            }
        } finally { image.delete() }
    }
}
