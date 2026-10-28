package dev.pocketagent.ui

data class CodeCompletionItem(
    val label: String,
    val insertText: String,
    val cursorOffset: Int = insertText.length,
    val detail: String,
)

data class CodeCompletionEdit(
    val text: String,
    val cursor: Int,
)

data class EditorCursorPosition(
    val line: Int,
    val column: Int,
)

object CodeCompletionEngine {
    fun suggestions(
        language: SyntaxLanguage,
        text: String,
        cursor: Int,
        limit: Int = 18,
    ): List<CodeCompletionItem> {
        if (language == SyntaxLanguage.PLAIN || limit <= 0) return emptyList()
        val safeCursor = cursor.coerceIn(0, text.length)
        val prefix = currentWord(text, safeCursor)
        val candidates = buildList {
            addAll(snippets[language].orEmpty())
            addAll(keywords[language].orEmpty().map {
                CodeCompletionItem(it, it, detail = "关键字")
            })
            addAll(typeNames[language].orEmpty().map {
                CodeCompletionItem(it, it, detail = "类型")
            })
            if (prefix.length >= 2 && text.length <= MAX_SYMBOL_SCAN_CHARS) {
                addAll(documentSymbols(text).map {
                    CodeCompletionItem(it, it, detail = "当前文件")
                })
            }
        }
        return candidates
            .asSequence()
            .filter { prefix.isEmpty() || it.label.startsWith(prefix, ignoreCase = true) }
            .distinctBy { it.label.lowercase() }
            .take(limit)
            .toList()
    }

    fun currentWord(text: String, cursor: Int): String {
        val safeCursor = cursor.coerceIn(0, text.length)
        return text.substring(wordStart(text, safeCursor), safeCursor)
    }

    fun apply(
        text: String,
        cursor: Int,
        item: CodeCompletionItem,
    ): CodeCompletionEdit {
        val safeCursor = cursor.coerceIn(0, text.length)
        val start = wordStart(text, safeCursor)
        val offset = item.cursorOffset.coerceIn(0, item.insertText.length)
        return CodeCompletionEdit(
            text = text.replaceRange(start, safeCursor, item.insertText),
            cursor = start + offset,
        )
    }

    fun position(text: String, cursor: Int): EditorCursorPosition {
        val safeCursor = cursor.coerceIn(0, text.length)
        val before = text.substring(0, safeCursor)
        val line = before.count { it == '\n' } + 1
        val lastBreak = before.lastIndexOf('\n')
        return EditorCursorPosition(
            line = line,
            column = safeCursor - lastBreak,
        )
    }

    private fun wordStart(text: String, cursor: Int): Int {
        var start = cursor.coerceIn(0, text.length)
        while (start > 0 && isIdentifierPart(text[start - 1])) start--
        return start
    }

    private fun documentSymbols(text: String): List<String> {
        val found = linkedSetOf<String>()
        var index = 0
        while (index < text.length && found.size < MAX_DOCUMENT_SYMBOLS) {
            if (!isIdentifierStart(text[index])) {
                index++
                continue
            }
            val start = index++
            while (index < text.length && isIdentifierPart(text[index])) index++
            val value = text.substring(start, index)
            if (value.length >= 2 && value !in allKeywords) found += value
        }
        return found.toList()
    }

    private fun isIdentifierStart(char: Char): Boolean =
        char == '_' || char.isLetter()

    private fun isIdentifierPart(char: Char): Boolean =
        char == '_' || char.isLetterOrDigit()

    private fun snippet(
        label: String,
        insertText: String,
        detail: String,
    ): CodeCompletionItem {
        val cursor = insertText.indexOf(CURSOR_MARKER)
        val clean = insertText.replace(CURSOR_MARKER, "")
        return CodeCompletionItem(
            label = label,
            insertText = clean,
            cursorOffset = if (cursor >= 0) cursor else clean.length,
            detail = detail,
        )
    }

