package dev.pocketagent.agent

import dev.pocketagent.data.AiSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ImageGenerationClientTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6cKAAAAAASUVORK5CYII=")
    private data class Captured(val line: String, val headers: String, val body: String)
    private class Server(private val response: ByteArray, private val status: Int = 200, private val wait: Boolean = false, private val bodyDelayMs: Long = 0, private val chunkDelayMs: Long = 0, private val missingBytes: Int = 0) : AutoCloseable {
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        val captured = CompletableFuture<Captured>()
        val worker = thread(isDaemon = true) {
            try {
                socket.accept().use { peer ->
                    peer.soTimeout = 5000
                    val input = peer.getInputStream()
                    fun line(): String {
                        val text = StringBuilder()
                        while (true) {
                            val b = input.read()
                            check(b >= 0)
                            if (b == 10) return text.toString().trimEnd('\r')
                            text.append(b.toChar())
                        }
                    }
                    val first = line()
                    val headers = mutableListOf<String>()
                    while (true) { val h = line(); if (h.isEmpty()) break; headers += h }
                    val size = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val body = ByteArray(size)
                    var offset = 0
                    while (offset < size) { val n = input.read(body, offset, size - offset); check(n > 0); offset += n }
                    captured.complete(Captured(first, headers.joinToString("\n"), body.toString(Charsets.UTF_8)))
                    if (wait) { input.read(); return@use }
                    val output = peer.getOutputStream()
                    output.write(("HTTP/1.1 $status Test\r\nContent-Length: ${response.size + missingBytes}\r\nConnection: close\r\n\r\n").toByteArray())
                    output.flush()
                    if (bodyDelayMs > 0) Thread.sleep(bodyDelayMs)
                    if (chunkDelayMs > 0) {
                        val split = response.size / 2
                        output.write(response, 0, split)
                        output.flush()
                        Thread.sleep(chunkDelayMs)
                        output.write(response, split, response.size - split)
                    } else output.write(response)
                }
            } catch (e: Throwable) { captured.completeExceptionally(e) }
        }
        override fun close() { socket.close(); worker.join(5500) }
    }
    private fun client() = ImageGenerationClient(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).build())
    private fun config(url: String) = AiSettings(baseUrl = url, model = "image-test", apiKey = "image-secret")

    @Test fun defaultImageToolWorksWithoutSeparateConfiguration() = runBlocking {
        val json = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}"""
        Server(json.toByteArray()).use { server ->
            val settings = AiSettings(baseUrl = server.url + "/v1", model = "chat-test", apiKey = "current-key")
            assertArrayEquals(png, client().generate(settings, "test", "1024x1024").bytes)
            val request = server.captured.get(5, TimeUnit.SECONDS)
            assertTrue(request.headers.contains("Authorization: Bearer current-key"))
            assertEquals(settings.model, JSONObject(request.body).getString("model"))
        }
    }

    @Test fun reportsActualReceiveAndDecodeStages() = runBlocking {
        val json = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}"""
        Server(json.toByteArray()).use { server ->
            val updates = java.util.Collections.synchronizedList(mutableListOf<ImageGenerationProgress>())
            client().generate(config(server.url), "test", "1024x1024") { updates.add(it) }
            assertEquals(ImageGenerationStage.WAITING, updates.first().stage)
            assertEquals(ImageGenerationStage.DECODING, updates.last().stage)
            assertTrue(updates.any { it.stage == ImageGenerationStage.RECEIVING && it.receivedBytes == json.toByteArray().size.toLong() })
        }
    }

    @Test fun multiMegabyteBodyWithProductionProgressIsNotThrottledByClient() = runBlocking {
        // PNG signature is sufficient for the transport client; no Bitmap is decoded here.
        val image = png + ByteArray(3 * 1024 * 1024)
        val json = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(image)}"}]}""".toByteArray()
        Server(json).use { server ->
            val updates = java.util.Collections.synchronizedList(mutableListOf<ImageGenerationProgress>())
            val state = kotlinx.coroutines.flow.MutableStateFlow(ImageGenerationProgress(ImageGenerationStage.WAITING))
            val start = System.nanoTime()
            val result = client().generate(config(server.url), "test", "auto") {
                state.value = it
                updates.add(it)
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertArrayEquals(image, result.bytes)
            val final = updates.last { it.stage == ImageGenerationStage.RECEIVING }
            assertEquals(json.size.toLong(), final.receivedBytes)
            assertEquals("http/1.1", final.protocol)
            assertTrue(final.readWaitMs <= final.transferMs + 5)
            assertTrue("4 MiB response took ${elapsedMs}ms", elapsedMs < 4000)
            assertTrue("Progress delivery must be bounded", updates.size < 30)
            println("IMAGE_LOCAL_THROUGHPUT bytes=${json.size} elapsed_ms=$elapsedMs read_wait_ms=${final.readWaitMs} transfer_ms=${final.transferMs}")
        }
    }

    @Test fun delayedBodyIsCountedAsWaitNotTransfer() = runBlocking {
        val json = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}"""
        Server(json.toByteArray(), bodyDelayMs = 350).use { server ->
            val updates = java.util.Collections.synchronizedList(mutableListOf<ImageGenerationProgress>())
            client().generate(config(server.url), "test", "auto") { updates.add(it) }
            val complete = updates.last { it.stage == ImageGenerationStage.RECEIVING }
            assertTrue("wait=${complete.waitMs}", complete.waitMs >= 300)
            assertTrue("transfer=${complete.transferMs}", complete.transferMs < 200)
        }
    }

    @Test fun slowBodyIsAttributedToReadWaitRatherThanParsing() = runBlocking {
        val image = png + ByteArray(64 * 1024)
        val json = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(image)}"}]}""".toByteArray()
        Server(json, chunkDelayMs = 500).use { server ->
            val updates = java.util.Collections.synchronizedList(mutableListOf<ImageGenerationProgress>())
            assertArrayEquals(image, client().generate(config(server.url), "test", "auto") { updates.add(it) }.bytes)
            val final = updates.last { it.stage == ImageGenerationStage.RECEIVING }
            assertTrue("Read wait ${final.readWaitMs}ms", final.readWaitMs >= 350)
            assertTrue("Transfer ${final.transferMs}ms", final.transferMs >= 350)
            assertEquals(json.size.toLong(), final.receivedBytes)
        }
    }

    @Test fun responseReadTimeoutHasSafeActionableMessageAndNoRetry() = runBlocking {
        val json = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}"""
        Server(json.toByteArray(), bodyDelayMs = 1500).use { server ->
            val shortCall = ImageGenerationClient(OkHttpClient.Builder().callTimeout(500, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false).build())
            try { shortCall.generate(config(server.url), "test", "auto"); fail("Expected timeout") }
            catch (error: AgentException) {
                assertTrue(error.message.orEmpty().contains("出图等待超时"))
                assertTrue(error.message.orEmpty().contains("不会自动重试"))
                assertFalse(error.message.orEmpty().contains("image-secret"))
            }
        }
    }

    @Test fun truncatedBodyReportsProtocolFailureWithoutLeakingDetailsOrRetrying() = runBlocking {
        Server("""{"data":[{"b64_json":"partial""".toByteArray(), missingBytes = 1000).use { server ->
            try { client().generate(config(server.url), "test", "auto"); fail("Expected truncated response") }
            catch (error: AgentException) {
                val message = error.message.orEmpty()
                assertTrue(message.contains("图片响应接收中断"))
                assertTrue(message.contains("HTTP 响应协议异常"))
                assertTrue(message.contains("ProtocolException"))
                assertFalse(message.contains("image-secret"))
                assertFalse(message.contains(server.url))
                server.socket.soTimeout = 200
                try { server.socket.accept().use { fail("Unexpected automatic retry") } }
                catch (_: java.net.SocketTimeoutException) { /* No second POST. */ }
            }
        }
    }

    @Test fun headersDoNotReplaceConfiguredFirstByteTimeoutWithTransferTimeout() = runBlocking {
        val json = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}"""
        Server(json.toByteArray(), bodyDelayMs = 700).use { server ->
            val configured = ImageGenerationClient(OkHttpClient.Builder()
                .readTimeout(150, TimeUnit.MILLISECONDS).callTimeout(4, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).build())
            try { configured.generate(config(server.url), "test", "auto"); fail("Expected configured first-byte timeout") }
            catch (error: AgentException) {
                assertTrue(error.message.orEmpty().contains("出图等待超时"))
                assertTrue(error.message.orEmpty().contains("SocketTimeoutException"))
                assertFalse(error.message.orEmpty().contains("image-secret"))
            }
        }
    }

    @Test fun switchingSelectedModelChangesNextImageRequest() = runBlocking {
        val settings = AiSettings(baseUrl = "https://example.com/v1", model = "first-model", apiKey = "current-key")
        val json = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}"""
        Server(json.toByteArray()).use { server ->
            val switched = settings.copy(baseUrl = server.url, model = "user-selected-second-model")
            client().generate(switched, "test", "1024x1024")
            assertTrue(server.captured.get(5, TimeUnit.SECONDS).headers.contains("Authorization: Bearer current-key"))
            assertEquals("user-selected-second-model", JSONObject(server.captured.get(5, TimeUnit.SECONDS).body).getString("model"))
        }
    }

    @Test fun switchingProfileUsesItsServiceModelAndKey() = runBlocking {
        val previous = AiSettings(baseUrl = "https://previous.example/v1", model = "old-model", apiKey = "old-key")
        val json = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}"""
        Server(json.toByteArray()).use { server ->
            val selected = previous.copy(baseUrl = server.url + "/v2", model = "selected-model", apiKey = "selected-key")
            client().generate(selected, "test", "1024x1024")
            val request = server.captured.get(5, TimeUnit.SECONDS)
            assertTrue(request.line.startsWith("POST /v2/images/generations "))
            assertTrue(request.headers.contains("Authorization: Bearer selected-key"))
            assertFalse(request.headers.contains("old-key"))
            assertEquals("selected-model", JSONObject(request.body).getString("model"))
        }
    }

    @Test fun promptTreatsImageGenerationAsBuiltIn() {
        val prompt = agentSystemPrompt(AgentPermissions())
        assertTrue(prompt.contains("default built-in capability"))
        assertTrue(prompt.contains("there are no separate image settings"))
        assertFalse(prompt.contains("If configuration is missing, explain how to configure it"))
    }

    @Test fun normalizesBaseAndFullEndpoint() {
        assertEquals("https://example.com/v1/images/generations", ImageGenerationClient.generationsUrl(" https://example.com/v1/ ").toString())
        assertEquals("https://example.com/v1/images/generations", ImageGenerationClient.generationsUrl("https://example.com/v1/images/generations").toString())
    }

    @Test fun rejectsUnsafeOrChatEndpoints() {
        listOf("https://user:secret@example.com/v1", "https://example.com/v1?key=secret", "https://example.com/v1#x", "https://example.com/v1/chat/completions", "file:///tmp").forEach {
            try { ImageGenerationClient.generationsUrl(it); fail("Accepted $it") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun base64ResponseAndRequestContract() = runBlocking {
        val json = JSONObject().put("data", org.json.JSONArray().put(JSONObject().put("b64_json", Base64.getEncoder().encodeToString(png))))
        Server(json.toString().toByteArray()).use { server ->
            val result = client().generate(config(server.url + "/v1"), "可爱的小樱", "1024x1536")
            assertArrayEquals(png, result.bytes)
            assertEquals("image/png", result.mimeType)
            val request = server.captured.get(5, TimeUnit.SECONDS)
            assertTrue(request.line.startsWith("POST /v1/images/generations "))
            assertTrue(request.headers.contains("Authorization: Bearer image-secret"))
            val body = JSONObject(request.body)
            assertEquals("可爱的小樱", body.getString("prompt"))
            assertEquals("image-test", body.getString("model"))
            assertEquals(1, body.getInt("n"))
            assertEquals("1024x1536", body.getString("size"))
            assertFalse(body.has("response_format"))
        }
    }

    @Test fun downloadsUrlWithoutForwardingApiKey() = runBlocking {
        // Same-origin HTTP is supported only for an explicitly configured HTTP service.
        run {
            // Use a single same-origin server for the two responses.
            val socket = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
            val base = "http://127.0.0.1:${socket.localPort}"
            val secondHeaders = CompletableFuture<String>()
            val worker = thread(isDaemon = true) {
                try {
                    repeat(2) { index -> socket.accept().use { peer ->
                        peer.soTimeout = 5000
                        val input = peer.getInputStream().bufferedReader()
                        val headers = mutableListOf<String>()
                        while (true) { val line = input.readLine() ?: error("EOF"); if (line.isEmpty()) break; headers += line }
                        val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                        repeat(length) { input.read() }
                        val body = if (index == 0) """{"data":[{"url":"$base/result.png"}]}""".toByteArray() else png
                        if (index == 1) secondHeaders.complete(headers.joinToString("\n"))
                        peer.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body)
                    } }
                } catch (e: Throwable) { secondHeaders.completeExceptionally(e) }
            }
            try {
                assertArrayEquals(png, client().generate(config(base), "test", "1024x1024").bytes)
                assertFalse(secondHeaders.get(5, TimeUnit.SECONDS).contains("Authorization", true))
            } finally { socket.close(); worker.join(5500) }
        }
    }

    @Test fun serviceErrorsDoNotLeakSecretsOrResponseBody() = runBlocking {
        Server("secret-provider-body image-secret".toByteArray(), 401).use { server ->
            try { client().generate(config(server.url), "test", "1024x1024"); fail() }
            catch (e: AgentException) {
                assertTrue(e.message.orEmpty().contains("401"))
                assertFalse(e.message.orEmpty().contains("image-secret"))
                assertFalse(e.message.orEmpty().contains("secret-provider-body"))
            }
        }
    }

    @Test fun structuredServiceErrorsExplainRejectionWithoutLeakingCredentials() = runBlocking {
        val error = JSONObject().put("message", "Model does not support image generation: image-secret Bearer other-secret sk-private https://cdn.example/image?sig=private")
            .put("type", "invalid_request_error").put("code", "unsupported_model").put("param", "model")
        val json = JSONObject().put("error", error).put("debug", "private-debug-body")
        Server(json.toString().toByteArray(), 400).use { server ->
            try { client().generate(config(server.url), "test", "1024x1024"); fail() }
            catch (e: AgentException) {
                val message = e.message.orEmpty()
                assertTrue(message.contains("HTTP 400"))
                assertTrue(message.contains("Model does not support image generation"))
                assertTrue(message.contains("code=unsupported_model"))
                assertTrue(message.contains("param=model"))
                listOf("image-secret", "other-secret", "sk-private", "sig=private", "cdn.example", "private-debug-body").forEach {
                    assertFalse("Leaked $it", message.contains(it))
                }
            }
        }
    }

    @Test fun oversizedOrUnstructuredErrorsFallBackSafely() = runBlocking {
        listOf("<html>private-proxy-body</html>", JSONObject().put("error", JSONObject().put("message", "x".repeat(20_000))).toString()).forEach { body ->
            Server(body.toByteArray(), 502).use { server ->
                try { client().generate(config(server.url), "test", "1024x1024"); fail() }
                catch (e: AgentException) {
                    assertTrue(e.message.orEmpty().contains("HTTP 502"))
                    assertFalse(e.message.orEmpty().contains("private-proxy-body"))
                    assertTrue(e.message.orEmpty().length < 500)
                }
            }
        }
    }

    @Test fun structuredErrorFieldsAreBounded() = runBlocking {
        val json = JSONObject().put("error", JSONObject().put("message", "x".repeat(2_000)).put("code", JSONObject().put("secret", "not-a-string")))
        Server(json.toString().toByteArray(), 400).use { server ->
            try { client().generate(config(server.url), "test", "1024x1024"); fail() }
            catch (e: AgentException) {
                assertTrue(e.message.orEmpty().length < 500)
                assertFalse(e.message.orEmpty().contains("not-a-string"))
            }
        }
    }

    @Test fun malformedAndNonImageResponsesFail() = runBlocking {
        listOf("not json", """{"data":[]}""", """{"data":[{"b64_json":"not base64!"}]}""",
            """{"data":[{"b64_json":"aGVsbG8="}]}""").forEach { response ->
            Server(response.toByteArray()).use { server ->
                try { client().generate(config(server.url), "test", "1024x1024"); fail("Accepted $response") }
                catch (_: AgentException) { }
            }
        }
    }

    @Test fun cancellationStopsAnInFlightRequest() = runBlocking {
        Server(ByteArray(0), wait = true).use { server ->
            val job = async(Dispatchers.IO) { client().generate(config(server.url), "test", "1024x1024") }
            withTimeout(3000) { while (!server.captured.isDone) delay(10) }
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }

    @Test fun validatesSettingsPromptAndSizeBeforeNetworking() = runBlocking {
        for ((settings, prompt, size) in listOf(
            Triple(AiSettings(), "test", "1024x1024"),
            Triple(config("http://127.0.0.1:1").copy(model = ""), "test", "1024x1024"),
            Triple(config("http://127.0.0.1:1").copy(baseUrl = ""), "test", "1024x1024"),
            Triple(config("http://127.0.0.1:1"), " ", "1024x1024"),
            Triple(config("http://127.0.0.1:1"), "test", "9:16"))) {
            try { client().generate(settings, prompt, size); fail() } catch (_: IllegalArgumentException) { }
        }
    }
}
