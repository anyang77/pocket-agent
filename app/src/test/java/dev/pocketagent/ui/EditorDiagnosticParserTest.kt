package dev.pocketagent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorDiagnosticParserTest {
    @Test
    fun parsesShellSyntaxErrorLine() {
        val diagnostic = EditorDiagnosticParser.parse(
            "/tmp/source.sh: 2: Syntax error: \"then\" unexpected",
            "/tmp/source.sh",
        ).single()

        assertEquals(2, diagnostic.line)
        assertEquals(1, diagnostic.column)
        assertEquals("Syntax error: \"then\" unexpected", diagnostic.message)
    }

    @Test
    fun parsesPythonErrorLineAndColumn() {
        val diagnostic = EditorDiagnosticParser.parse(
            "File \"/tmp/source.py\", line 7, column 4\nSyntaxError: invalid syntax",
            "/tmp/source.py",
        ).single()

        assertEquals(7, diagnostic.line)
        assertEquals(4, diagnostic.column)
        assertEquals("SyntaxError: invalid syntax", diagnostic.message)
    }

    @Test
    fun parsesNodeErrorPosition() {
        val diagnostic = EditorDiagnosticParser.parse(
            "/tmp/source.js:3:9\nSyntaxError: Unexpected token '='",
            "/tmp/source.js",
        ).single()

        assertEquals(3, diagnostic.line)
        assertEquals(9, diagnostic.column)
    }

    @Test
    fun parsesAllShellCheckErrors() {
        val diagnostics = EditorDiagnosticParser.parse(
            """
            /tmp/source.sh:2:1: error: Couldn't parse this if expression. [SC1073]
            /tmp/source.sh:2:8: error: Unexpected keyword/token. [SC1072]
            """.trimIndent(),
            "/tmp/source.sh",
        )

        assertEquals(2, diagnostics.size)
        assertEquals(2, diagnostics[0].line)
        assertEquals(1, diagnostics[0].column)
        assertEquals(8, diagnostics[1].column)
    }

    @Test
    fun parsesShellMissingThenAtReportedLine() {
        val diagnostic = EditorDiagnosticParser.parse(
            "/tmp/source.sh: 4: Syntax error: \"fi\" unexpected (expecting \"then\")",
            "/tmp/source.sh",
        ).single()

        assertEquals(4, diagnostic.line)
        assertEquals(
            "Syntax error: \"fi\" unexpected (expecting \"then\")",
            diagnostic.message,
        )
    }
}
