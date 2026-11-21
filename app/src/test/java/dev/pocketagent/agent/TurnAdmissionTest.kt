package dev.pocketagent.agent

import dev.pocketagent.data.StoredConversation
import dev.pocketagent.data.StoredMessage
import dev.pocketagent.data.forkInto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TurnAdmissionTest {
    @Test fun cancellingDuringPrepareStillForksTheLatestUserAndImages() = runTest {
        val history = mutableListOf(AgentMessage("user", "old task"), AgentMessage("assistant", "old result"))
        var persisted = emptyList<AgentMessage>()
        val preparing = CompletableDeferred<Unit>()
        val latest = AgentMessage("user", "new task", imagePaths = listOf("/attachment/image.png"))
        val job = launch {
            admitUserTurn(history, latest, onAccepted = { persisted = history.toList() }, prepare = {
                preparing.complete(Unit)
                awaitCancellation()
            })
        }
        preparing.await()
        job.cancel()
        job.join()
        assertEquals(latest, persisted.last())
        assertEquals(history, persisted)
        val source = StoredConversation(1, "source", 1, 1,
            listOf(StoredMessage(4, "USER", "new task", 1)), persisted)
        val target = StoredConversation(2, "target", 2, 2, emptyList())
        val fork = source.forkInto(target) { 10 }
        assertEquals("new task", fork.messages.last().content)
        assertEquals(latest, fork.agentMessages.last())
    }

    @Test fun failedPrepareDoesNotLoseOrDuplicateAcceptedMessage() = runTest {
        val history = mutableListOf<AgentMessage>()
        val message = AgentMessage("user", "task")
        var acceptCount = 0
        try {
            admitUserTurn(history, message, onAccepted = { acceptCount++ }, prepare = { error("prepare failed") })
            fail("Preparation failure should be propagated")
        } catch (error: IllegalStateException) {
            assertEquals("prepare failed", error.message)
        }
        assertEquals(1, acceptCount)
        assertEquals(listOf(message), history)
    }
}
