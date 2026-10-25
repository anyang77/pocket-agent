package dev.pocketagent.agent

import org.json.JSONObject
import java.util.Locale

data class AgentMessage(
    val role: String,
    val content: String? = null,
    val reasoningContent: String? = null,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<AgentToolCall> = emptyList(),
)

data class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val requests: Int = 1,
    val cacheMetricsAvailable: Boolean = false,
) {
    operator fun plus(other: TokenUsage): TokenUsage = TokenUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        totalTokens = totalTokens + other.totalTokens,
        cacheHitTokens = cacheHitTokens + other.cacheHitTokens,
        cacheMissTokens = cacheMissTokens + other.cacheMissTokens,
        reasoningTokens = reasoningTokens + other.reasoningTokens,
        requests = requests + other.requests,
        cacheMetricsAvailable = cacheMetricsAvailable || other.cacheMetricsAvailable,
    )

    val cacheHitRate: Float?
        get() = if (promptTokens > 0) {
            cacheHitTokens.toFloat() / promptTokens.toFloat()
        } else {
            null
        }
}

object ModelContextBudget {
    fun limitFor(model: String, configuredWindow: Int = 0): Int {
        if (configuredWindow > 0) return configuredWindow
        val normalized = model.lowercase(Locale.ROOT)
        return when {
            "gemini" in normalized -> 1_000_000
            "gpt-4.1" in normalized -> 1_000_000
            "claude" in normalized -> 200_000
            "deepseek" in normalized -> 128_000
            "qwen" in normalized -> 128_000
            "gpt-4o" in normalized -> 128_000
            "gpt-4" in normalized -> 128_000
            else -> 128_000
        }
    }
}

data class ToolActivity(
    val name: String,
    val summary: String,
    val success: Boolean? = null,
    val result: String? = null,
    val startedAt: Long? = null,
    val durationMs: Long? = null,
)

enum class AgentPlanStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
}

data class AgentPlanStep(
    val step: String,
    val status: AgentPlanStatus = AgentPlanStatus.PENDING,
)

data class AssistantTurn(
    val content: String?,
    val reasoningContent: String? = null,
    val toolCalls: List<AgentToolCall>,
    val finishReason: String?,
    val usage: TokenUsage? = null,
    val contentWasStreamed: Boolean = false,
)

data class ModelInfo(
    val id: String,
    val reasoningEfforts: List<String> = emptyList(),
)

object ReasoningEffortSupport {
    private val knownOrder = listOf(
        "none",
        "minimal",
        "low",
        "medium",
        "high",
        "xhigh",
        "max",
    )

    fun normalize(values: Iterable<String>): List<String> =
        values.asSequence()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it in knownOrder }
            .distinct()
            .sortedBy { knownOrder.indexOf(it) }
            .toList()

    fun infer(modelId: String): List<String> {
        val id = modelId.lowercase(Locale.ROOT)
        return when {
            "gpt-5" in id || "o3" in id || "o4" in id ->
                listOf("minimal", "low", "medium", "high")
            "o1" in id -> listOf("low", "medium", "high")
            "deepseek" in id -> listOf("low", "medium", "high")
            "claude" in id -> listOf("low", "medium", "high")
            "gemini-2.5" in id || "gemini-3" in id -> listOf("low", "medium", "high")
            "qwen3" in id -> listOf("low", "medium", "high")
            else -> emptyList()
        }
    }
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JSONObject,
)

sealed interface AgentEvent {
    data class AssistantText(val text: String) : AgentEvent
    data class Usage(val usage: TokenUsage) : AgentEvent
    data class ContextCompactionStarted(
        val beforeTokens: Int,
        val contextLimit: Int,
        val messageCount: Int,
    ) : AgentEvent
    data class ContextCompacted(
        val beforeTokens: Int,
        val afterTokens: Int,
        val contextLimit: Int,
        val summaryChars: Int,
        val elapsedMs: Long,
        val fallback: Boolean,
    ) : AgentEvent
    data class ModelRequestStarted(
        val model: String,
        val round: Int,
        val messageCount: Int,
    ) : AgentEvent
    data class ModelRequestFinished(
        val round: Int,
        val elapsedMs: Long,
        val contentChars: Int,
        val toolCallCount: Int,
    ) : AgentEvent
    data class AssistantDelta(val text: String) : AgentEvent
    data class ModelReasoningProgress(val chars: Int) : AgentEvent
    data class PlanUpdated(val steps: List<AgentPlanStep>) : AgentEvent
    data class ToolStarted(val name: String, val summary: String) : AgentEvent
    data class ToolFinished(val name: String, val summary: String, val success: Boolean) : AgentEvent
    data class State(val text: String) : AgentEvent
    data object Completed : AgentEvent
    data class Stopped(val message: String) : AgentEvent
    data class Failed(val message: String) : AgentEvent
}

open class AgentException(message: String) : Exception(message)

class AgentHttpException(
    val statusCode: Int,
    body: String,
    val retryAfterSeconds: Long? = null,
) : AgentException("模型请求失败 HTTP $statusCode: ${body.take(1_000)}")