    private val snippets = mapOf(
        SyntaxLanguage.KOTLIN to listOf(
            snippet("fun()", "fun (%CURSOR%) {\n    \n}", "函数"),
            snippet("if()", "if (%CURSOR%) {\n    \n}", "条件"),
            snippet("when()", "when (%CURSOR%) {\n    else -> \n}", "分支"),
            snippet("for()", "for (item in %CURSOR%) {\n    \n}", "循环"),
            snippet(
                "try()",
                "try {\n    %CURSOR%\n} catch (e: Exception) {\n    \n}",
                "异常处理",
            ),
            snippet("println()", "println(\"%CURSOR%\")", "输出"),
            snippet("TODO()", "TODO(\"%CURSOR%\")", "待办"),
        ),
        SyntaxLanguage.JAVA to listOf(
            snippet(
                "main",
                "public static void main(String[] args) {\n    %CURSOR%\n}",
                "入口函数",
            ),
            snippet("if", "if (%CURSOR%) {\n    \n}", "条件"),
            snippet("for", "for (int i = 0; i < %CURSOR%; i++) {\n    \n}", "循环"),
            snippet(
                "try",
                "try {\n    %CURSOR%\n} catch (Exception e) {\n    \n}",
                "异常处理",
            ),
            snippet("println", "System.out.println(\"%CURSOR%\");", "输出"),
        ),
        SyntaxLanguage.CPP to listOf(
            snippet("main", "int main(int argc, char** argv) {\n    %CURSOR%\n}", "入口函数"),
            snippet("include", "#include <%CURSOR%>", "头文件"),
            snippet("if", "if (%CURSOR%) {\n    \n}", "条件"),
            snippet("for", "for (int i = 0; i < %CURSOR%; ++i) {\n    \n}", "循环"),
            snippet("cout", "std::cout << \"%CURSOR%\" << std::endl;", "输出"),
            snippet("vector", "std::vector<%CURSOR%>", "容器"),
        ),
        SyntaxLanguage.RUST to listOf(
            snippet("fn", "fn %CURSOR%() {\n    \n}", "函数"),
            snippet("main", "fn main() {\n    %CURSOR%\n}", "入口函数"),
            snippet("match", "match %CURSOR% {\n    _ => {}\n}", "模式匹配"),
            snippet("impl", "impl %CURSOR% {\n    \n}", "实现"),
            snippet("println!", "println!(\"%CURSOR%\");", "输出"),
        ),
        SyntaxLanguage.PYTHON to listOf(
            snippet("def", "def %CURSOR%():\n    ", "函数"),
            snippet("class", "class %CURSOR%:\n    def __init__(self):\n        ", "类"),
            snippet("if", "if %CURSOR%:\n    ", "条件"),
            snippet("for", "for item in %CURSOR%:\n    ", "循环"),
            snippet("try", "try:\n    %CURSOR%\nexcept Exception as e:\n    ", "异常处理"),
            snippet("print", "print(%CURSOR%)", "输出"),
            snippet("main", "if __name__ == \"__main__\":\n    %CURSOR%", "入口"),
        ),
        SyntaxLanguage.JAVASCRIPT to javascriptSnippets(),
        SyntaxLanguage.TYPESCRIPT to javascriptSnippets(),
        SyntaxLanguage.SHELL to listOf(
            snippet("if", "if [ %CURSOR% ]; then\n  \nfi", "条件"),
            snippet("for", "for item in %CURSOR%; do\n  \ndone", "循环"),
            snippet("function", "function %CURSOR%() {\n  \n}", "函数"),
            snippet(
                "case",
                "case \"\$value\" in\n  %CURSOR%)\n    \n    ;;\nesac",
                "分支",
            ),
            snippet("echo", "echo \"%CURSOR%\"", "输出"),
            snippet("command", "$(%CURSOR%)", "命令替换"),
        ),
        SyntaxLanguage.JSON to listOf(
            CodeCompletionItem("true", "true", detail = "布尔值"),
            CodeCompletionItem("false", "false", detail = "布尔值"),
            CodeCompletionItem("null", "null", detail = "空值"),
        ),
        SyntaxLanguage.MARKDOWN to listOf(
            snippet("heading", "## %CURSOR%\n", "标题"),
            snippet("code", "```\n%CURSOR%\n```", "代码块"),
            snippet("link", "[标题](%CURSOR%)", "链接"),
            snippet("task", "- [ ] %CURSOR%", "任务"),
            snippet("table", "| 列 | 列 |\n| --- | --- |\n| %CURSOR% |  |", "表格"),
        ),
    )

    private fun javascriptSnippets() = listOf(
        snippet("function", "function %CURSOR%() {\n  \n}", "函数"),
        snippet("if", "if (%CURSOR%) {\n  \n}", "条件"),
        snippet("for", "for (const item of %CURSOR%) {\n  \n}", "循环"),
        snippet(
            "try",
            "try {\n  %CURSOR%\n} catch (error) {\n  \n}",
            "异常处理",
        ),
        snippet("console", "console.log(\"%CURSOR%\");", "输出"),
        snippet("async", "async function %CURSOR%() {\n  await \n}", "异步函数"),
        snippet("fetch", "await fetch(\"%CURSOR%\");", "网络请求"),
    )

