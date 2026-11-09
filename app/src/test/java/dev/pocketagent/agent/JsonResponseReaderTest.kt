package dev.pocketagent.agent

import dev.pocketagent.data.AiSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class JsonResponseReaderTest {
    @Test fun handlesNestedObjectsEscapesUtf8AndArbitrarySplits() {
        val json = """  {"data":[{"text":"中文 } [ \" \\"}],"ok":true}"""
        val bytes = json.toByteArray()
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 1))
        }
        assertArrayEquals(bytes, JsonResponseReader.read(input, 1024))
    }

    @Test fun completeObjectDoesNotReadAgainOrRequireEof() {
        val bytes = """{"data":[{"b64_json":"abc"}]}""".toByteArray()
        var read = false
        val input = object : InputStream() {
            override fun read(): Int = error("unexpected single-byte read")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                check(!read) { "Waiting for EOF after complete JSON" }
                read = true
                bytes.copyInto(b, off)
                return bytes.size
            }
        }
        assertArrayEquals(bytes, JsonResponseReader.read(input, 1024))
    }

    @Test fun rejectsTruncatedAndOversizedObjects() {
        try { JsonResponseReader.read(ByteArrayInputStream("{\"data\":[".toByteArray()), 100); fail() }
        catch (_: IOException) { }
        try { JsonResponseReader.read(ByteArrayInputStream("{\"data\":123}".toByteArray()), 5); fail() }
        catch (_: AgentException) { }
    }

    /** A proxy has delivered the entire JSON but keeps the final HTTP chunk open.
     * Both production callers must finish before the server releases the connection.
     */
    @Test fun imageAndChatClientsFinishBeforeProxyEndsChunkedBody() = runBlocking {
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6cKAAAAAASUVORK5CYII=")
        for (mode in listOf("image", "chat", "sse")) {
            val chat = mode != "image"
            val sse = mode == "sse"
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val release = CountDownLatch(1)
            val worker = thread(isDaemon = true) {
                try {
                    server.accept().use { peer ->
                        peer.soTimeout = 2000
                        val input = peer.getInputStream().bufferedReader()
                        var length = 0
                        while (true) {
                            val line = input.readLine() ?: error("EOF")
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { input.read() }
                        val json = if (chat) """{"choices":[{"message":{"content":"done"},"finish_reason":"stop"}]}"""
                            else """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}"""
                        val body = (if (sse) "data: $json\n\n" else json).toByteArray()
                        val type = if (sse) "text/event-stream" else "application/json"
                        val output = peer.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nTransfer-Encoding: chunked\r\n\r\n${body.size.toString(16)}\r\n".toByteArray())
                        output.write(body)
                        output.write("\r\n".toByteArray())
                        output.flush()
                        release.await(5, TimeUnit.SECONDS)
                    }
                } catch (_: Exception) { }
            }
            try {
                val config = AiSettings(baseUrl = "http://127.0.0.1:${server.localPort}/v1", model = "gpt-image-test", apiKey = "local-only")
                val http = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
                val started = System.nanoTime()
                if (chat) assertEquals("done", AiClient(http).complete(config, listOf(AgentMessage("user", "draw")), emptyList()).content)
                else assertArrayEquals(png, ImageGenerationClient(http).generate(config, "draw", "auto").bytes)
                val elapsed = (System.nanoTime() - started) / 1_000_000
                println("complete response, proxy still open: mode=$mode elapsed=${elapsed}ms")
                assertTrue("Client waited for HTTP completion: ${elapsed}ms", elapsed < 1900)
                assertEquals(1L, release.count)
            } finally {
                release.countDown(); server.close(); worker.join(2500)
            }
        }
    }
}
