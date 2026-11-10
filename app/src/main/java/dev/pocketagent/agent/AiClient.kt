package dev.pocketagent.agent

import dev.pocketagent.data.AiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class AiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    @Volatile
    private var activeAgentCall: Call? = null
    private val prefixTracker = PromptPrefixTracker()
    private val MAX_CHAT_RESPONSE_BYTES = 48L * 1024 * 1024

    suspend fun listModels(settings: AiSettings): List<ModelInfo> = withContext(Dispatchers.IO) {
        if (settings.apiKey.isBlank()) {
            throw AgentException("请先填写 API Key")
        }
        if (settings.baseUrl.isBlank()) {
            throw AgentException("API Base URL 不能为空")
        }

        val request = Request.Builder()
            .url(modelsUrl(settings.baseUrl))
            .header("Authorization", "Bearer ${settings.apiKey.trim()}")
            .header("Accept", "application/json")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw AgentException("获取模型列表失败 HTTP ${response.code}: ${body.take(1_000)}")
            }
            val json = runCatching { JSONObject(body) }
                .getOrElse { throw AgentException("模型列表不是有效 JSON") }
            val data = json.optJSONArray("data")
                ?: throw AgentException("模型列表缺少 data 数组")
            buildList {
                for (index in 0 until data.length()) {
                    val model = data.optJSONObject(index) ?: continue
                    val id = model.optString("id")
                        .ifBlank { model.optString("name") }
                    if (id.isNotBlank()) {
                        val declaredEfforts = extractReasoningEfforts(model)
                        add(
                            ModelInfo(
                                id = id,
                                reasoningEfforts = declaredEfforts.ifEmpty {
                                    ReasoningEffortSupport.infer(id)
                                },
                            ),
                        )
                    }
                }
            }.distinctBy { it.id }.sortedBy { it.id }
        }
    }

    suspend fun complete(
        settings: AiSettings,
        messages: List<AgentMessage>,
        tools: List<ToolDefinition>,
        onRetry: (RetryNotice) -> Unit = {},
        onDelta: (String) -> Unit = {},
        onReasoningDelta: (String) -> Unit = {},
        onStreamReset: (Int) -> Unit = {},
        onDiagnostic: (String, String) -> Unit = { _, _ -> },
        onToolCallDelta: (Int, String, String) -> Unit = { _, _, _ -> },
    ): AssistantTurn = withContext(Dispatchers.IO) {
        if (settings.apiKey.isBlank()) {
            throw AgentException("请先在设置里填写 API Key")
        }
        if (settings.baseUrl.isBlank()) {
            throw AgentException("API Base URL 不能为空")
        }
        if (settings.model.isBlank()) {
            throw AgentException("模型名不能为空")
        }

        // A new reasoning response must not change the serialization of older messages.
        val includeReasoningContent = settings.reasoningEffort != "auto"
        val requestJson = JSONObject().apply {
            put("model", settings.model.trim())
            put("messages", messages.toMessagesJson(includeReasoningContent))
            put("tool_choice", "auto")
            put("stream", true)
            put(
                "stream_options",
                JSONObject().put("include_usage", true),
            )
            if (tools.isNotEmpty()) put("tools", tools.toToolsJson())
            if (settings.reasoningEffort.isNotBlank() && settings.reasoningEffort != "auto") {
                put("reasoning_effort", settings.reasoningEffort)
            }
        }

        val diagnostic = prefixTracker.record(settings.baseUrl, requestJson)
        onDiagnostic("缓存请求", diagnostic.description)
        val request = Request.Builder()
            .url(chatCompletionsUrl(settings.baseUrl))
            .header("Authorization", "Bearer ${settings.apiKey.trim()}")
            .header("Accept", "text/event-stream, application/json")
            .post(
                requestJson.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType()).withoutAutomaticReplay(),
            )
            .build()

        var lastError: Exception? = null
        var receivedStreamOutput = false
        var streamedContentChars = 0
        val imageDetector = InlineImagePreview()
        // Image gateways may stay silent for minutes before returning a single image frame.
        // Do not replay a potentially billed generation, even before any image bytes arrive.
        val imageRequest = isImageGenerationModel(settings.model)
        val requestAttempts = if (imageRequest) 1 else MAX_REQUEST_ATTEMPTS
        for (attempt in 1..requestAttempts) {
            currentCoroutineContext().ensureActive()
            receivedStreamOutput = false
            streamedContentChars = 0
            try {
                return@withContext executeCompletion(
                    request = request,
                    imageRequest = imageRequest,
                    onToolCallDelta = onToolCallDelta,
                    onUsageDiagnostic = { raw ->
                        onDiagnostic("缓存用量", "request=${diagnostic.id} attempt=$attempt $raw")
                    },
                    onDelta = { delta ->
                        receivedStreamOutput = true
                        streamedContentChars += delta.length
                        imageDetector.append(delta)
                        onDelta(delta)
                    },
                    onReasoningDelta = { delta ->
                        receivedStreamOutput = true
                        onReasoningDelta(delta)
                    },
                )
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                lastError = error
                if (imageRequest || imageDetector.hidden) throw AgentException("图片请求中断或超时；服务端可能仍在生成或已计费，不会自动重试，请检查服务端记录").also { it.retryAttempts = attempt - 1 }
                val interrupted = receivedStreamOutput
                if (attempt == requestAttempts || !error.isTransientNetworkError()) {
                    throw AgentException(
                        if (interrupted) {
                            "模型输出中断：${error.message ?: "网络连接已断开"}"
                        } else if (error.isTimeout()) {
                            "模型在 ${REQUEST_TIMEOUT_SECONDS} 秒内没有响应，" +
                                "请检查接口地址、模型状态或网络"
                        } else {
                            error.message ?: "模型网络请求失败"
                        },
                    ).also { it.retryAttempts = attempt - 1 }
                }
                val waitMs = retryDelayMs(attempt)
                if (streamedContentChars > 0) {
                    onStreamReset(streamedContentChars)
                }
                onRetry(
                    RetryNotice(
                        attempt = attempt,
                        maxAttempts = MAX_RETRIES,
                        delaySeconds = (waitMs / 1_000).toInt(),
                        reason = if (interrupted) {
                            "模型输出意外中断，正在重新生成"
                        } else if (error.isTimeout()) {
                            "等待 ${REQUEST_TIMEOUT_SECONDS} 秒没有收到响应"
                        } else {
                            "网络连接中断，正在重新连接"
                        },
                    ),
                )
                delay(waitMs)
            } catch (error: AgentHttpException) {
                lastError = error
                if (imageRequest || imageDetector.hidden || attempt == requestAttempts || !error.isRetryable()) {
                    throw AgentException(
                        message = error.message.orEmpty(),
                        retryAfterSeconds = error.retryAfterSeconds,
                    ).also { it.retryAttempts = attempt - 1 }
                }
                val waitMs = error.retryAfterSeconds
                    ?.times(1_000)
                    ?.coerceIn(1_000, 30_000)
                    ?: retryDelayMs(attempt)
                if (streamedContentChars > 0) {
                    onStreamReset(streamedContentChars)
                }
                onRetry(
                    RetryNotice(
                        attempt = attempt,
                        maxAttempts = MAX_RETRIES,
                        delaySeconds = (waitMs / 1_000).toInt(),
                        reason = error.retryReason(),
                    ),
                )
                delay(waitMs)
            } catch (error: AgentException) {
                lastError = error
                if (imageRequest || imageDetector.hidden || attempt == requestAttempts || !error.isRetryable()) {
                    throw error.withRetryAttempts(attempt - 1)
                }
                val waitMs = error.retryAfterSeconds
                    ?.times(1_000)
                    ?.coerceIn(1_000, 30_000)
                    ?: retryDelayMs(attempt)
                if (streamedContentChars > 0) {
                    onStreamReset(streamedContentChars)
                }
                onRetry(
                    RetryNotice(
                        attempt = attempt,
                        maxAttempts = MAX_RETRIES,
                        delaySeconds = (waitMs / 1_000).toInt(),
                        reason = error.retryReason(),
                    ),
                )
                delay(waitMs)
            }
        }
        throw AgentException(lastError?.message ?: "模型网络请求失败")
    }

    private fun executeCompletion(
        request: Request,
        imageRequest: Boolean,
        onUsageDiagnostic: (String) -> Unit,
        onDelta: (String) -> Unit,
        onReasoningDelta: (String) -> Unit,
        onToolCallDelta: (Int, String, String) -> Unit,
    ): AssistantTurn {
        val builder = httpClient.newBuilder().retryOnConnectionFailure(false)
        if (imageRequest) {
            builder.readTimeout(IMAGE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // A stream can keep emitting heartbeats forever without ever hitting readTimeout.
            val limit = TimeUnit.MINUTES.toMillis(7)
            builder.callTimeout(httpClient.callTimeoutMillis.takeIf { it > 0 }?.toLong()?.coerceAtMost(limit) ?: limit,
                TimeUnit.MILLISECONDS)
        }
        val call = builder.build().newCall(request)
        activeAgentCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val key = request.header("Authorization")?.removePrefix("Bearer ").orEmpty()
                    val body = safeServiceError(response.peekBody(16 * 1024L).string(), key)
                    throw AgentHttpException(
                        statusCode = response.code,
                        body = body,
                        retryAfterSeconds = response.header("Retry-After")
                            ?.trim()
                            ?.toLongOrNull(),
                    )
                }
                val responseBody = response.body
                    ?: throw AgentException("模型返回没有响应体")
                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.contains("text/event-stream", ignoreCase = true)) {
                    return parseStreamingResponse(
                        source = responseBody.source(),
                        imageRequest = imageRequest,
                        apiKey = request.header("Authorization")?.removePrefix("Bearer ").orEmpty(),
                        onUsageDiagnostic = onUsageDiagnostic,
                        onDelta = onDelta,
                        onReasoningDelta = onReasoningDelta,
                        onToolCallDelta = onToolCallDelta,
                    )
                }

                val body = JsonResponseReader.read(responseBody.byteStream(), MAX_CHAT_RESPONSE_BYTES.toInt())
                    .toString(Charsets.UTF_8)
                val json = runCatching { JSONObject(body) }
                    .getOrElse { throw AgentException("模型返回不是有效 JSON") }
                val choice = json.optJSONArray("choices")?.optJSONObject(0)
                    ?: throw AgentException("模型返回缺少 choices")
                val message = choice.optJSONObject("message")
                    ?: throw AgentException("模型返回缺少 message")

                val toolCalls = buildList {
                    val calls = message.optJSONArray("tool_calls")
                    if (calls != null) {
                        for (index in 0 until calls.length()) {
                            val toolCall = calls.optJSONObject(index) ?: continue
                            val function = toolCall.optJSONObject("function") ?: continue
                            add(
                                AgentToolCall(
                                    id = toolCall.optString("id", "call_$index"),
                                    name = function.optString("name"),
                                    arguments = function.optString("arguments", "{}"),
                                ),
                            )
                        }
                    }
                }

                val content = message.opt("content")
                    ?.takeUnless { it == JSONObject.NULL }
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
                val reasoningContent = message.opt("reasoning_content")
                    ?.takeUnless { it == JSONObject.NULL }
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
                val usage = json.optJSONObject("usage")?.let {
                    onUsageDiagnostic(it.cacheUsageDiagnostic())
                    it.toTokenUsage()
                }

                return AssistantTurn(
                    content = content,
                    reasoningContent = reasoningContent,
                    toolCalls = toolCalls,
                    finishReason = choice.optString("finish_reason").takeIf { it.isNotBlank() },
                    usage = usage,
                )
            }
        } finally {
            if (activeAgentCall === call) activeAgentCall = null
        }
    }

    private fun parseStreamingResponse(
        source: BufferedSource,
        imageRequest: Boolean,
        apiKey: String,
        onUsageDiagnostic: (String) -> Unit,
        onDelta: (String) -> Unit,
        onReasoningDelta: (String) -> Unit,
        onToolCallDelta: (Int, String, String) -> Unit,
    ): AssistantTurn {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = linkedMapOf<Int, StreamingToolCall>()
        var finishReason: String? = null
        val usage = StreamingTokenUsage()
        var usageFrames = 0
        var firstUsageFields = ""
        var lastUsageFields = ""
        var sawDone = false

        var responseBytes = 0L
        while (true) {
            val remaining = MAX_CHAT_RESPONSE_BYTES - responseBytes
            val newline = source.indexOf('\n'.code.toByte(), 0L, remaining + 1L)
            if (newline == -1L && source.buffer.size > remaining) throw AgentException("模型响应超过 48 MB，不会自动重试")
            val line = source.readUtf8Line() ?: break
            responseBytes += line.toByteArray(Charsets.UTF_8).size + 1L
            if (responseBytes > MAX_CHAT_RESPONSE_BYTES) throw AgentException("模型响应超过 48 MB，不会自动重试")
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith(":")) continue
            if (!trimmed.startsWith("data:")) continue
            val data = trimmed.removePrefix("data:").trimStart()
            if (data == "[DONE]") {
                sawDone = true
                break
            }
            val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
            json.optJSONObject("error")?.let { error ->
                val message = error.optString("message").ifBlank {
                    error.optString("detail").ifBlank { error.toString() }
                }
                throw AgentException(
                    "模型流式响应失败: ${safeServiceError(message, apiKey)}",
                    retryAfterSeconds = streamRetryAfterSeconds(error),
                )
            }
            json.optJSONObject("usage")?.let {
                lastUsageFields = it.cacheUsageDiagnostic()
                if (usageFrames++ == 0) firstUsageFields = lastUsageFields
                usage.update(it)
            }
            val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: continue
            choice.optString("finish_reason")
                .takeIf { it.isNotBlank() }
                ?.let { finishReason = it }
            val delta = choice.optJSONObject("delta")
                ?: choice.optJSONObject("message")
                ?: if (imageRequest && finishReason != null) break else continue

            delta.opt("reasoning_content")
                ?.takeUnless { it == JSONObject.NULL }
                ?.toString()
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    reasoning.append(it)
                    onReasoningDelta(it)
                }
            delta.opt("reasoning")
                ?.takeUnless { it == JSONObject.NULL }
                ?.toString()
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    reasoning.append(it)
                    onReasoningDelta(it)
                }
            delta.opt("content")
                ?.takeUnless { it == JSONObject.NULL }
                ?.toString()
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    content.append(it)
                    onDelta(it)
                }
            val calls = delta.optJSONArray("tool_calls")
            if (calls != null) {
                for (position in 0 until calls.length()) {
                    val call = calls.optJSONObject(position) ?: continue
                    val index = call.optInt("index", position)
                    val target = toolCalls.getOrPut(index) { StreamingToolCall() }
                    call.optString("id").takeIf { it.isNotBlank() }?.let { target.id = it }
                    val function = call.optJSONObject("function")
                    function?.optString("name")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { target.name = it }
                    function?.opt("arguments")
                        ?.takeUnless { it == JSONObject.NULL }
                        ?.toString()
                        ?.let(target.arguments::append)
                    onToolCallDelta(index, target.name, target.arguments.toString())
                }
            }
            // Image gateways may send a terminal finish_reason but never [DONE] or EOF.
            // Content in the terminal frame is already appended; do not wait minutes for closure.
            // Normal chat still reads its separate usage frame as before.
            if (imageRequest && finishReason != null) break
        }
        if (!sawDone && finishReason == null) {
            throw IOException("模型流式响应意外中断")
        }
        if (usageFrames > 0) {
            onUsageDiagnostic("frames=$usageFrames first=[$firstUsageFields] last=[$lastUsageFields] merged=[${usage.diagnostic()}]")
        }

        val parsedToolCalls = toolCalls.entries.map { (index, call) ->
            AgentToolCall(
                id = call.id.ifBlank { "call_$index" },
                name = call.name,
                arguments = call.arguments.toString().ifBlank { "{}" },
            )
        }
        val text = content.toString().takeIf { it.isNotBlank() }
        val reasoningText = reasoning.toString().takeIf { it.isNotBlank() }
        if (text == null && reasoningText == null && parsedToolCalls.isEmpty()) {
            throw AgentException("模型流式响应结束，但没有返回内容")
        }
        return AssistantTurn(
            content = text,
            reasoningContent = reasoningText,
            toolCalls = parsedToolCalls,
            finishReason = finishReason,
            usage = usage.value(),
            contentWasStreamed = text != null,
        )
    }

    private data class StreamingToolCall(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    private fun IOException.isTimeout(): Boolean =
        this is SocketTimeoutException ||
            this is java.io.InterruptedIOException ||
            message.orEmpty().contains("timeout", ignoreCase = true)

    fun cancel() {
        activeAgentCall?.cancel()
    }

    private fun extractReasoningEfforts(model: JSONObject): List<String> {
        val values = mutableListOf<String>()
        val capabilities = model.optJSONObject("capabilities")

        fun addArray(array: JSONArray?) {
            if (array == null) return
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is String -> values += item
                    is JSONObject -> {
                        values += item.optString("id")
                        values += item.optString("name")
                        values += item.optString("effort")
                    }
                }
            }
        }

        addArray(model.optJSONArray("reasoning_efforts"))
        addArray(model.optJSONArray("supported_reasoning_efforts"))
        addArray(model.optJSONArray("supported_efforts"))
        addArray(capabilities?.optJSONArray("reasoning_efforts"))
        addArray(capabilities?.optJSONArray("supported_efforts"))

        val reasoning = capabilities?.optJSONObject("reasoning")
            ?: model.optJSONObject("reasoning")
        addArray(reasoning?.optJSONArray("efforts"))
        addArray(reasoning?.optJSONArray("supported_efforts"))
        addArray(reasoning?.optJSONArray("reasoning_efforts"))

        model.optString("reasoning_effort")
            .takeIf { it.isNotBlank() }
            ?.let(values::add)
        capabilities?.optString("reasoning_effort")
            .orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let(values::add)
        return ReasoningEffortSupport.normalize(values)
    }

    private fun chatCompletionsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.endsWith("/chat/completions")) {
            trimmed
        } else {
            "$trimmed/chat/completions"
        }
    }

    private fun modelsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.endsWith("/models")) trimmed else "$trimmed/models"
    }

    private fun List<AgentMessage>.toMessagesJson(
        includeReasoningContent: Boolean,
    ): JSONArray = JSONArray().apply {
        forEach { message ->
            put(
                JSONObject().apply {
                    put("role", message.role)
                    if (message.imagePaths.isEmpty() || message.role == "assistant") {
                        // Generated assistant images stay local; do not re-send megabytes of base64 as text.
                        message.content?.let { put("content", InlineImages.strip(it)) }
                    } else {
                        put("content", multimodalContent(message))
                    }
                    when {
                        message.reasoningContent != null ->
                            put("reasoning_content", message.reasoningContent)
                        includeReasoningContent && message.role == "assistant" ->
                            put("reasoning_content", "")
                    }
                    message.name?.let { put("name", it) }
                    message.toolCallId?.let { put("tool_call_id", it) }
                    if (message.toolCalls.isNotEmpty()) {
                        put(
                            "tool_calls",
                            JSONArray().apply {
                                message.toolCalls.forEach { call ->
                                    put(
                                        JSONObject().apply {
                                            put("id", call.id)
                                            put("type", "function")
                                            put(
                                                "function",
                                                JSONObject().apply {
                                                    put("name", call.name)
                                                    put("arguments", call.arguments)
                                                },
                                            )
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    private fun List<ToolDefinition>.toToolsJson(): JSONArray = JSONArray().apply {
        forEach { tool ->
            put(
                JSONObject().apply {
                    put("type", "function")
                    put(
                        "function",
                        JSONObject().apply {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        },
                    )
                },
            )
        }
    }

    companion object {
        private const val MAX_RETRIES = 5
        private const val MAX_REQUEST_ATTEMPTS = MAX_RETRIES + 1
        private const val REQUEST_TIMEOUT_SECONDS = 90
        private const val IMAGE_READ_TIMEOUT_SECONDS = 300L

        internal fun isImageGenerationModel(model: String): Boolean =
            Regex("(?:^|[-_./])(?:image|images|imagen)(?:$|[-_./0-9])", RegexOption.IGNORE_CASE)
                .containsMatchIn(model.trim())
    }
}

/** Connection failures can be wrapped by a proxy or the HTTP stack; inspect their causes too. */
internal fun IOException.isTransientNetworkError(): Boolean {
    val causes = generateSequence<Throwable>(this) { it.cause }.take(8).toList()
    if (causes.any { it is java.security.cert.CertificateException || it is javax.net.ssl.SSLPeerUnverifiedException || it is java.net.ProtocolException }) return false
    val message = causes.joinToString(" ") { it.message.orEmpty() }.lowercase()
    if ("canceled" in message || "cancelled" in message) return false
    return causes.any {
        it is SocketException || it is UnknownHostException ||
            it is java.io.InterruptedIOException || it is EOFException
    } || listOf(
        "unable to resolve host", "timed out", "connection reset", "connect failed", "failed to connect",
        "connection refused", "network is unreachable", "no route to host", "software caused connection abort",
        "connection abort", "流式响应意外中断", "broken pipe", "unexpected end of stream", "socket closed",
    ).any(message::contains)
}

internal fun AgentHttpException.isRetryable(): Boolean =
    statusCode == 408 ||
        statusCode == 409 ||
        statusCode == 425 ||
        statusCode == 429 ||
        statusCode >= 500

private fun AgentException.withRetryAttempts(attempts: Int): AgentException =
    if (retryAttempts == attempts) {
        this
    } else {
        AgentException(message.orEmpty(), retryAfterSeconds)
            .also { it.retryAttempts = attempts }
    }

internal fun AgentException.isRetryable(): Boolean {
    if (this is AgentHttpException) return isRetryable()
    val text = message.orEmpty().lowercase()
    return RETRYABLE_SERVICE_ERROR_MARKERS.any(text::contains)
}

private fun AgentException.retryReason(): String {
    val text = message.orEmpty()
    val lower = text.lowercase()
    return when {
        "concurrency" in lower ||
            "too many" in lower ||
            "rate limit" in lower ||
            "request limit" in lower ||
            "quota exceeded" in lower ||
            "429" in lower ->
            "服务并发请求已达上限，正在等待后重连"
        "overloaded" in lower ||
            "temporarily unavailable" in lower ||
            "service unavailable" in lower ||
            "internal server error" in lower ||
            "bad gateway" in lower ||
            "gateway timeout" in lower ||
            this is AgentHttpException && statusCode >= 500 ->
            "模型服务暂时不可用，正在等待后重连"
        "stream interrupted" in lower ||
            "stream closed" in lower ||
            "connection reset" in lower ||
            "模型流式响应意外中断" in text ->
            "模型输出意外中断，正在重新生成"
        else -> "服务暂时不可用，正在等待后重连"
    }
}

private fun retryDelayMs(attempt: Int): Long {
    val baseDelayMs = 1_000L
    val maxDelayMs = 30_000L
    val multiplier = 1L shl (attempt - 1).coerceIn(0, 5)
    return (baseDelayMs * multiplier).coerceAtMost(maxDelayMs)
}

private fun streamRetryAfterSeconds(error: JSONObject): Long? =
    (error.opt("retry_after") ?: error.opt("retry_after_seconds"))
        .takeUnless { it == null || it == JSONObject.NULL }
        ?.toString()
        ?.toDoubleOrNull()
        ?.let { seconds ->
            if (seconds >= 1_000) {
                (seconds / 1_000).toLong()
            } else {
                seconds.toLong()
            }
        }
        ?.coerceIn(1, 30)

private val RETRYABLE_SERVICE_ERROR_MARKERS = listOf(
    "concurrency limit",
    "rate limit",
    "too many requests",
    "request limit",
    "quota exceeded",
    "temporarily unavailable",
    "service unavailable",
    "overloaded",
    "server busy",
    "internal server error",
    "bad gateway",
    "gateway timeout",
    "upstream",
    "stream interrupted",
    "stream closed",
    "模型流式响应意外中断",
)

/** Image bytes are encoded only for the wire request; history stores private file paths. */
internal fun multimodalContent(message: AgentMessage): org.json.JSONArray = org.json.JSONArray().apply {
    message.content?.takeIf { it.isNotBlank() }?.let {
        put(org.json.JSONObject().put("type", "text").put("text", it))
    }
    message.imagePaths.forEach { path ->
        val file = java.io.File(path)
        require(file.isFile && file.length() <= 12 * 1024 * 1024) { "图片附件不可用，请重新添加" }
        val encoded = java.util.Base64.getEncoder().encodeToString(file.readBytes())
        put(org.json.JSONObject().put("type", "image_url").put(
            "image_url", org.json.JSONObject().put("url", "data:image/jpeg;base64,$encoded").put("detail", "auto"),
        ))
    }
}
