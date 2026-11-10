package dev.pocketagent.agent

import org.junit.Assert.*
import org.junit.Test

class FileChangePreviewTest {
    @Test fun separatedEditsDoNotCountUnchangedMiddleAsModified() {
        val diff = fileChangePreview("a\nold\nkeep\nold2\nz\n", "a\nnew\nkeep\nnew2\nz\n")
        assertEquals(2, diff.additions); assertEquals(2, diff.deletions)
        assertTrue(diff.diff.contains("-old\n+new\n keep\n-old2\n+new2"))
        assertFalse(diff.diff.contains("-keep"))
    }
    @Test fun insertsDeletesNoopsAndTerminalNewline() {
        assertEquals(2, fileChangePreview("", "one\ntwo\n").additions)
        assertEquals(2, fileChangePreview("one\ntwo\n", "").deletions)
        assertEquals(FileChangePreview(0, 0, ""), fileChangePreview("same\n", "same\n"))
        val newline = fileChangePreview("same", "same\n")
        assertEquals(1, newline.additions); assertEquals(1, newline.deletions)
        assertTrue(newline.diff.contains("No newline"))
    }
    @Test fun truncationBoundsOutputButNotStats() {
        val diff = fileChangePreview("", (1..1000).joinToString("\n") { "line $it" })
        assertEquals(1000, diff.additions)
        assertTrue(diff.diff.contains("截断")); assertTrue(diff.diff.length < 62_100)
    }
    @Test fun largeReplacementsAreBoundedAndIncludeAllCounts() {
        val diff = fileChangePreview((1..3000).joinToString("\n"), (3001..6000).joinToString("\n"))
        assertEquals(3000, diff.additions); assertEquals(3000, diff.deletions)
        assertTrue(diff.diff.contains("截断"))
    }
    @Test fun longLineTruncationIsExplicitWithoutLosingStats() {
        val diff = fileChangePreview("", "x".repeat(3_000))
        assertEquals(1, diff.additions)
        assertEquals(0, diff.deletions)
        assertTrue(diff.diff.contains("截断"))
        assertFalse(diff.diff.contains("x".repeat(2_001)))
    }
    @Test fun largeSeparatedChangesMarkFallbackStatisticsAsApproximate() {
        val middle = (1..1000).joinToString("\n") { "keep $it" }
        val diff = fileChangePreview("old\n$middle\nold", "new\n$middle\nnew")
        assertTrue(diff.diff.startsWith("≈"))
        assertTrue(diff.diff.contains("按块估算"))
    }
    @Test fun preservesCodeTrailingSpaces() {
        assertTrue(fileChangePreview("", "hello  ").diff.endsWith("+hello  "))
    }
    @Test fun decodesPartialStringsWithoutLeakingIncompleteEscapes() {
        val fields = partialToolStrings("""{"path":"a.kt","content":"one\ntwo\u4f60\u""")
        assertEquals("a.kt", fields["path"])
        assertEquals("true", fields["path:complete"])
        assertEquals("one\ntwo你", fields["content"])
        assertNull(fields["content:complete"])
    }
    @Test fun handlesEscapedQuotesSlashesAndNestedLookingText() {
        val raw = """{"path":"a.kt","old_text":"say \"hi\"","new_text":"{\"x\":\"\\\"}","replace_all":true}"""
        val fields = partialToolStrings(raw)
        assertEquals("say \"hi\"", fields["old_text"])
        assertEquals("true", fields["new_text:complete"])
        assertEquals("true", fields["replace_all"])
    }
    @Test fun incompletePathIsNeverReadyToRead() {
        val fields = partialToolStrings("""{"path":"unfin""")
        assertNull(fields["path:complete"])
        assertNull(fields["content"])
    }
}
