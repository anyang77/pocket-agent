package dev.pocketagent.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import dev.pocketagent.agent.AgentMessage
import dev.pocketagent.agent.AgentPlanStatus
import dev.pocketagent.agent.AgentPlanStep
import dev.pocketagent.agent.AgentToolCall
import dev.pocketagent.agent.TokenUsage
import dev.pocketagent.agent.ToolActivity
import java.io.IOException
import java.io.Reader
import java.io.Writer

/** Version 2 wire format, streamed down to individual fields (including large tool results). */
internal object ConversationJson {
    fun write(output: Writer, conversations: List<StoredConversation>) {
        val w = JsonWriter(output)
        w.beginObject().name("version").value(2).name("conversations").beginArray()
        // The cache is insertion-ordered: taking its first 50 would silently drop new sessions.
        conversations.sortedByDescending { it.updatedAt }.take(50).forEach { c ->
            w.beginObject().name("id").value(c.id).name("title").value(c.title)
                .name("createdAt").value(c.createdAt).name("updatedAt").value(c.updatedAt)
                .name("messages").beginArray()
            c.messages.takeLast(500).forEach { m ->
                w.beginObject().name("id").value(m.id).name("role").value(m.role)
                    .name("content").value(m.content).name("timestamp").value(m.timestamp)
                w.optional("error", m.error); w.optional("goal", m.goal)
                w.name("phase").value(m.phase).name("reasoning").value(m.reasoning)
                    .name("retry_count").value(m.retryCount)
                w.name("attachments").beginArray()
                m.attachments.forEach { a ->
                    w.beginObject().name("id").value(a.id).name("name").value(a.name)
                        .name("path").value(a.path).name("kind").value(a.kind)
                        .name("mime_type").value(a.mimeType)
                    w.optional("source_path", a.sourcePath); w.endObject()
                }
                w.endArray().name("activities").beginArray()
                m.activities.forEach { a ->
                    w.beginObject().name("name").value(a.name).name("summary").value(a.summary)
                    a.success?.let { w.name("success").value(it) }
                    w.optional("result", a.result)
                    a.startedAt?.let { w.name("started_at").value(it) }
                    a.durationMs?.let { w.name("duration_ms").value(it) }
                    w.name("content_offset").value(a.contentOffset)
                        .name("additions").value(a.additions).name("deletions").value(a.deletions)
                    w.endObject()
                }
                w.endArray()
                m.usage?.let { w.name("usage"); writeUsage(w, it) }
                m.lastRequestUsage?.let { w.name("last_request_usage"); writeUsage(w, it) }
                m.lastRequestAt?.let { w.name("last_request_at").value(it) }
                w.name("plan").beginArray()
                m.plan.forEach { s ->
                    w.beginObject().name("step").value(s.step)
                        .name("status").value(s.status.name.lowercase()).endObject()
                }
                w.endArray().endObject()
            }
            w.endArray().name("agent_messages").beginArray()
            c.agentMessages.forEach { m ->
                w.beginObject().name("role").value(m.role)
                w.optional("content", m.content); w.optional("reasoning_content", m.reasoningContent)
                w.optional("name", m.name); w.optional("tool_call_id", m.toolCallId)
                w.name("image_paths").beginArray()
                m.imagePaths.forEach { w.value(it) }; w.endArray()
                w.name("tool_calls").beginArray()
                m.toolCalls.forEach { t ->
                    w.beginObject().name("id").value(t.id).name("name").value(t.name)
                        .name("arguments").value(t.arguments).endObject()
                }
                w.endArray().endObject()
            }
            w.endArray().endObject()
        }
        w.endArray().endObject().flush()
    }

