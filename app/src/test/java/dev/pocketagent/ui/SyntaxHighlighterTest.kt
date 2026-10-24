package dev.pocketagent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.text.AnnotatedString

class SyntaxHighlighterTest {
    @Test
    fun detectsLanguageFromExtension() {
        assertEquals(SyntaxLanguage.KOTLIN, syntaxLanguageFor("Main.kt"))
        assertEquals(SyntaxLanguage.PYTHON, syntaxLanguageFor("build.py"))
        assertEquals(SyntaxLanguage.CPP, syntaxLanguageFor("native.cpp"))
        assertEquals(SyntaxLanguage.SHELL, syntaxLanguageFor("install.sh"))
        assertEquals(SyntaxLanguage.MARKDOWN, syntaxLanguageFor("README.md"))
        assertEquals(SyntaxLanguage.PLAIN, syntaxLanguageFor("notes"))
    }

    @Test
    fun usesDistinctTermuxStyleColorsForCodeTokens() {
        val transformation = SyntaxHighlightTransformation(
            language = SyntaxLanguage.KOTLIN,
            keyword = TermuxEditorColors.keyword,
            string = TermuxEditorColors.string,
            comment = TermuxEditorColors.comment,
            number = TermuxEditorColors.number,
            function = TermuxEditorColors.function,
            type = TermuxEditorColors.type,
            operator = TermuxEditorColors.operator,
            variable = TermuxEditorColors.variable,
        )
        val result = transformation.filter(
            AnnotatedString(
                "class Main { fun run(): String = \"ok\" + 42 // note\n}",
            ),
        )
        val colors = result.text.spanStyles.map { it.item.color }.distinct()

        assertTrue(colors.size >= 6)
    }
}
