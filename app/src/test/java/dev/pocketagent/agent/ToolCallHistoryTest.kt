package dev.pocketagent.agent

import org.junit.Assert.*
import org.junit.Test

class ToolCallHistoryTest {
    private val first = AgentToolCall("first", "write_file", "{}")
    private val second = AgentToolCall("second", "run_command", "{}")
    private fun turn() = AgentMessage("assistant", toolCalls = listOf(first, second))
    private fun result(id: String) = AgentMessage("tool", content = "ok", toolCallId = id)

    @Test fun cancelledBatchGetsResultsForAllOutstandingCalls() {
        val repaired = repairInterruptedToolCalls(listOf(turn()))
        assertEquals(listOf("first", "second"), repaired.drop(1).map { it.toolCallId })
        assertTrue(repaired.last().content!!.contains("may have partially executed"))
        assertEquals(repaired, repairInterruptedToolCalls(repaired))
    }

    @Test fun partialBatchPreservesCompletedResult() {
        val completed = result("first")
        val repaired = repairInterruptedToolCalls(listOf(turn(), completed))
        assertEquals(completed, repaired[1])
        assertEquals("second", repaired.last().toolCallId)
        assertEquals(3, repaired.size)
    }

    @Test fun loadedBrokenHistoryIsRepairedBeforeNextUserTurn() {
        val user = AgentMessage("user", "continue")
        val repaired = repairInterruptedToolCalls(listOf(turn(), result("first"), user))
        assertEquals("second", repaired[2].toolCallId)
        assertEquals(user, repaired.last())
    }

    @Test fun validHistoryIsUnchanged() {
        val history = listOf(AgentMessage("system", "system"), turn(), result("first"),
            result("second"), AgentMessage("assistant", "done"))
        assertEquals(history, repairInterruptedToolCalls(history))
    }

    @Test fun orphanAndDuplicateResultsAreRemoved() {
        val repaired = repairInterruptedToolCalls(listOf(result("orphan"), turn(),
            result("first"), result("first"), result("second")))
        assertEquals(listOf(turn(), result("first"), result("second")), repaired)
    }
}
