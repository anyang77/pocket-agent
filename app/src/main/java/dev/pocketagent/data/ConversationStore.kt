package dev.pocketagent.data

import android.content.Context
import android.util.AtomicFile
import dev.pocketagent.agent.TokenUsage
import dev.pocketagent.agent.AgentMessage
import dev.pocketagent.agent.AgentToolCall
import dev.pocketagent.agent.AgentPlanStatus
import dev.pocketagent.agent.AgentPlanStep
import dev.pocketagent.agent.ToolActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StoredMessage(
    val id: Long,
    val role: String,
    val content: String,
    val timestamp: Long,
    val activities: List<ToolActivity> = emptyList(),
    val usage: TokenUsage? = null,
    val lastRequestUsage: TokenUsage? = null,
    val lastRequestAt: Long? = null,
    val plan: List<AgentPlanStep> = emptyList(),
    val error: String? = null,
    val retryCount: Int = 0,
    val goal: String? = null,
)

data class StoredConversation(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<StoredMessage>,
    val agentMessages: List<AgentMessage> = emptyList(),
)

data class ConversationSummary(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
    val preview: String,
)

class ConversationStore(context: Context) {
    private val atomicFile = AtomicFile(
        File(context.applicationContext.filesDir, "conversations.json"),
    )

    @Synchronized
    fun load(): List<StoredConversation> {
        if (!atomicFile.baseFile.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(atomicFile.readFully().toString(Charsets.UTF_8))
            val array = root.optJSONArray("conversations") ?: return emptyList()
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val messagesArray = item.optJSONArray("messages") ?: JSONArray()
                    val agentMessagesArray = item.optJSONArray("agent_messages") ?: JSONArray()
                    val agentMessages = buildList {
                        for (
                            messageIndex in 0 until agentMessagesArray.length()
                        ) {
                            val message = agentMessagesArray.optJSONObject(messageIndex)
                                ?: continue
                            val toolCallsArray = message.optJSONArray("tool_calls")
                                ?: JSONArray()
                            val toolCalls = buildList {
                                for (
                                    toolIndex in 0 until toolCallsArray.length()
                                ) {
                                    val call = toolCallsArray.optJSONObject(toolIndex)
                                        ?: continue
                                    add(
                                        AgentToolCall(
                                            id = call.optString("id"),
                                            name = call.optString("name"),
                                            arguments = call.optString("arguments", "{}"),
                                        ),
                                    )
                                }
                            }
                            add(
                                AgentMessage(
                                    role = message.optString("role"),
                                    content = message.optString("content")
                                        .takeIf { message.has("content") },
                                    reasoningContent = message.optString("reasoning_content")
                                        .takeIf { message.has("reasoning_content") },
                                    name = message.optString("name")
                                        .takeIf { message.has("name") },
                                    toolCallId = message.optString("tool_call_id")
                                        .takeIf { message.has("tool_call_id") },
                                    toolCalls = toolCalls,
                                ),
                            )
                        }
                    }
                    val messages = buildList {
                        for (messageIndex in 0 until messagesArray.length()) {
                            val message = messagesArray.optJSONObject(messageIndex) ?: continue
                            val activitiesArray = message.optJSONArray("activities") ?: JSONArray()
                            val activities = buildList {
                                for (
                                    activityIndex in 0 until activitiesArray.length()
                                ) {
                                    val activity = activitiesArray.optJSONObject(activityIndex)
                                        ?: continue
                                    add(
                                        ToolActivity(
                                            name = activity.optString("name"),
                                            summary = activity.optString("summary"),
                                            success = if (activity.has("success")) {
                                                activity.optBoolean("success")
                                            } else {
                                                null
                                            },
                                            result = activity.optString("result")
                                                .takeIf { it.isNotBlank() },
                                            startedAt = activity.optLong("started_at")
                                                .takeIf { it > 0 },
                                            durationMs = activity.optLong("duration_ms")
                                                .takeIf { it >= 0 && activity.has("duration_ms") },
                                        ),
                                    )
                                }
                            }
                            val usageJson = message.optJSONObject("usage")
                            val usage = usageJson?.toTokenUsage()
                            val lastRequestUsage = message.optJSONObject("last_request_usage")
                                ?.toTokenUsage()
                            val lastRequestAt = message.optLong("last_request_at")
                                .takeIf { it > 0 }
                            val planArray = message.optJSONArray("plan") ?: JSONArray()
                            val plan = buildList {
                                for (planIndex in 0 until planArray.length()) {
                                    val step = planArray.optJSONObject(planIndex) ?: continue
                                    val status = runCatching {
                                        AgentPlanStatus.valueOf(
                                            step.optString("status").uppercase(),
                                        )
                                    }.getOrDefault(AgentPlanStatus.PENDING)
                                    add(
                                        AgentPlanStep(
                                            step = step.optString("step"),
                                            status = status,
                                        ),
                                    )
                                }
                            }
                            add(
                                StoredMessage(
                                    id = message.optLong("id"),
                                    role = message.optString("role"),
                                    content = message.optString("content"),
                                    timestamp = message.optLong("timestamp"),
                                    activities = activities,
                                    usage = usage,
                                    lastRequestUsage = lastRequestUsage,
                                    lastRequestAt = lastRequestAt,
                                    plan = plan,
                                    error = message.optString("error")
                                        .takeIf { it.isNotBlank() },
                                    retryCount = message.optInt("retry_count", 0),
                                    goal = message.optString("goal")
                                        .takeIf { it.isNotBlank() },
                                ),
                            )
                        }
                    }
                    if (messages.isNotEmpty()) {
                        add(
                            StoredConversation(
                                id = item.getLong("id"),
                                title = item.optString("title", "对话"),
                                createdAt = item.optLong("createdAt"),
                                updatedAt = item.optLong("updatedAt"),
                                messages = messages,
                                agentMessages = agentMessages,
                            ),
                        )
                    }
                }
            }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(conversations: List<StoredConversation>) {
        val array = JSONArray()
        conversations.take(MAX_CONVERSATIONS).forEach { conversation ->
            val messages = JSONArray()
            conversation.messages.takeLast(MAX_MESSAGES_PER_CONVERSATION).forEach { message ->
                messages.put(
                    JSONObject().apply {
                        put("id", message.id)
                        put("role", message.role)
                        put("content", message.content)
                        put("timestamp", message.timestamp)
                        message.error?.let { put("error", it) }
                        message.goal?.let { put("goal", it) }
                        if (message.retryCount > 0) {
                            put("retry_count", message.retryCount)
                        }
                        if (message.activities.isNotEmpty()) {
                            put(
                                "activities",
                                JSONArray().apply {
                                    message.activities.forEach { activity ->
                                        put(
                                            JSONObject().apply {
                                                put("name", activity.name)
                                                put("summary", activity.summary)
                                                activity.success?.let {
                                                    put("success", it)
                                                }
                                                activity.result?.let { put("result", it) }
                                                activity.startedAt?.let { put("started_at", it) }
                                                activity.durationMs?.let { put("duration_ms", it) }
                                            },
                                        )
                                    }
                                },
                            )
                        }
                        message.usage?.let { usage ->
                            put("usage", usage.toJson())
                        }
                        message.lastRequestUsage?.let { usage ->
                            put("last_request_usage", usage.toJson())
                        }
                        message.lastRequestAt?.let { put("last_request_at", it) }
                        if (message.plan.isNotEmpty()) {
                            put(
                                "plan",
                                JSONArray().apply {
                                    message.plan.forEach { step ->
                                        put(
                                            JSONObject().apply {
                                                put("step", step.step)
                                                put("status", step.status.name.lowercase())
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    },
                )
            }
            array.put(
                JSONObject().apply {
                    put("id", conversation.id)
                    put("title", conversation.title)
                    put("createdAt", conversation.createdAt)
                    put("updatedAt", conversation.updatedAt)
                    put("messages", messages)
                    put(
                        "agent_messages",
                        JSONArray().apply {
                            conversation.agentMessages.forEach { message ->
                                put(
                                    JSONObject().apply {
                                        put("role", message.role)
                                        message.content?.let { put("content", it) }
                                        message.reasoningContent?.let {
                                            put("reasoning_content", it)
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
                                                                put("name", call.name)
                                                                put("arguments", call.arguments)
                                                            },
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
        val root = JSONObject().apply {
            put("version", 2)
            put("conversations", array)
        }
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    companion object {
        private const val MAX_CONVERSATIONS = 50
        private const val MAX_MESSAGES_PER_CONVERSATION = 500

        private fun TokenUsage.toJson(): JSONObject = JSONObject().apply {
            put("prompt_tokens", promptTokens)
            put("completion_tokens", completionTokens)
            put("total_tokens", totalTokens)
            put("cache_hit_tokens", cacheHitTokens)
            put("cache_miss_tokens", cacheMissTokens)
            put("reasoning_tokens", reasoningTokens)
            put("requests", requests)
            put("cache_metrics_available", cacheMetricsAvailable)
        }

        private fun JSONObject.toTokenUsage(): TokenUsage = TokenUsage(
            promptTokens = optInt("prompt_tokens"),
            completionTokens = optInt("completion_tokens"),
            totalTokens = optInt("total_tokens"),
            cacheHitTokens = optInt("cache_hit_tokens"),
            cacheMissTokens = optInt("cache_miss_tokens"),
            reasoningTokens = optInt("reasoning_tokens"),
            requests = optInt("requests", 1),
            cacheMetricsAvailable = optBoolean("cache_metrics_available"),
        )
    }
}
