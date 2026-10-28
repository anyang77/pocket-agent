package dev.pocketagent.extensions

import dev.pocketagent.agent.AgentEvent
import dev.pocketagent.data.AiSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubagentManagerTest {
    @Test fun runsIndependentChildrenCollectsResultsAndEnforcesConcurrency() = runTest {
        val gate = CompletableDeferred<Unit>()
        val manager = SubagentManager(this, { ExtensionSettings(true, 2) }) { _, prompt, settings, emit ->
            assertEquals("test-model", settings.model)
            gate.await()
            emit(AgentEvent.AssistantText("Finding: " + prompt.substringAfterLast("Delegated task:")))
        }
        val first = manager.start("first", "review A", AiSettings(model = "test-model"), "context")
        val second = manager.start("second", "review B", AiSettings(model = "test-model"), "context")
        assertThrows(IllegalArgumentException::class.java) { manager.start("third", "task", AiSettings(), "") }
        runCurrent()
        assertEquals(2, manager.tasks.value.count { it.running })
        gate.complete(Unit)
        val result = manager.waitFor(listOf(first, second), 5)
        assertTrue(result.contains("review A"))
        assertTrue(result.contains("review B"))
        assertTrue(manager.tasks.value.none { it.running })
        assertNull(manager.remainingResults())
    }
    @Test fun stoppingOneChildDoesNotCancelOtherAndParentCollectsUnseenResults() = runTest {
        val gate = CompletableDeferred<Unit>()
        val manager = SubagentManager(this, { ExtensionSettings(true, 3) }) { _, _, _, emit ->
            gate.await(); emit(AgentEvent.AssistantText("completed inspection"))
        }
        val one = manager.start("one", "a", AiSettings(), "")
        val two = manager.start("two", "b", AiSettings(), "")
        runCurrent()
        manager.stop(one)
        runCurrent()
        assertFalse(manager.tasks.value.first { it.id == one }.running)
        assertTrue(manager.tasks.value.first { it.id == two }.running)
        gate.complete(Unit)
        assertTrue(manager.remainingResults()!!.contains("completed inspection"))
        assertNull(manager.remainingResults())
        assertTrue(currentCoroutineContext().isActive)
    }
    @Test fun disabledSubagentsCannotStart() = runTest {
        val manager = SubagentManager(this, { ExtensionSettings(false) }) { _, _, _, _ -> error("must not execute") }
        assertThrows(IllegalArgumentException::class.java) { manager.start("one", "task", AiSettings(), "") }
    }
}
