package dev.pocketagent.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle

object TermuxEditorColors {
    val keyword = Color(0xFF79C0FF)
    val string = Color(0xFF7EE787)
    val comment = Color(0xFF8B949E)
    val number = Color(0xFFE3D58A)
    val function = Color(0xFFD2A8FF)
    val type = Color(0xFF56D4DD)
    val operator = Color(0xFFFF7B72)
    val variable = Color(0xFFFFA657)
}

enum class SyntaxLanguage(val label: String) {
    KOTLIN("Kotlin"),
    JAVA("Java"),
    CPP("C/C++"),
    PYTHON("Python"),
    JAVASCRIPT("JavaScript"),
    TYPESCRIPT("TypeScript"),
    SHELL("Shell"),
    JSON("JSON"),
    MARKDOWN("Markdown"),
    PLAIN("Text"),
}

fun syntaxLanguageFor(path: String): SyntaxLanguage = when (
    path.substringAfterLast('.', "").lowercase()
) {
    "kt", "kts" -> SyntaxLanguage.KOTLIN
    "java" -> SyntaxLanguage.JAVA
    "c", "h", "cc", "cpp", "cxx", "hpp" -> SyntaxLanguage.CPP
    "py" -> SyntaxLanguage.PYTHON
    "js", "mjs", "cjs", "jsx" -> SyntaxLanguage.JAVASCRIPT
    "ts", "tsx" -> SyntaxLanguage.TYPESCRIPT
    "sh", "bash", "zsh" -> SyntaxLanguage.SHELL
    "json", "jsonc" -> SyntaxLanguage.JSON
    "md", "markdown" -> SyntaxLanguage.MARKDOWN
    else -> SyntaxLanguage.PLAIN
}

