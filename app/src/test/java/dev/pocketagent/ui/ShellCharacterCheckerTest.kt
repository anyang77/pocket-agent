package dev.pocketagent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCharacterCheckerTest {
    @Test
    fun reportsFullWidthPunctuationOutsideStrings() {
        val diagnostic = ShellCharacterChecker.check("echo ok；\necho done\n").single()

        assertEquals(1, diagnostic.line)
        assertEquals(8, diagnostic.column)
        assertTrue(diagnostic.message.contains("英文 ;"))
    }

    @Test
    fun ignoresChinesePunctuationInsideStringsAndComments() {
        val diagnostics = ShellCharacterChecker.check(
            """
            # 注释中的标点：正常
            echo "中文，标点；正常"
            echo '全角括号（正常）'
            """.trimIndent(),
        )

        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun ignoresOrdinaryUnicodeCommandWords() {
        val diagnostics = ShellCharacterChecker.check("echo 你好\necho 世界\n")

        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun ignoresHeredocBodyContent() {
        val diagnostics = ShellCharacterChecker.check(
            """
            cat <<'EOF'
            中文，标点；不是 Shell 代码
            EOF
            echo done
            """.trimIndent(),
        )

        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun reportsControlCharacters() {
        val diagnostic = ShellCharacterChecker.check("echo \u0001\n").single()

        assertTrue(diagnostic.message.contains("U+0001"))
    }

    @Test
    fun reportsReplacementCharacter() {
        val diagnostic = ShellCharacterChecker.check("echo \uFFFD\n").single()

        assertTrue(diagnostic.message.contains("U+FFFD"))
    }
}
