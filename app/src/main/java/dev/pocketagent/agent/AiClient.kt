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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class AiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
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
        onRetry: (String) -> Unit = {},
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
            if (tools.isNotEmpty()) put("tools", tools.toToolsJson())
            if (settings.reasoningEffort.isNotBlank() && settings.reasoningEffort != "auto") {
                put("reasoning_effort", settings.reasoningEffort)
            }
        }

        val request = Request.Builder()
            .url(chatCompletionsUrl(settings.baseUrl))
            .header("Authorization", "Bearer ${settings.apiKey.trim()}")
            .header("Accept", "application/json")
            .post(
                requestJson.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            .build()

        var lastError: Exception? = null
        for (attempt in 1..MAX_TRANSIENT_ATTEMPTS) {
            try {
                return@withContext executeCompletion(request)
            } catch (error: IOException) {
                lastError = error
                if (attempt == MAX_TRANSIENT_ATTEMPTS || !error.isTransientNetworkError()) {
                    throw AgentException(error.message ?: "模型网络请求失败")
                }
                val waitMs = 750L * attempt
                onRetry("连接中断，${waitMs / 1_000} 秒后重试 $attempt/${MAX_TRANSIENT_ATTEMPTS - 1}")
                delay(waitMs)
            } catch (error: AgentHttpException) {
                lastError = error
                if (attempt == MAX_TRANSIENT_ATTEMPTS || !error.isRetryable()) {
                    throw error
                }
                val waitMs = error.retryAfterSeconds
                    ?.times(1_000)
                    ?.coerceIn(1_000, 30_000)
                    ?: (1_000L shl (attempt - 1)).coerceAtMost(8_000)
                onRetry(
                    "服务暂时不可用 HTTP ${error.statusCode}，" +
                        "${waitMs / 1_000} 秒后重试 $attempt/${MAX_TRANSIENT_ATTEMPTS - 1}",
                )
                delay(waitMs)
            }
        }
        throw AgentException(lastError?.message ?: "模型网络请求失败")
    }

    private fun executeCompletion(request: Request): AssistantTurn {
        val call = httpClient.newCall(request)
        activeAgentCall = call
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw AgentHttpException(
                        statusCode = response.code,
                        body = body,
                        retryAfterSeconds = response.header("Retry-After")
                            ?.trim()
                            ?.toLongOrNull(),
                    )
                }

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

    private fun IOException.isTransientNetworkError(): Boolean {
        val message = message.orEmpty().lowercase()
        return this is UnknownHostException ||
            this is SocketTimeoutException ||
            "unable to resolve host" in message ||
            "timed out" in message ||
            "connection reset" in message ||
            "connect failed" in message ||
            "software caused connection abort" in message ||
            "connection abort" in message ||
            "broken pipe" in message ||
            "unexpected end of stream" in message ||
            "socket closed" in message
    }

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
        private const val MAX_TRANSIENT_ATTEMPTS = 4
    }
}

internal fun AgentHttpException.isRetryable(): Boolean =
    statusCode == 408 ||
        statusCode == 409 ||
        statusCode == 425 ||
        statusCode == 429 ||
        statusCode >= 500
