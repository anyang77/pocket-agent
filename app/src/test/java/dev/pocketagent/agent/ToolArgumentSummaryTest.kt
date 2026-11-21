package dev.pocketagent.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolArgumentSummaryTest {
    @Test fun longMultilineCommandRemainsFullyInspectable() {
        val command = "printf '%s' '" + "x".repeat(1000) + "'\nprintf done"
        val raw = JSONObject().put("command", command).put("cwd", "project/src").put("environment", "root").toString()
        assertEquals("environment=root\ncwd=project/src\n$command", summarizeToolArguments(raw))
    }

    @Test fun commandDefaultsShowActualExecutionContext() {
        assertEquals("environment=termux\ncwd=.\npwd", summarizeToolArguments("""{"command":"pwd"}"""))
    }

    @Test fun otherToolArgumentsRemainCompactAndMalformedJsonDoesNotCrash() {
        assertEquals("a.kt", summarizeToolArguments("""{"path":"a.kt"}"""))
        assertEquals(180, summarizeToolArguments("x".repeat(300)).length)
    }
}