class SyntaxHighlightTransformation(
    private val language: SyntaxLanguage,
    private val keyword: Color,
    private val string: Color,
    private val comment: Color,
    private val number: Color,
    private val function: Color,
    private val type: Color,
    private val operator: Color,
    private val variable: Color,
) : VisualTransformation {
    private var cachedSource: String? = null
    private var cachedHighlighted: AnnotatedString? = null

    override fun filter(text: AnnotatedString): TransformedText = TransformedText(
        highlight(text.text),
        OffsetMapping.Identity,
    )

    private fun highlight(source: String): AnnotatedString {
        cachedHighlighted?.takeIf { cachedSource == source }?.let { return it }
        return buildAnnotatedString {
        var index = 0
        while (index < source.length) {
            if (index >= MAX_HIGHLIGHT_CHARS) {
                append(source.substring(index))
                break
            }
            val ch = source[index]
            when {
                language == SyntaxLanguage.MARKDOWN && ch == '#' &&
                    (index == 0 || source[index - 1] == '\n') -> {
                    val end = source.indexOf('\n', index).let {
                        if (it < 0) source.length else it
                    }
                    withStyle(SpanStyle(color = keyword, fontWeight = FontWeight.Bold)) {
                        append(source.substring(index, end))
                    }
                    index = end
                }
                ch == '/' && source.getOrNull(index + 1) == '/' -> {
                    val end = source.indexOf('\n', index).let {
                        if (it < 0) source.length else it
                    }
                    withStyle(SpanStyle(color = comment)) {
                        append(source.substring(index, end))
                    }
                    index = end
                }
                ch == '/' && source.getOrNull(index + 1) == '*' -> {
                    val close = source.indexOf("*/", index + 2)
                    val end = if (close < 0) source.length else close + 2
                    withStyle(SpanStyle(color = comment)) {
                        append(source.substring(index, end))
                    }
                    index = end
                }
                ch == '#' && language in setOf(SyntaxLanguage.PYTHON, SyntaxLanguage.SHELL) -> {
                    val end = source.indexOf('\n', index).let {
                        if (it < 0) source.length else it
                    }
                    withStyle(SpanStyle(color = comment)) {
                        append(source.substring(index, end))
                    }
                    index = end
                }
                ch == '#' && language == SyntaxLanguage.CPP &&
                    (index == 0 || source[index - 1] == '\n') -> {
                    val end = source.indexOf('\n', index).let {
                        if (it < 0) source.length else it
                    }
                    withStyle(SpanStyle(color = keyword, fontWeight = FontWeight.Bold)) {
                        append(source.substring(index, end))
                    }
                    index = end
                }
                ch == '$' && language == SyntaxLanguage.SHELL -> {
                    val end = if (source.getOrNull(index + 1) == '{') {
                        source.indexOf('}', index + 2).let {
                            if (it < 0) source.length else it + 1
                        }
                    } else {
                        advanceWhile(source, index + 1) {
                            it.isLetterOrDigit() || it == '_'
                        }
                    }
                    withStyle(SpanStyle(color = variable)) {
                        append(source.substring(index, end))
                    }
                    index = end
                }
                ch == '@' && language in setOf(
                    SyntaxLanguage.KOTLIN,
                    SyntaxLanguage.JAVA,
                ) -> {
                    val end = advanceWhile(source, index + 1) {
                        it.isLetterOrDigit() || it == '_' || it == '.'
                    }
                    withStyle(SpanStyle(color = function)) {
                        append(source.substring(index, end))
                    }
                    index = end
                }
                ch == '"' || ch == '\'' || (ch == '`' && language != SyntaxLanguage.MARKDOWN) -> {
                    val end = findStringEnd(source, index, ch)
                    withStyle(SpanStyle(color = string)) {
                        append(source.substring(index, end))
                    }
                    index = end
                }
                ch.isDigit() -> {
                    val end = advanceWhile(source, index) {
                        it.isLetterOrDigit() || it == '.' || it == '_'
                    }
                    withStyle(SpanStyle(color = number)) {
                        append(source.substring(index, end))
                    }
                    index = end
                }
                ch.isLetter() || ch == '_' -> {
                    val end = advanceWhile(source, index) { it.isLetterOrDigit() || it == '_' }
                    val word = source.substring(index, end)
                    var nextIndex = end
                    while (nextIndex < source.length && source[nextIndex].isWhitespace()) {
                        nextIndex++
                    }
                    val next = source.getOrNull(nextIndex)
                    val style = when {
                        word in keywords ->
                            SpanStyle(color = keyword, fontWeight = FontWeight.SemiBold)
                        next == '(' ->
                            SpanStyle(color = function)
                        word.firstOrNull()?.isUpperCase() == true ||
                            word in typeNames ->
                            SpanStyle(color = type)
                        word.all { it.isUpperCase() || it.isDigit() || it == '_' } ->
                            SpanStyle(color = number, fontWeight = FontWeight.Medium)
                        else -> null
                    }
                    if (style == null) append(word) else withStyle(style) { append(word) }
                    index = end
                }
                ch in "{}[]()+-*/%=<>&|!?:.," -> {
                    withStyle(SpanStyle(color = operator)) { append(ch) }
                    index++
                }
                else -> {
                    append(ch)
                    index++
                }
            }
        }
        }.also {
            cachedSource = source
            cachedHighlighted = it
        }
    }

    private fun findStringEnd(source: String, start: Int, quote: Char): Int {
        var index = start + 1
        while (index < source.length) {
            if (source[index] == '\\') {
                index += 2
                continue
            }
            if (source[index] == quote) return index + 1
            if (source[index] == '\n' && quote != '`') return index
            index++
        }
        return source.length
    }

    private fun advanceWhile(source: String, start: Int, predicate: (Char) -> Boolean): Int {
        var index = start
        while (index < source.length && predicate(source[index])) index++
        return index
    }

    private val keywords: Set<String> = when (language) {
        SyntaxLanguage.KOTLIN -> setOf(
            "package", "import", "class", "object", "interface", "fun", "val", "var",
            "if", "else", "when", "for", "while", "return", "try", "catch", "finally",
            "private", "public", "internal", "protected", "suspend", "data", "sealed",
        )
        SyntaxLanguage.JAVA -> setOf(
            "package", "import", "class", "interface", "enum", "public", "private",
            "protected", "static", "final", "void", "new", "return", "if", "else",
            "for", "while", "try", "catch", "extends", "implements",
        )
        SyntaxLanguage.CPP -> setOf(
            "include", "int", "char", "void", "auto", "const", "static", "struct",
            "class", "namespace", "using", "return", "if", "else", "for", "while",
            "switch", "case", "break", "continue", "template", "typename",
        )
        SyntaxLanguage.PYTHON -> setOf(
            "def", "class", "import", "from", "as", "if", "elif", "else", "for",
            "while", "return", "try", "except", "finally", "with", "lambda", "yield",
            "async", "await", "pass", "break", "continue", "True", "False", "None",
        )
        SyntaxLanguage.JAVASCRIPT, SyntaxLanguage.TYPESCRIPT -> setOf(
            "const", "let", "var", "function", "class", "interface", "type", "import",
            "export", "from", "if", "else", "for", "while", "return", "async", "await",
            "try", "catch", "finally", "throw", "new", "extends", "implements",
        )
        SyntaxLanguage.SHELL -> setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done",
            "case", "esac", "function", "export", "local", "return",
        )
        SyntaxLanguage.JSON -> setOf("true", "false", "null")
        else -> emptySet()
    }

    private val typeNames: Set<String> = when (language) {
        SyntaxLanguage.KOTLIN -> setOf(
            "String", "Int", "Long", "Boolean", "Float", "Double", "List", "Map",
            "Set", "Any", "Unit", "Flow", "Result",
        )
        SyntaxLanguage.JAVA, SyntaxLanguage.CPP -> setOf(
            "String", "Object", "Integer", "Long", "Boolean", "List", "Map", "Set",
            "void", "int", "long", "float", "double", "char", "bool", "size_t",
        )
        SyntaxLanguage.PYTHON ->
            setOf("str", "int", "float", "bool", "list", "dict", "set")
        SyntaxLanguage.JAVASCRIPT, SyntaxLanguage.TYPESCRIPT -> setOf(
            "String", "Number", "Boolean", "Array", "Object", "Promise", "Map", "Set",
        )
        else -> emptySet()
    }

    companion object {
        private const val MAX_HIGHLIGHT_CHARS = 160_000
    }
}
