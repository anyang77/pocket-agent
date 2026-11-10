package dev.pocketagent.data

import org.junit.Assert.*
import org.junit.Test

class AgentGoalTest {
    @Test fun onlyMatchingActiveGoalAcceptsRunEvents() {
        val goal = AgentGoal("完成验证")
        assertTrue(goal.isActiveRun(goal.id))
        assertFalse(goal.isActiveRun(null))
        assertFalse(goal.isActiveRun(AgentGoal(goal.objective).id))
        assertFalse(goal.copy(phase = GoalPhase.PAUSED).isActiveRun(goal.id))
        assertFalse(goal.copy(phase = GoalPhase.COMPLETED).isActiveRun(goal.id))
        assertFalse(goal.copy(phase = GoalPhase.BLOCKED).isActiveRun(goal.id))
    }

    @Test fun replacingOrResumingGoalRejectsLateEvents() {
        val old = AgentGoal("运行测试")
        val replacement = AgentGoal(old.objective)
        assertNotEquals(old.id, replacement.id)
        assertFalse(replacement.isActiveRun(old.id))
        assertEquals(old.id, old.copy(phase = GoalPhase.PAUSED).id)
    }
}
