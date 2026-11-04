package dev.pocketagent.agent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One gate is shared by all conversations using the application's current workspace. */
class WorkspaceToolScheduler {
    private val mutations = Mutex()

    suspend fun <T> execute(name: String, external: Boolean = false, action: suspend () -> T): T {
        if (!external && name !in MUTATING_TOOLS) return action()
        return mutations.withLock {
            // A cancelled waiter must never execute after a different session releases the gate.
            currentCoroutineContext().ensureActive()
            action()
        }
    }

    private companion object {
        val MUTATING_TOOLS = setOf("write_file", "edit_file", "run_command", "mcp_call")
    }
}
