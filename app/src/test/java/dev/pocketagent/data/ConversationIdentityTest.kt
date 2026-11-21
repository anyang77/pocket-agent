package dev.pocketagent.data

import dev.pocketagent.agent.AgentMessage
import dev.pocketagent.agent.AgentToolCall
import dev.pocketagent.agent.ToolActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter
import java.util.UUID

class ConversationIdentityTest {
    private val modelA = ConversationModelSelection("provider-a", "model-a", "high")
    private val modelB = ConversationModelSelection("provider-b", "model-b", "low")
    private fun conversation(id: Long) = StoredConversation(id, "Session $id", 10, 20,
        messages = listOf(StoredMessage(30, "USER", "previous task", 12)),
        modelSelection = modelA)

    @Test fun legacyIdsAreStableAndNewIdsAreUniqueUuids() {
        val a = legacyConversationDeepId(1, 10)
        assertEquals(a, legacyConversationDeepId(1, 10))
        assertNotEquals(a, legacyConversationDeepId(2, 10))
        assertNotEquals(a, legacyConversationDeepId(1, 11))
        assertEquals(a, UUID.fromString(a).toString())
        val ids = (1..50).map { newConversationDeepId() }
        assertEquals(50, ids.toSet().size)
        ids.forEach { assertEquals(it, UUID.fromString(it).toString()) }
    }

    @Test fun modelAndInheritanceSurvivePersistenceWithoutCredentials() {
        val original = conversation(1).copy(deepId = newConversationDeepId(), inheritedFromDeepId = newConversationDeepId())
        val output = StringWriter()
        ConversationJson.write(output, listOf(original))
        assertEquals(original, ConversationJson.read(StringReader(output.toString())).single())
        assertFalse(output.toString().contains("api_key"))
        assertFalse(output.toString().contains("base_url"))
    }

    @Test fun legacyHistoryGetsTheSameDeepIdOnEveryReadAndResave() {
        val oldJson = """{"conversations":[{"id":7,"createdAt":99,"messages":[{"id":1,"role":"USER","content":"legacy","timestamp":99}]}]}"""
        val first = ConversationJson.read(StringReader(oldJson)).single()
        assertEquals(legacyConversationDeepId(7, 99), first.deepId)
        assertNull(first.modelSelection)
        assertEquals(first.deepId, ConversationJson.read(StringReader(oldJson)).single().deepId)
        val output = StringWriter()
        ConversationJson.write(output, listOf(first))
        assertEquals(first, ConversationJson.read(StringReader(output.toString())).single())
    }

    @Test fun selectionUsesItsOwnProviderButNotItsLatestDefaultModel() {
        val profiles = listOf(
            ModelProfile("provider-b", "B", "https://b.example", "default-b", "secret-b", 64000, "auto"),
            ModelProfile("provider-a", "A", "https://a.example", "changed-default", "secret-a", 128000, "auto"),
        )
        val resolved = requireNotNull(modelA.resolve(profiles, "/work"))
        assertEquals("model-a", resolved.model)
        assertEquals("high", resolved.reasoningEffort)
        assertEquals("https://a.example", resolved.baseUrl)
        assertEquals("secret-a", resolved.apiKey)
        assertEquals("/work", resolved.workspacePath)
        assertNull(modelA.resolve(profiles.take(1), "/work"))
    }

    @Test fun forkCopiesFullContextAndRetainsTargetIdentityAndModel() {
        val history = listOf(
            AgentMessage("user", "previous task"),
            AgentMessage("assistant", null, toolCalls = listOf(AgentToolCall("tool-1", "run_command", "{}"))),
            AgentMessage("tool", "result", name = "run_command", toolCallId = "tool-1"),
            AgentMessage("assistant", "previous result"),
        )
        val source = conversation(1).copy(agentMessages = history)
        val target = conversation(2).copy(modelSelection = modelB)
        var nextId = 100L
        val fork = source.forkInto(target) { nextId++ }
        assertEquals(target.id, fork.id)
        assertEquals(target.deepId, fork.deepId)
        assertEquals(target.createdAt, fork.createdAt)
        assertEquals(modelB, fork.modelSelection)
        assertEquals(source.deepId, fork.inheritedFromDeepId)
        assertEquals(history, fork.agentMessages)
        assertEquals(source.messages.single().content, fork.messages.single().content)
        assertEquals(100L, fork.messages.single().id)
        assertEquals(30L, source.messages.single().id)
        assertNull(source.inheritedFromDeepId)
    }

    @Test fun forkDoesNotRestoreSpinnersForInterruptedTools() {
        val source = conversation(1).copy(messages = listOf(StoredMessage(4, "ASSISTANT", "", 1,
            activities = listOf(ToolActivity("run_command", "echo test"),
                ToolActivity("read_file", "a.kt", true, "done")))))
        val fork = source.forkInto(conversation(2)) { 101 }
        assertEquals(false, fork.messages.single().activities.first().success)
        assertEquals(true, fork.messages.single().activities.last().success)
        assertNull(source.messages.single().activities.first().success)
    }
}