    fun read(input: Reader): List<StoredConversation> {
        val r = JsonReader(input)
        var conversations: List<StoredConversation>? = null
        r.fields { name ->
            when (name) {
                "conversations" -> conversations = r.items { readConversation(r) }
                else -> r.skipValue()
            }
        }
        if (r.peek() != JsonToken.END_DOCUMENT) throw IOException("历史文件尾部包含无效数据")
        return (conversations ?: throw IOException("历史文件缺少 conversations 字段"))
            .filter { it.messages.isNotEmpty() }.sortedByDescending { it.updatedAt }
    }

    private fun readConversation(r: JsonReader): StoredConversation {
        var id: Long? = null; var title = "对话"; var created = 0L; var updated = 0L
        var messages: List<StoredMessage>? = null; var agent = emptyList<AgentMessage>()
        r.fields { when (it) {
            "id" -> id = r.nextLong(); "title" -> title = r.nextString()
            "createdAt" -> created = r.nextLong(); "updatedAt" -> updated = r.nextLong()
            "messages" -> messages = r.items { readMessage(r) }
            "agent_messages" -> agent = r.items { readAgentMessage(r) }
            else -> r.skipValue()
        } }
        return StoredConversation(id ?: throw IOException("历史会话缺少 id"), title, created, updated,
            messages ?: throw IOException("历史会话缺少有效的 messages 字段"), agent)
    }

    private fun readMessage(r: JsonReader): StoredMessage {
        var id = 0L; var role = ""; var content = ""; var timestamp = 0L
        var error: String? = null; var goal: String? = null; var phase = ""; var reasoning = ""; var retries = 0
        var attachments = emptyList<ChatAttachment>(); var activities = emptyList<ToolActivity>()
        var usage: TokenUsage? = null; var lastUsage: TokenUsage? = null; var lastAt: Long? = null
        var plan = emptyList<AgentPlanStep>()
        r.fields { when (it) {
            "id" -> id = r.nextLong(); "role" -> role = r.nextString()
            "content" -> content = r.nextString(); "timestamp" -> timestamp = r.nextLong()
            "error" -> error = r.nextString().takeIf(String::isNotBlank)
            "goal" -> goal = r.nextString().takeIf(String::isNotBlank)
            "phase" -> phase = r.nextString(); "reasoning" -> reasoning = r.nextString()
            "retry_count" -> retries = r.nextInt()
            "attachments" -> attachments = r.items { readAttachment(r) }
            "activities" -> activities = r.items { readActivity(r) }
            "usage" -> usage = readUsage(r); "last_request_usage" -> lastUsage = readUsage(r)
            "last_request_at" -> lastAt = r.nextLong().takeIf { n -> n > 0 }
            "plan" -> plan = r.items {
                var step = ""; var status = AgentPlanStatus.PENDING
                r.fields { key -> when (key) {
                    "step" -> step = r.nextString()
                    "status" -> status = runCatching { AgentPlanStatus.valueOf(r.nextString().uppercase()) }
                        .getOrDefault(AgentPlanStatus.PENDING)
                    else -> r.skipValue()
                } }
                AgentPlanStep(step, status)
            }
            else -> r.skipValue()
        } }
        return StoredMessage(id, role, content, timestamp, activities, usage, lastUsage, lastAt,
            plan, error, retries, goal, phase, reasoning, attachments)
    }

    private fun readAgentMessage(r: JsonReader): AgentMessage {
        var role = ""; var content: String? = null; var reasoning: String? = null
        var name: String? = null; var callId: String? = null
        var images = emptyList<String>(); var calls = emptyList<AgentToolCall>()
        r.fields { when (it) {
            "role" -> role = r.nextString(); "content" -> content = r.nextString()
            "reasoning_content" -> reasoning = r.nextString(); "name" -> name = r.nextString()
            "tool_call_id" -> callId = r.nextString(); "image_paths" -> images = r.items { r.nextString() }
            "tool_calls" -> calls = r.items {
                var id = ""; var toolName = ""; var args = "{}"
                r.fields { key -> when (key) {
                    "id" -> id = r.nextString(); "name" -> toolName = r.nextString()
                    "arguments" -> args = r.nextString(); else -> r.skipValue()
                } }
                AgentToolCall(id, toolName, args)
            }
            else -> r.skipValue()
        } }
        return AgentMessage(role, content, reasoning, name, callId, calls, images)
    }

