package dev.pocketagent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeCompletionTest {
    @Test
    fun filtersSuggestionsAndReplacesTheCurrentWord() {
        val text = "class Main {\n    fun calculate() = 1\n    cal\n}"
        val cursor = text.indexOf("cal\n") + 3
        val suggestions = CodeCompletionEngine.suggestions(
            SyntaxLanguage.KOTLIN,
            text,
            cursor,
        )
        val item = suggestions.first { it.label == "calculate" }

        val edit = CodeCompletionEngine.apply(text, cursor, item)

        assertEquals("cal", CodeCompletionEngine.currentWord(text, cursor))
        assertTrue(item.detail == "当前文件")
        assertTrue(edit.text.contains("    calculate\n}"))
        assertEquals(edit.text.lastIndexOf("calculate") + "calculate".length, edit.cursor)
    }

    @Test
    fun insertsSnippetAndPlacesCursorInsideIt() {
        val item = CodeCompletionEngine
            .suggestions(SyntaxLanguage.KOTLIN, "", 0)
            .first { it.label == "fun()" }
        val edit = CodeCompletionEngine.apply("", 0, item)

        assertEquals("fun () {\n    \n}", edit.text)
        assertEquals("fun (".length, edit.cursor)
    }

    @Test
    fun completesRustFunctionWithCursorAtItsName() {
        val item = CodeCompletionEngine.suggestions(SyntaxLanguage.RUST, "fn", 2)
            .first { it.detail == "函数" }
        val edit = CodeCompletionEngine.apply("fn", 2, item)
        assertEquals("fn () {\n    \n}", edit.text)
        assertEquals(3, edit.cursor)
        assertTrue(CodeCompletionEngine.suggestions(SyntaxLanguage.RUST, "Res", 3)
            .any { it.label == "Result" })
    }

    @Test
    fun completesBatchCommandsRegardlessOfCase() {
        val item = CodeCompletionEngine.suggestions(SyntaxLanguage.BATCH, "SET", 3)
            .first { it.detail == "设置变量" }
        val edit = CodeCompletionEngine.apply("SET", 3, item)
        assertEquals("set \"=value\"", edit.text)
        assertEquals(5, edit.cursor)
    }

    @Test
    fun reportsOneBasedLineAndColumn() {
        assertEquals(
            EditorCursorPosition(line = 2, column = 4),
            CodeCompletionEngine.position("abc\ndef", 7),
        )
    }
}
