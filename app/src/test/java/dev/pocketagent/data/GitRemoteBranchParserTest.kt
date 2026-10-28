package dev.pocketagent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitRemoteBranchParserTest {
    @Test
    fun detectsDefaultAndPreservesSlashInBranchNames() {
        val result = GitRemoteBranchParser.parse(
            "ref: refs/heads/main\tHEAD\n" +
                "abc\tHEAD\nabc\trefs/heads/test/feature\n" +
                "def\trefs/heads/main\nabc\trefs/heads/test\n"
        )
        assertEquals("main", result.defaultBranch)
        assertEquals(listOf("main", "test", "test/feature"), result.names)
    }

    @Test
    fun emptyRepositoryHasNoBranches() {
        val result = GitRemoteBranchParser.parse("")
        assertEquals(emptyList<String>(), result.names)
        assertNull(result.defaultBranch)
    }

    @Test
    fun ignoresTagsAndDeduplicatesHeads() {
        val result = GitRemoteBranchParser.parse(
            "abc\trefs/tags/v1\nabc\trefs/heads/test\nabc\trefs/heads/test\n"
        )
        assertEquals(listOf("test"), result.names)
        assertNull(result.defaultBranch)
    }
}
