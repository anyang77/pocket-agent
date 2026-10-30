package dev.pocketagent.agent

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Keeps only hashes of the last wire prompt, never a second copy of conversation text. */
internal class PromptPrefixTracker {
    private val clientId = UUID.randomUUID().toString().take(8)
    private var sequence = 0
    private var previous: Snapshot? = null

    data class Diagnostic(val id: String, val description: String)
    private data class Snapshot(val id: String, val config: String, val messages: List<String>)

    @Synchronized
    fun record(endpoint: String, request: JSONObject): Diagnostic {
        val id = "$clientId-${++sequence}"
        val config = JSONObject().apply {
            request.keys().asSequence().filter { it != "messages" }.sorted()
                .forEach { key -> put(key, request.get(key)) }
        }
        val messages = request.getJSONArray("messages")
        val next = Snapshot(id, hash(endpoint + config.toString()),
            (0 until messages.length()).map { hash(messages.getJSONObject(it).toString()) })
        val old = previous
        val common = old?.messages?.zip(next.messages)?.takeWhile { (a, b) -> a == b }?.size ?: 0
        previous = next
        return Diagnostic(id, buildString {
            append("request=$id messages=${next.messages.size}")
            if (old != null) {
                append(" previous=${old.id} config_same=${old.config == next.config}")
                append(" unchanged_messages=$common/${old.messages.size}")
                append(" prefix_intact=${old.config == next.config && common == old.messages.size}")
            }
        })
    }

    private fun hash(value: String): String = buildString {
        for (byte in MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))) {
            val value = byte.toInt() and 255
            append("0123456789abcdef"[value ushr 4])
            append("0123456789abcdef"[value and 15])
        }
    }
}

/** SSE usage objects are cumulative snapshots; missing fields must not erase earlier fields. */
internal class StreamingTokenUsage {
    private var snapshot: JSONObject? = null

    fun update(next: JSONObject) {
        val target = snapshot ?: JSONObject().also { snapshot = it }
        val hasNested = next.optJSONObject("prompt_tokens_details")?.opt("cached_tokens") is Number
        val hasHit = next.opt("prompt_cache_hit_tokens") is Number
        val hasMiss = next.opt("prompt_cache_miss_tokens") is Number
        if (hasNested || hasHit || hasMiss) {
            // A gateway can switch field formats between frames. The latest explicit
            // cache measurement takes precedence, including an explicit zero.
            if (!hasNested) target.optJSONObject("prompt_tokens_details")?.remove("cached_tokens")
            if (!hasHit) target.remove("prompt_cache_hit_tokens")
            if (!hasMiss) target.remove("prompt_cache_miss_tokens")
        }
        merge(target, next)
    }

    fun value(): TokenUsage? = snapshot?.toTokenUsage()

    fun diagnostic(): String = snapshot?.cacheUsageDiagnostic().orEmpty()

    private fun merge(target: JSONObject, next: JSONObject) {
        next.keys().forEach { key ->
            val value = next.opt(key)
            if (value != null && value != JSONObject.NULL) {
                val existing = target.optJSONObject(key)
                if (existing != null && value is JSONObject) merge(existing, value)
                else target.put(key, value)
            }
        }
    }
}

internal fun JSONObject.toTokenUsage(): TokenUsage {
    val prompt = optInt("prompt_tokens").coerceAtLeast(0)
    val completion = optInt("completion_tokens").coerceAtLeast(0)
    val details = optJSONObject("prompt_tokens_details")
    val nestedHit = details?.opt("cached_tokens") as? Number
    val legacyHit = opt("prompt_cache_hit_tokens") as? Number
    val legacyMiss = opt("prompt_cache_miss_tokens") as? Number
    // Prefer the standard Chat Completions field when a gateway also sends legacy fields.
    val hit = (nestedHit?.toInt() ?: legacyHit?.toInt()
        ?: legacyMiss?.let { prompt - it.toInt() } ?: 0).coerceIn(0, prompt)
    val available = nestedHit != null || legacyHit != null || legacyMiss != null
    return TokenUsage(
        promptTokens = prompt,
        completionTokens = completion,
        totalTokens = optInt("total_tokens", prompt + completion),
        cacheHitTokens = hit,
        cacheMissTokens = if (available) prompt - hit else 0,
        reasoningTokens = optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens") ?: 0,
        cacheMetricsAvailable = available,
    )
}

/** Whitelist numbers only: provider extensions may contain private request data. */
internal fun JSONObject.cacheUsageDiagnostic(): String = buildList {
    for (key in listOf("prompt_tokens", "completion_tokens", "total_tokens",
        "prompt_cache_hit_tokens", "prompt_cache_miss_tokens")) {
        (opt(key) as? Number)?.let { add("$key=$it") }
    }
    (optJSONObject("prompt_tokens_details")?.opt("cached_tokens") as? Number)
        ?.let { add("prompt_tokens_details.cached_tokens=$it") }
}.joinToString(" ")
