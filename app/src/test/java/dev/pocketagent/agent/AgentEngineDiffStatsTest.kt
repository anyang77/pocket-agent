package dev.pocketagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEngineDiffStatsTest {
    @Test
    fun countsInsertedAndRemovedLinesAroundUnchangedPrefixAndSuffix() {
        val stats = countLineDiff(
            before = "header\nold one\nold two\nfooter\n",
            after = "header\nnew one\nnew two\nnew three\nfooter\n",
        )

        assertEquals(3, stats.additions)
        assertEquals(2, stats.deletions)
    }

    @Test
    fun countsNewFileContentAndNoopEdits() {
        assertEquals(LineDiffStats(2, 0), countLineDiff("", "a\nb"))
        assertEquals(LineDiffStats(0, 0), countLineDiff("same\n", "same\n"))
    }

    @Test
    fun compactsLongToolOutputWithoutHidingBeginningsOrEnds() {
        val content = "BEGIN\n" + "x".repeat(20_000) + "\nEND"

        val compacted = compactToolResultForModel(content, maxChars = 1_000)

        assertTrue(compacted.length <= 1_000)
        assertTrue(compacted.startsWith("BEGIN"))
        assertTrue(compacted.endsWith("END"))
        assertTrue(compacted.contains("characters omitted"))
    }

    @Test
    fun keepsContextCompactionThresholdsProportionalAndSafe() {
        assertEquals(32_000, contextCompactionThreshold(32_000))
        assertEquals(96_000, contextCompactionThreshold(128_000))
        assertEquals(224_000, contextCompactionThreshold(256_000))
        assertEquals(968_000, contextCompactionThreshold(1_000_000))
    }

    @Test
    fun leavesSmallToolOutputUntouched() {
        val content = "exit_code=0\noutput:\nok\n"

        assertEquals(content, compactToolResultForModel(content, maxChars = 1_000))
    }


    @Test
    fun keepsPreviousSpeedWhileNewRequestWarmsUp() {
        assertEquals(12.5f, retainLastMeasuredSpeed(12.5f, null))
        assertEquals(15.0f, retainLastMeasuredSpeed(12.5f, 15.0f))
        assertNull(retainLastMeasuredSpeed(null, null))
    }
}
