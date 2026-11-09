package dev.pocketagent.agent

import dev.pocketagent.data.AiSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class GeneratedImage(val bytes: ByteArray, val extension: String) {
    val mimeType: String get() = if (extension == "jpg") "image/jpeg" else "image/$extension"
}

internal enum class ImageGenerationStage { WAITING, RECEIVING, DOWNLOADING, DECODING }
internal data class ImageGenerationProgress(
    val stage: ImageGenerationStage,
    val receivedBytes: Long = 0,
    val totalBytes: Long? = null,
    val waitMs: Long = 0,
    val transferMs: Long = 0,
    val recentBytesPerSecond: Long? = null,
    val measuredAtNanos: Long = 0,
    val readWaitMs: Long = 0,
    val protocol: String = "",
)

/** No automatic POST retry: a retry can create and bill a second image. */
internal class ImageGenerationClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(6, TimeUnit.MINUTES)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    suspend fun generate(settings: AiSettings, prompt: String, size: String): GeneratedImage =
        generate(settings, prompt, size) { }

    suspend fun generate(settings: AiSettings, prompt: String, size: String,
        onProgress: (ImageGenerationProgress) -> Unit): GeneratedImage {
        require(settings.baseUrl.isNotBlank() && settings.model.isNotBlank() && settings.apiKey.isNotBlank()) { "当前服务缺少出图所需的地址、模型或 API 密钥，请检查模型服务配置" }
        require(prompt.isNotBlank() && prompt.length <= 16_000) { "prompt 需要 1–16000 字符" }
        require(size in SIZES) { "不支持的图片尺寸: $size" }
        val endpoint = generationsUrl(settings.baseUrl)
        val payload = JSONObject().put("model", settings.model.trim()).put("prompt", prompt)
            .put("n", 1).put("size", size)
        // Do not force response_format: GPT Image returns base64, other providers may return URLs.
        onProgress(ImageGenerationProgress(ImageGenerationStage.WAITING))
        val response = requestBytes(Request.Builder().url(endpoint)
            .header("Authorization", "Bearer ${settings.apiKey.trim()}")
            .post(payload.toString().toRequestBody("application/json".toMediaType()).withoutAutomaticReplay()).build(), MAX_JSON_BYTES, ImageGenerationStage.RECEIVING, onProgress)
        onProgress(ImageGenerationProgress(ImageGenerationStage.DECODING))
        val item = runCatching { JSONObject(response.toString(Charsets.UTF_8)).optJSONArray("data")?.optJSONObject(0) }
            .getOrNull() ?: throw AgentException("出图响应缺少 data[0]，请确认服务支持 /images/generations")
        val encoded = item.optString("b64_json").takeUnless { it.isBlank() || it == "null" }
        val bytes = if (encoded != null) {
            require(encoded.length <= ((MAX_IMAGE_BYTES + 2L) / 3 * 4)) { "生成图片超过 32 MB" }
            runCatching { Base64.getDecoder().decode(encoded) }
                .getOrElse { throw AgentException("出图服务返回了无效 Base64 图片") }
        } else {
            val url = runCatching { item.getString("url").toHttpUrl() }
                .getOrElse { throw AgentException("出图响应没有 b64_json 或有效图片 URL") }
            require(url.username.isEmpty() && url.password.isEmpty()) { "图片 URL 不允许嵌入凭据" }
            require(url.isHttps || (!endpoint.isHttps && url.host == endpoint.host && url.port == endpoint.port)) {
                "图片下载必须使用 HTTPS"
            }
            // A signed image URL may be hosted elsewhere. Never forward the API key to it.
            onProgress(ImageGenerationProgress(ImageGenerationStage.DOWNLOADING))
            requestBytes(Request.Builder().url(url).get().build(), MAX_IMAGE_BYTES, ImageGenerationStage.DOWNLOADING, onProgress)
        }
        require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES) { "生成图片为空或超过 32 MB" }
        return GeneratedImage(bytes, imageExtension(bytes))
    }

    private suspend fun requestBytes(request: Request, limit: Int, stage: ImageGenerationStage,
        onProgress: (ImageGenerationProgress) -> Unit): ByteArray = suspendCancellableCoroutine { continuation ->
        val meter = ImageTransferMeter()
        var measuredInput: ImageResponseInput? = null
        var protocol = ""
        var receivedBytes = 0L
        var totalBytes: Long? = null
        fun report(received: Long, total: Long?) {
            receivedBytes = received
            totalBytes = total
            onProgress(meter.progress(stage, received, total).copy(
                readWaitMs = (measuredInput?.readWaitNanos ?: 0) / 1_000_000,
                protocol = protocol,
            ))
        }
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(networkError(e, receiving = false))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        protocol = it.protocol.toString()
                        // Only expose bounded, structured API error fields after redaction.
                        // Download failures can contain signed URLs; never expose their bodies.
                        // Error bodies can stall too; bound reads before peeking at them.
                        it.body?.source()?.timeout()?.timeout(60, TimeUnit.SECONDS)
                        if (!it.isSuccessful) throw AgentException(serviceError(it, request))
                        val body = it.body ?: throw AgentException("出图服务返回空响应")
                        require(body.contentLength() <= limit) { "出图响应过大" }
                        val total = body.contentLength().takeIf { length -> length > 0 }
                        // Providers may flush headers before generation finishes. Keep the configured
                        // generation read timeout until the first body bytes actually arrive.
                        // Only subsequent idle reads use the shorter transfer timeout.
                        body.source().timeout().timeout(client.readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                        report(0, total)
                        ImageResponseInput(body.byteStream(), onFirstBytes = {
                            body.source().timeout().timeout(60, TimeUnit.SECONDS)
                        }).also { measuredInput = it }.use { input ->
                            var lastReported = 0L
                            if (stage == ImageGenerationStage.RECEIVING) {
                                return@use JsonResponseReader.read(input, limit) { received ->
                                    val now = System.nanoTime()
                                    if (now - lastReported >= 250_000_000L) {
                                        report(received, total)
                                        lastReported = now
                                    }
                                }.also { bytes -> report(bytes.size.toLong(), total) }
                            }
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count == -1) break
                                require(output.size().toLong() + count <= limit) { "出图响应过大" }
                                output.write(buffer, 0, count)
                                val now = System.nanoTime()
                                if (now - lastReported >= 250_000_000L) {
                                    report(output.size().toLong(), total)
                                    lastReported = now
                                }
                            }
                            report(output.size().toLong(), total)
                            output.toByteArray()
                        }
                    }
                }
                if (continuation.isActive) result.fold(continuation::resume) { error ->
                    // Preserve the last measured body-read time even when a read times out.
                    runCatching { report(receivedBytes, totalBytes) }
                    continuation.resumeWithException(if (error is IOException) networkError(error, receiving = receivedBytes > 0) else error)
                }
            }
        })
    }

    private fun networkError(error: IOException, receiving: Boolean): AgentException {
        // Exception messages may contain signed URLs. Inspect types, never display messages.
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        val timeout = causes.any { it is java.io.InterruptedIOException } || error.message.orEmpty().contains("timeout", true)
        val reason = when {
            timeout && receiving -> "图片响应接收超时（连续读取空闲最多 60 秒，单次网络调用最多 6 分钟）"
            timeout -> "出图等待超时（等待响应最多 5 分钟，单次网络调用最多 6 分钟）"
            receiving -> "图片响应接收中断，请检查服务与网络"
            else -> "出图连接中断，请检查服务与网络"
        }
        val category = when {
            timeout -> "超时"
            causes.any { it is java.net.UnknownHostException } -> "DNS 解析失败"
            causes.any { it is javax.net.ssl.SSLException } -> "TLS 连接失败"
            causes.any { it is java.net.ConnectException } -> "建立连接失败"
            causes.any { it is java.net.SocketException } -> "连接被重置或关闭"
            causes.any { it is java.io.EOFException } -> "响应提前结束"
            causes.any { it.javaClass.name == "okhttp3.internal.http2.StreamResetException" } -> "HTTP/2 流被重置"
            causes.any { it.javaClass.name == "okhttp3.internal.http2.ConnectionShutdownException" } -> "HTTP/2 连接被关闭"
            causes.any { it is java.net.ProtocolException } -> "HTTP 响应协议异常"
            else -> "网络读写失败"
        }
        // Only allow Java identifier characters in the diagnostic type, never arbitrary text.
        val type = error.javaClass.simpleName.filter { it.isLetterOrDigit() || it == '_' }.take(64)
        return AgentException("$reason（$category；$type）；服务端可能已计费，请先检查服务端记录，不会自动重试")
    }

    private fun serviceError(response: Response, request: Request): String {
        val fallback = "出图请求失败 HTTP ${response.code}，请确认当前所选模型和服务支持图片生成，并检查请求参数、密钥及额度"
        if (request.method != "POST") return fallback
        val detail = runCatching {
            // Never log raw bodies (HTML, proxy diagnostics, prompts, signed URLs, etc.).
            val bytes = response.peekBody(16 * 1024L).bytes()
            val error = JSONObject(bytes.toString(Charsets.UTF_8)).optJSONObject("error")
                ?: return@runCatching null
            val key = request.header("Authorization")?.removePrefix("Bearer ").orEmpty()
            fun field(name: String): String? {
                val raw = error.opt(name) as? String ?: return null
                return raw.let { if (key.isNotEmpty()) it.replace(key, "[REDACTED]") else it }
                    .replace(Regex("(?i)https?://[^\\s<>\\\"]+"), "[URL]")
                    .replace(Regex("(?i)Bearer\\s+[^\\s,;]+"), "Bearer [REDACTED]")
                    .replace(Regex("(?i)\\bsk-[a-z0-9_-]+"), "[REDACTED]")
                    .replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(400).takeIf { it.isNotEmpty() }
            }
            listOfNotNull(field("message"), field("type")?.let { "type=$it" },
                field("code")?.let { "code=$it" }, field("param")?.let { "param=$it" })
                .joinToString("; ").takeIf { it.isNotEmpty() }
        }.getOrNull()
        return if (detail == null) fallback else "出图请求失败 HTTP ${response.code}: $detail"
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
        private const val MAX_JSON_BYTES = 48 * 1024 * 1024
        val SIZES = setOf("auto", "1024x1024", "1536x1024", "1024x1536", "1792x1024", "1024x1792", "512x512", "256x256")

        internal fun generationsUrl(baseUrl: String): HttpUrl {
            val url = baseUrl.trim().trimEnd('/').toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "出图服务地址不能包含凭据、查询参数或片段"
            }
            require(!url.encodedPath.endsWith("/chat/completions")) { "请填写出图服务 Base URL，不要填写 /chat/completions" }
            return if (url.encodedPath.endsWith("/images/generations")) url
            else url.newBuilder().addPathSegments("images/generations").build()
        }

        internal fun imageExtension(bytes: ByteArray): String = when {
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "png"
            bytes.size >= 3 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte() -> "jpg"
            bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "webp"
            else -> throw AgentException("出图服务返回的不是支持的 PNG、JPEG 或 WebP 图片")
        }
    }
}
