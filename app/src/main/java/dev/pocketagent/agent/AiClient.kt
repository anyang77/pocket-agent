package dev.pocketagent.agent

import dev.pocketagent.data.AiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

        val includeReasoningContent = settings.reasoningEffort != "auto" ||
            messages.any { it.reasoningContent != null }
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

        val request = Request.Builder()
            .url(chatCompletionsUrl(settings.baseUrl))
            .header("Authorization", "Bearer ${settings.apiKey.trim()}")
            .header("Accept", "text/event-stream, application/json")
            .header("Cache-Control", "no-cache")
            .post(
                requestJson.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            .build()

        var lastError: Exception? = null
        var receivedStreamOutput = false
        var streamedContentChars = 0
        for (attempt in 1..MAX_REQUEST_ATTEMPTS) {
            receivedStreamOutput = false
            streamedContentChars = 0
            try {
                return@withContext executeCompletion(
                    request = request,
                    onDelta = { delta ->
                        receivedStreamOutput = true
                        streamedContentChars += delta.length
                        onDelta(delta)
                    },
                    onReasoningDelta = { delta ->
                        receivedStreamOutput = true
                        onReasoningDelta(delta)
                    },
                )
            } catch (error: IOException) {
                lastError = error
                val interrupted = receivedStreamOutput
                if (attempt == MAX_REQUEST_ATTEMPTS || !error.isTransientNetworkError()) {
                    throw AgentException(
                        if (interrupted) {
                            "模型输出中断：${error.message ?: "网络连接已断开"}"
                        } else if (error.isTimeout()) {
                            "模型在 ${REQUEST_TIMEOUT_SECONDS} 秒内没有响应，" +
                                "请检查接口地址、模型状态或网络"
                        } else {
                            error.message ?: "模型网络请求失败"
                        },
                    )
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
                if (attempt == MAX_REQUEST_ATTEMPTS || !error.isRetryable()) {
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
                if (attempt == MAX_REQUEST_ATTEMPTS || !error.isRetryable()) {
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
        onDelta: (String) -> Unit,
        onReasoningDelta: (String) -> Unit,
    ): AssistantTurn {
        val call = httpClient.newCall(request)
        activeAgentCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
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
                        onDelta = onDelta,
                        onReasoningDelta = onReasoningDelta,
                    )
                }

                val body = responseBody.string()
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
                val usage = json.optJSONObject("usage")?.toTokenUsage()

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
        onDelta: (String) -> Unit,
        onReasoningDelta: (String) -> Unit,
    ): AssistantTurn {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = linkedMapOf<Int, StreamingToolCall>()
        var finishReason: String? = null
        var usage: TokenUsage? = null
        var sawDone = false

        while (true) {
            val line = source.readUtf8Line() ?: break
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
                    "模型流式响应失败: $message",
                    retryAfterSeconds = streamRetryAfterSeconds(error),
                )
            }
            json.optJSONObject("usage")?.let { usage = it.toTokenUsage() }
            val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: continue
            choice.optString("finish_reason")
                .takeIf { it.isNotBlank() }
                ?.let { finishReason = it }
            val delta = choice.optJSONObject("delta")
                ?: choice.optJSONObject("message")
                ?: continue

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
                }
            }
        }
        if (!sawDone && finishReason == null) {
            throw IOException("模型流式响应意外中断")
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
            usage = usage,
            contentWasStreamed = text != null,
        )
    }

    private data class StreamingToolCall(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    private fun IOException.isTransientNetworkError(): Boolean {
        val message = message.orEmpty().lowercase()
        return this is UnknownHostException ||
            this is java.io.InterruptedIOException ||
            this is SocketTimeoutException ||
            "unable to resolve host" in message ||
            "timed out" in message ||
            "connection reset" in message ||
            "connect failed" in message ||
            "software caused connection abort" in message ||
            "connection abort" in message ||
            "流式响应意外中断" in message ||
            "broken pipe" in message ||
            "unexpected end of stream" in message ||
            "socket closed" in message
    }

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

    private fun JSONObject.toTokenUsage(): TokenUsage {
        val promptTokens = optInt("prompt_tokens")
        val completionTokens = optInt("completion_tokens")
        val promptDetails = optJSONObject("prompt_tokens_details")
        val completionDetails = optJSONObject("completion_tokens_details")
        val cacheMetricsAvailable = has("prompt_cache_hit_tokens") ||
            has("prompt_cache_miss_tokens") ||
            promptDetails?.has("cached_tokens") == true
        val cacheHit = when {
            has("prompt_cache_hit_tokens") -> optInt("prompt_cache_hit_tokens")
            promptDetails?.has("cached_tokens") == true ->
                promptDetails.optInt("cached_tokens")
            else -> 0
        }.coerceIn(0, promptTokens)
        val cacheMiss = when {
            has("prompt_cache_miss_tokens") -> optInt("prompt_cache_miss_tokens")
            cacheHit > 0 -> (promptTokens - cacheHit).coerceAtLeast(0)
            else -> 0
        }
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = optInt("total_tokens", promptTokens + completionTokens),
            cacheHitTokens = cacheHit,
            cacheMissTokens = cacheMiss,
            reasoningTokens = completionDetails?.optInt("reasoning_tokens") ?: 0,
            requests = 1,
            cacheMetricsAvailable = cacheMetricsAvailable,
        )
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
                    message.content?.let { put("content", it) }
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
    }
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
