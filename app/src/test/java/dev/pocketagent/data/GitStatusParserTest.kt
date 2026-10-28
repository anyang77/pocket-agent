package dev.pocketagent.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GitStatusParserTest {
    @Test
    fun parsesStagedUnstagedAndUntrackedFiles() {
        val parsed = GitStatusParser.parse(
            """
            ## main...origin/main [ahead 2, behind 1]
             M src/Main.kt
            A  README.md
            ?? notes.txt
            D  old.txt
            """.trimIndent(),
        )

        assertEquals("main", parsed.branch)
        assertEquals(true, parsed.hasUpstream)
        assertEquals("origin/main", parsed.upstream)
        assertEquals(2, parsed.ahead)
        assertEquals(1, parsed.behind)
        assertEquals(listOf("README.md", "old.txt"), parsed.staged.map { it.path })
        assertEquals(listOf("src/Main.kt", "notes.txt"), parsed.changes.map { it.path })
    }

    @Test
    fun parsesNullSeparatedStatusWithoutEscapingUnicodePaths() {
        val output = buildString {
            append("## 功能/编辑器...origin/功能/编辑器 [behind 3]\u0000")
            append("A  src/中文.kt\u0000")
            append(" M 说明 文档.md\u0000")
            append("?? 新建 文件.txt\u0000")
        }

        val parsed = GitStatusParser.parse(output)

        assertEquals("功能/编辑器", parsed.branch)
        assertEquals(0, parsed.ahead)
        assertEquals(3, parsed.behind)
        assertEquals(listOf("src/中文.kt"), parsed.staged.map { it.path })
        assertEquals(
            listOf("说明 文档.md", "新建 文件.txt"),
            parsed.changes.map { it.path },
        )
        assertEquals(true, parsed.changes.last().untracked)
    }

    @Test
    fun parsesNameStatusFallbackForTrackedChanges() {
        val output = "M\u0000src/Main.kt\u0000A\u0000说明.md\u0000"

        val parsed = GitNameStatusParser.parse(output, staged = false)

        assertEquals(listOf("src/Main.kt", "说明.md"), parsed.map { it.path })
        assertEquals(listOf("M", "A"), parsed.map { it.status })
        assertEquals(false, parsed.any { it.staged })
    }

    @Test
    fun keepsRenameSourcePathForDiscard() {
        val output = "## main\u0000" + "R  new/name.kt\u0000old/name.kt\u0000"

        val parsed = GitStatusParser.parse(output)

        assertEquals(1, parsed.staged.size)
        val change = parsed.staged.single()
        assertEquals("new/name.kt", change.path)
        assertEquals("old/name.kt", change.originalPath)
    }

    @Test
    fun nameStatusParserKeepsRenameSourcePath() {
        val parsed = GitNameStatusParser.parse(
            "R100\u0000old/name.kt\u0000new/name.kt\u0000",
            staged = true,
        )

        val change = parsed.single()
        assertEquals("new/name.kt", change.path)
        assertEquals("old/name.kt", change.originalPath)
        assertEquals(true, change.staged)
    }

    @Test
    fun plainChangesHaveNoRenameSourcePath() {
        val parsed = GitStatusParser.parse("## main\u0000 M src/Main.kt\u0000")

        assertEquals(null, parsed.changes.single().originalPath)
    }
}
