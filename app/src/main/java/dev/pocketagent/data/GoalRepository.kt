package dev.pocketagent.data

import android.content.Context
import java.util.UUID

enum class GoalPhase {
    ACTIVE,
    PAUSED,
    BLOCKED,
    COMPLETED,
}

data class AgentGoal(
    val objective: String,
    val phase: GoalPhase = GoalPhase.ACTIVE,
    val id: String = UUID.randomUUID().toString(),
) {
    internal fun isActiveRun(runGoalId: String?): Boolean =
        runGoalId != null && id == runGoalId && phase == GoalPhase.ACTIVE
}

class GoalRepository(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("pocket_agent_goal", Context.MODE_PRIVATE)

    fun load(): AgentGoal? {
        val objective = preferences.getString("objective", null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val phase = runCatching {
            GoalPhase.valueOf(
                preferences.getString("phase", GoalPhase.ACTIVE.name)
                    .orEmpty()
                    .uppercase(),
            )
        }.getOrDefault(GoalPhase.ACTIVE)
        val id = preferences.getString("id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("id", it).apply()
        }
        return AgentGoal(objective, phase, id)
    }

    fun save(goal: AgentGoal?) {
        if (goal == null) {
            preferences.edit().clear().apply()
        } else {
            preferences.edit()
                .putString("id", goal.id)
                .putString("objective", goal.objective)
                .putString("phase", goal.phase.name)
                .apply()
        }
    }
}
