package dev.pocketagent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHistoryParserTest {
    @Test
    fun parsesUnifiedLocalAndRemoteGraph() {
        val separator = '\u001e'
        val field = '\u001f'
        val output = listOf(
            "* ${separator}abcdef123456${field}abcdef1${field}Alice${field}10-25 10:00" +
                "${field}HEAD -> main, origin/main, tag: v1${field}Latest commit",
            "|\\",
            "| * ${separator}123456abcdef${field}123456a${field}Bob${field}10-24 09:00" +
                "${field}origin/topic${field}Remote topic",
        ).joinToString("\n")

        val history = GitHistoryParser.parse(output, setOf("origin"))

        assertEquals(3, history.size)
        assertNull(history[1].commit)
        assertEquals("Latest commit", history[0].commit?.subject)
        assertEquals(
            listOf(GitRefType.HEAD, GitRefType.LOCAL, GitRefType.REMOTE, GitRefType.TAG),
            history[0].commit?.refs?.map { it.type },
        )
        assertEquals(GitRefType.REMOTE, history[2].commit?.refs?.single()?.type)
    }
}
