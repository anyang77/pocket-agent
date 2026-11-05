package dev.pocketagent.data

import dev.pocketagent.agent.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.io.Writer

class ConversationJsonTest {
    private fun fullConversation() = StoredConversation(
        9, "中文会话 😀", 11, 22,
        listOf(StoredMessage(3, "ASSISTANT", "正文\n\t\"\\😀", 10,
            activities = listOf(ToolActivity("run_command", "测试", false, "结果", 123, 0, 5, 2, 1),
                ToolActivity("pending", "等待")),
            usage = TokenUsage(100, 30, 130, 20, 80, 4, 2, true, 100),
            lastRequestUsage = TokenUsage(3, 2, 5), lastRequestAt = 99,
            plan = listOf(AgentPlanStep("检查", AgentPlanStatus.COMPLETED)),
            error = "错误", retryCount = 2, goal = "目标", phase = "FAILED", reasoning = "推理",
            attachments = listOf(ChatAttachment("a", "图片", "/private/a.jpg", "image", "/source", "image/jpeg")))),
        listOf(AgentMessage("assistant", null, "reason", null, null,
            listOf(AgentToolCall("call1", "run_command", "{\"command\":\"echo 中文\"}")), listOf("/private/a.jpg")),
            AgentMessage("tool", "工具输出", name = "run_command", toolCallId = "call1"),
            AgentMessage("user", "")),
    )

    @Test fun allFieldsRoundTripIncludingNullAndEmptyContent() {
        val original = listOf(fullConversation())
        val output = StringWriter()
        ConversationJson.write(output, original)
        assertEquals(original, ConversationJson.read(StringReader(output.toString())))
    }

    @Test fun readsLegacyOptionalFieldsAndSkipsUnknownValues() {
        val text = """{"version":2,"future":{"array":[null,{},1]},"conversations":[
            {"id":1,"createdAt":10,"updatedAt":20,"messages":[
                {"id":3,"role":"USER","content":"旧历史","timestamp":5,"error":null,
                 "usage":{"prompt_tokens":100,"cache_metrics_available":true},
                 "plan":[{"step":"未知状态","status":"future"}]}],
             "agent_messages":[{"role":"assistant","content":null,"tool_calls":[]}]}
        ]}"""
        val c = ConversationJson.read(StringReader(text)).single()
        assertEquals("对话", c.title)
        assertEquals("旧历史", c.messages.single().content)
        assertEquals(100, c.messages.single().usage?.cacheMeasuredPromptTokens)
        assertEquals(AgentPlanStatus.PENDING, c.messages.single().plan.single().status)
        assertNull(c.agentMessages.single().content)
    }

    @Test fun missingOrCorruptHistoryIsNotSilentlyEmpty() {
        listOf("{}", "{\"conversations\":null}", "{\"conversations\":[", "{\"conversations\":[]} trailing")
            .forEach { text ->
                try { ConversationJson.read(StringReader(text)); fail("Accepted corrupt history") }
                catch (_: IOException) { }
            }
    }

    @Test fun structurallyIncompleteConversationIsRejectedInsteadOfDiscarded() {
        listOf("{\"conversations\":[{\"id\":1}]}",
            "{\"conversations\":[{\"id\":1,\"messages\":null}]}",
            "{\"conversations\":[{\"messages\":[]}]}")
            .forEach { text ->
                try { ConversationJson.read(StringReader(text)); fail("Accepted incomplete conversation") }
                catch (_: IOException) { }
            }
        // Explicitly empty conversations are valid and retain the old filtering behavior.
        assertTrue(ConversationJson.read(StringReader(
            "{\"conversations\":[{\"id\":1,\"messages\":[]}]}"
        )).isEmpty())
    }

    @Test fun retentionKeepsNewestConversationsInsteadOfFirstInserted() {
        val c = fullConversation()
        val output = StringWriter()
        ConversationJson.write(output, (1L..55L).map { id -> c.copy(id = id, updatedAt = id,
            messages = (1L..505L).map { StoredMessage(it, "USER", "消息", it) }) })
        val loaded = ConversationJson.read(StringReader(output.toString()))
        assertEquals(50, loaded.size)
        assertEquals(55L, loaded.first().id)
        assertEquals(6L, loaded.last().id)
        assertEquals(500, loaded.first().messages.size)
        assertEquals(6L, loaded.first().messages.first().id)
    }

    @Test fun streamsHistoryLargerThanAvailableContiguousHeap() {
        // 48 MiB wire file, a shared payload in the model. No full JSON String/byte[] needed.
        val file = File.createTempFile("history-large", ".json")
        try {
            val payload = "中\"\\\n😀".repeat(40_000)
            val c = fullConversation().copy(messages = (1L..80L).map {
                StoredMessage(it, "ASSISTANT", "消息", it,
                    activities = listOf(ToolActivity("tool", "输出", true, payload)))
            }, agentMessages = emptyList())
            file.bufferedWriter().use { ConversationJson.write(it, listOf(c)) }
            assertTrue(file.length() > 40_000_000)
            val loaded = file.bufferedReader().use(ConversationJson::read).single()
            assertEquals(80, loaded.messages.size)
            assertTrue(loaded.messages.all { it.activities.single().result == payload })
        } finally { file.delete() }
    }

    @Test fun propagatesWriterFailure() {
        val failing = object : Writer() {
            override fun write(c: CharArray, off: Int, len: Int) { throw IOException("simulated disk full") }
            override fun flush() {}
            override fun close() {}
        }
        try { ConversationJson.write(failing, listOf(fullConversation())); fail("Write error hidden") }
        catch (e: IOException) { assertEquals("simulated disk full", e.message) }
    }

    @Test fun realHistoryCompatibilityWhenPrivateFixtureProvided() {
        val path = System.getenv("CONVERSATION_HISTORY_FIXTURE_PATH") ?: return
        val source = File(path)
        val first = source.bufferedReader().use(ConversationJson::read)
        assertTrue(first.isNotEmpty())
        val copy = File.createTempFile("history-roundtrip", ".json")
        try {
            copy.bufferedWriter().use { ConversationJson.write(it, first) }
            assertEquals(first, copy.bufferedReader().use(ConversationJson::read))
            println("Private history round trip: ${source.length()} bytes, ${first.size} conversations (contents not logged)")
        } finally { copy.delete() }
    }
}
