package dev.pocketagent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitDiffDisplayTest {
    @Test fun keepsNormalDiffLinesAndEmptyLines() {
        assertEquals(listOf("@@ -1 +1 @@", "-old", "+new", ""), gitDiffDisplayLines("@@ -1 +1 @@\n-old\n+new\n"))
    }

    @Test fun boundsGeneratedLinesBeforeAndroidTextLayout() {
        val lines = gitDiffDisplayLines("+" + "x".repeat(500_000) + "\n next")
        assertTrue(lines.first().startsWith("+"))
        assertTrue(lines.first().length < 2_100)
        assertTrue(lines.first().contains("截断"))
        assertEquals(" next", lines.last())
    }
}
