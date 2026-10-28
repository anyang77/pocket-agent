package dev.pocketagent.ui

import org.junit.Assert.*
import org.junit.Test

class FileMentionTest {
    @Test fun findsMentionAtCaretWithoutTreatingEmailAsReference() {
        assertEquals(FileMention(3, 7, "src"), activeFileMention("查看 @src 然后继续", 7))
        assertEquals("", activeFileMention("@", 1)?.query)
        assertNull(activeFileMention("me@example.com", 14))
        assertNull(activeFileMention("@src ", 5))
        assertNull(activeFileMention("@src", 4, false))
        assertNull(activeFileMention("普通输入", 4))
    }
    @Test fun insertsPathAtCaretAndPreservesFollowingText() {
        val text = "查看 @src 后继续"
        val mention = activeFileMention(text, 7)!!
        val result = replaceFileMention(text, mention, "src/My File.kt")
        assertEquals("查看 @\"src/My File.kt\"  后继续", result.first)
        assertEquals(' ', result.first[result.second - 1])
        assertNull(activeFileMention(result.first, result.second))
    }
}