    private fun readAttachment(r: JsonReader): ChatAttachment {
        var id = ""; var name = ""; var path = ""; var kind = ""; var source: String? = null; var mime = "text/plain"
        r.fields { when (it) {
            "id" -> id = r.nextString(); "name" -> name = r.nextString(); "path" -> path = r.nextString()
            "kind" -> kind = r.nextString(); "source_path" -> source = r.nextString().takeIf(String::isNotBlank)
            "mime_type" -> mime = r.nextString(); else -> r.skipValue()
        } }
        return ChatAttachment(id, name, path, kind, source, mime)
    }

    private fun readActivity(r: JsonReader): ToolActivity {
        var name = ""; var summary = ""; var success: Boolean? = null; var result: String? = null
        var start: Long? = null; var duration: Long? = null; var offset = 0; var additions = 0; var deletions = 0
        r.fields { when (it) {
            "name" -> name = r.nextString(); "summary" -> summary = r.nextString()
            "success" -> success = r.nextBoolean(); "result" -> result = r.nextString().takeIf(String::isNotBlank)
            "started_at" -> start = r.nextLong().takeIf { n -> n > 0 }
            "duration_ms" -> duration = r.nextLong().takeIf { n -> n >= 0 }
            "content_offset" -> offset = r.nextInt(); "additions" -> additions = r.nextInt()
            "deletions" -> deletions = r.nextInt(); else -> r.skipValue()
        } }
        return ToolActivity(name, summary, success, result, start, duration, offset, additions, deletions)
    }

    private fun writeUsage(w: JsonWriter, u: TokenUsage) {
        w.beginObject().name("prompt_tokens").value(u.promptTokens).name("completion_tokens").value(u.completionTokens)
            .name("total_tokens").value(u.totalTokens).name("cache_hit_tokens").value(u.cacheHitTokens)
            .name("cache_miss_tokens").value(u.cacheMissTokens).name("reasoning_tokens").value(u.reasoningTokens)
            .name("requests").value(u.requests).name("cache_metrics_available").value(u.cacheMetricsAvailable)
            .name("cache_measured_prompt_tokens").value(u.cacheMeasuredPromptTokens).endObject()
    }

    private fun readUsage(r: JsonReader): TokenUsage {
        var prompt = 0; var completion = 0; var total = 0; var hit = 0; var miss = 0; var reasoning = 0
        var requests = 1; var available = false; var measured: Int? = null
        r.fields { when (it) {
            "prompt_tokens" -> prompt = r.nextInt(); "completion_tokens" -> completion = r.nextInt()
            "total_tokens" -> total = r.nextInt(); "cache_hit_tokens" -> hit = r.nextInt()
            "cache_miss_tokens" -> miss = r.nextInt(); "reasoning_tokens" -> reasoning = r.nextInt()
            "requests" -> requests = r.nextInt(); "cache_metrics_available" -> available = r.nextBoolean()
            "cache_measured_prompt_tokens" -> measured = r.nextInt(); else -> r.skipValue()
        } }
        return TokenUsage(prompt, completion, total, hit, miss, reasoning, requests, available,
            measured ?: if (available) prompt else 0)
    }

    private fun JsonWriter.optional(name: String, value: String?) { if (value != null) name(name).value(value) }

    private inline fun JsonReader.fields(field: (String) -> Unit) {
        beginObject()
        while (hasNext()) {
            val name = nextName()
            if (peek() == JsonToken.NULL) nextNull() else field(name)
        }
        endObject()
    }

    private inline fun <T> JsonReader.items(item: () -> T): List<T> {
        val result = mutableListOf<T>()
        beginArray()
        while (hasNext()) {
            if (peek() == JsonToken.NULL) nextNull() else result.add(item())
        }
        endArray()
        return result
    }
}
