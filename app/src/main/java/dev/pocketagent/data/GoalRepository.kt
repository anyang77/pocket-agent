package dev.pocketagent.data

import android.content.Context

enum class GoalPhase {
    ACTIVE,
    PAUSED,
    BLOCKED,
}

data class AgentGoal(
    val objective: String,
    val phase: GoalPhase = GoalPhase.ACTIVE,
)

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
        return AgentGoal(objective, phase)
    }

    fun save(goal: AgentGoal?) {
        if (goal == null) {
            preferences.edit().clear().apply()
        } else {
            preferences.edit()
                .putString("objective", goal.objective)
                .putString("phase", goal.phase.name)
                .apply()
        }
    }
}