    private val keywords = mapOf(
        SyntaxLanguage.KOTLIN to listOf(
            "package", "import", "class", "object", "interface", "fun", "val", "var",
            "if", "else", "when", "for", "while", "return", "try", "catch", "finally",
            "private", "public", "internal", "protected", "suspend", "data", "sealed",
            "override", "companion", "lateinit", "by", "is", "in", "as",
        ),
        SyntaxLanguage.JAVA to listOf(
            "package", "import", "class", "interface", "enum", "public", "private",
            "protected", "static", "final", "void", "new", "return", "if", "else",
            "for", "while", "try", "catch", "finally", "extends", "implements",
            "instanceof", "synchronized", "abstract", "record",
        ),
        SyntaxLanguage.CPP to listOf(
            "include", "int", "char", "void", "auto", "const", "static", "struct",
            "class", "namespace", "using", "return", "if", "else", "for", "while",
            "switch", "case", "break", "continue", "template", "typename", "public",
            "private", "protected", "constexpr", "nullptr",
        ),
        SyntaxLanguage.RUST to listOf(
            "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else",
            "enum", "extern", "fn", "for", "if", "impl", "in", "let", "loop", "match",
            "mod", "move", "mut", "pub", "ref", "return", "self", "Self", "static",
            "struct", "super", "trait", "type", "unsafe", "use", "where", "while",
            "true", "false", "Some", "None", "Ok", "Err",
        ),
        SyntaxLanguage.TOML to listOf("true", "false"),
        SyntaxLanguage.PYTHON to listOf(
            "def", "class", "import", "from", "as", "if", "elif", "else", "for",
            "while", "return", "try", "except", "finally", "with", "lambda", "yield",
            "async", "await", "pass", "break", "continue", "True", "False", "None",
        ),
        SyntaxLanguage.JAVASCRIPT to listOf(
            "const", "let", "var", "function", "class", "import", "export", "from",
            "if", "else", "for", "while", "return", "async", "await", "try", "catch",
            "finally", "throw", "new", "extends", "switch", "case", "break",
        ),
        SyntaxLanguage.TYPESCRIPT to listOf(
            "const", "let", "var", "function", "class", "interface", "type", "import",
            "export", "from", "if", "else", "for", "while", "return", "async", "await",
            "try", "catch", "finally", "throw", "new", "extends", "implements",
            "readonly", "enum", "namespace", "private", "public", "protected",
        ),
        SyntaxLanguage.SHELL to listOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done",
            "case", "esac", "function", "export", "local", "return", "readonly",
        ),
        SyntaxLanguage.JSON to listOf("true", "false", "null"),
    )

    private val typeNames = mapOf(
        SyntaxLanguage.KOTLIN to listOf(
            "String", "Int", "Long", "Boolean", "Float", "Double", "List", "Map",
            "Set", "Any", "Unit", "Flow", "Result", "ByteArray",
        ),
        SyntaxLanguage.JAVA to listOf(
            "String", "Object", "Integer", "Long", "Boolean", "List", "Map", "Set",
            "void", "int", "long", "float", "double", "char", "byte",
        ),
        SyntaxLanguage.CPP to listOf(
            "string", "vector", "map", "set", "size_t", "uint8_t", "int32_t",
            "unique_ptr", "shared_ptr", "optional",
        ),
        SyntaxLanguage.RUST to listOf(
            "u8", "u16", "u32", "u64", "u128", "usize", "i8", "i16", "i32", "i64",
            "i128", "isize", "f32", "f64", "bool", "char", "str", "String", "Vec",
            "Option", "Result", "Box", "Self",
        ),
        SyntaxLanguage.PYTHON to listOf("str", "int", "float", "bool", "list", "dict", "set"),
        SyntaxLanguage.JAVASCRIPT to listOf(
            "String", "Number", "Boolean", "Array", "Object", "Promise", "Map", "Set",
        ),
        SyntaxLanguage.TYPESCRIPT to listOf(
            "string", "number", "boolean", "unknown", "never", "any", "Array",
            "Promise", "Record", "Partial", "Readonly",
        ),
    )

    private val allKeywords = keywords.values.flatten().toSet()

    private const val MAX_DOCUMENT_SYMBOLS = 120
    private const val MAX_SYMBOL_SCAN_CHARS = 80_000
    private const val CURSOR_MARKER = "%CURSOR%"
}
