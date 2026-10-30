package dev.pocketagent.agent

import dev.pocketagent.data.AiSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CacheDiagnosticsTest {
    @Test fun partialStreamingSnapshotsRetainCacheAndPromptFields() {
        val usage = StreamingTokenUsage()
        usage.update(JSONObject("""{"prompt_tokens":23126,"prompt_tokens_details":{"cached_tokens":21504}}"""))
        usage.update(JSONObject("""{"completion_tokens":259,"prompt_tokens_details":{"audio_tokens":0}}"""))
        usage.update(JSONObject("""{"prompt_tokens":null,"prompt_tokens_details":null}"""))
        assertEquals(23126, usage.value()!!.promptTokens)
        assertEquals(21504, usage.value()!!.cacheHitTokens)
        assertEquals(259, usage.value()!!.completionTokens)
        assertEquals(0.9299f, usage.value()!!.cacheHitRate!!, 0.001f)
    }

    @Test fun honorsExplicitZeroRatherThanKeepingOldHighHitCount() {
        val usage = StreamingTokenUsage()
        usage.update(JSONObject("""{"prompt_tokens":23126,"prompt_tokens_details":{"cached_tokens":21504}}"""))
        usage.update(JSONObject("""{"prompt_tokens_details":{"cached_tokens":0}}"""))
        assertEquals(0, usage.value()!!.cacheHitTokens)
        usage.update(JSONObject("""{"prompt_cache_hit_tokens":20000}"""))
        assertEquals(20000, usage.value()!!.cacheHitTokens)
        usage.update(JSONObject("""{"prompt_tokens_details":{"cached_tokens":0}}"""))
        assertEquals(0, usage.value()!!.cacheHitTokens)
    }

    @Test fun parsesStandardAndLegacyFieldsWithoutFabricatingHits() {
        val conflict = JSONObject("""{"prompt_tokens":23126,"prompt_cache_hit_tokens":4096,"prompt_tokens_details":{"cached_tokens":21504}}""")
        assertEquals(21504, conflict.toTokenUsage().cacheHitTokens)
        val low = JSONObject("""{"prompt_tokens":23126,"prompt_tokens_details":{"cached_tokens":4096}}""").toTokenUsage()
        assertEquals(0.1771f, low.cacheHitRate!!, 0.0001f)
        assertEquals(19030, low.cacheMissTokens)
        assertEquals(900, JSONObject("""{"prompt_tokens":1000,"prompt_cache_miss_tokens":100}""").toTokenUsage().cacheHitTokens)
        assertEquals(900, JSONObject("""{"prompt_tokens":1000,"prompt_cache_hit_tokens":900}""").toTokenUsage().cacheHitTokens)
        assertFalse(JSONObject("""{"prompt_tokens":1000,"prompt_tokens_details":{"cached_tokens":null}}""").toTokenUsage().cacheMetricsAvailable)
    }

    @Test fun fingerprintsDetectRewritesToolsAndEndpointChangesWithoutLoggingContent() {
        val tracker = PromptPrefixTracker()
        fun request(text: String, extra: String = "", tool: String = "read") = JSONObject("""{"model":"test","messages":[{"role":"system","content":"$text"}$extra],"tools":["$tool"]}""")
        tracker.record("https://example.test", request("private-data"))
        val appended = tracker.record("https://example.test", request("private-data", ",{\"role\":\"user\",\"content\":\"next\"}"))
        assertTrue(appended.description.contains("prefix_intact=true"))
        assertFalse(appended.description.contains("private-data"))
        assertTrue(tracker.record("https://example.test", request("changed")).description.contains("prefix_intact=false"))
        assertTrue(tracker.record("https://example.test", request("changed", tool = "write")).description.contains("config_same=false"))
        assertTrue(tracker.record("https://other.test", request("changed", tool = "write")).description.contains("config_same=false"))
    }

    @Test fun diagnosticWhitelistsNumericFieldsOnly() {
        val json = JSONObject("""{"prompt_tokens":23126,"prompt_cache_hit_tokens":4096,"secret":"do-not-log","prompt_tokens_details":{"cached_tokens":21504,"private":"do-not-log"}}""")
        assertEquals("prompt_tokens=23126 prompt_cache_hit_tokens=4096 prompt_tokens_details.cached_tokens=21504", json.cacheUsageDiagnostic())
    }

    @Test fun laterReasoningDoesNotRewriteEarlierWireMessagesAndStreamingUsageIsMerged() = runBlocking {
        val requests = mutableListOf<JSONObject>()
        val diagnostics = mutableListOf<String>()
        val events = """data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":23126,"prompt_tokens_details":{"cached_tokens":4096}}}
data: {"choices":[],"usage":{"completion_tokens":259}}
data: [DONE]

"""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            requests += JSONObject(buffer.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Content-Type", "text/event-stream")
                .body(events.toResponseBody()).build()
        }.build()
        val client = AiClient(http)
        val settings = AiSettings(baseUrl = "https://local.test/v1", model = "test", apiKey = "local", reasoningEffort = "auto")
        val history = listOf(AgentMessage("user", "first"), AgentMessage("assistant", "answer"), AgentMessage("user", "next"))
        val turn = client.complete(settings, history, emptyList(), onDiagnostic = { _, text -> diagnostics += text })
        assertEquals(4096, turn.usage!!.cacheHitTokens)
        assertEquals(23126, turn.usage!!.promptTokens)
        client.complete(settings, history + AgentMessage("assistant", "answer2", reasoningContent = "thinking") + AgentMessage("user", "continue"), emptyList(),
            onDiagnostic = { _, text -> diagnostics += text })
        val before = requests[0].getJSONArray("messages")
        val after = requests[1].getJSONArray("messages")
        for (i in 0 until before.length()) assertEquals(before.getJSONObject(i).toString(), after.getJSONObject(i).toString())
        assertEquals("thinking", after.getJSONObject(3).getString("reasoning_content"))
        assertTrue(diagnostics.any { it.contains("prefix_intact=true") })
        assertTrue(diagnostics.any { it.contains("cached_tokens=4096") })
    }
}
