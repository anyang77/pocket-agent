package dev.pocketagent.ui

object ShellCharacterChecker {
    private val fullWidthPunctuation = mapOf(
        '，' to ',',
        '。' to '.',
        '；' to ';',
        '：' to ':',
        '！' to '!',
        '？' to '?',
        '（' to '(',
        '）' to ')',
        '【' to '[',
        '】' to ']',
        '｛' to '{',
        '｝' to '}',
        '“' to '"',
        '”' to '"',
        '‘' to '\'',
        '’' to '\'',
        '、' to ',',
        '《' to '<',
        '》' to '>',
        '「' to '"',
        '」' to '"',
        '『' to '"',
        '』' to '"',
        '＄' to '$',
        '％' to '%',
        '＆' to '&',
        '＊' to '*',
        '＋' to '+',
        '－' to '-',
        '／' to '/',
        '＝' to '=',
        '＠' to '@',
        '＾' to '^',
        '＿' to '_',
        '｜' to '|',
        '～' to '~',
        '＃' to '#',
        '＜' to '<',
        '＞' to '>',
    )

    fun check(content: String): List<EditorDiagnostic> {
        val diagnostics = mutableListOf<EditorDiagnostic>()
        val heredocLines = heredocBodyLines(content)
        var quote: Char? = null
        var comment = false
        var line = 1
        var column = 1
        var index = 0

        while (index < content.length && diagnostics.size < 50) {
            val char = content[index]
            if (char == '\n') {
                line++
                column = 1
                comment = false
                index++
                continue
            }
            if (line in heredocLines) {
                column++
                index++
                continue
            }
            if (comment) {
                column++
                index++
                continue
            }
            if (quote != null) {
                if (char == '\\' && quote == '"' && index + 1 < content.length) {
                    index += 2
                    column += 2
                    continue
                }
                if (char == quote) quote = null
                column++
                index++
                continue
            }
            if (char == '\\' && index + 1 < content.length) {
                index += 2
                column += 2
                continue
            }
            if (char == '\'' || char == '"') {
                quote = char
                column++
                index++
                continue
            }
            if (char == '#' && startsComment(content, index)) {
                comment = true
                column++
                index++
                continue
            }

            val codePoint = content.codePointAt(index)
            val character = String(Character.toChars(codePoint))
            val recommendation = fullWidthPunctuation[char]
            when {
                codePoint == 0xFFFD -> {
                    diagnostics += diagnostic(line, column, "无效的替换字符 U+FFFD")
                }
                Character.isISOControl(char) && char != '\t' && char != '\r' -> {
                    diagnostics += diagnostic(
                        line,
                        column,
                        "不可见控制字符 U+${codePoint.toString(16).uppercase().padStart(4, '0')}",
                    )
                }
                isHiddenFormattingCharacter(codePoint) -> {
                    diagnostics += diagnostic(
                        line,
                        column,
                        "不可见格式字符 U+${codePoint.toString(16).uppercase().padStart(4, '0')}",
                    )
                }
                recommendation != null -> {
                    diagnostics += diagnostic(
                        line,
                        column,
                        "无效的全角标点“$character”，这里应使用英文 $recommendation",
                        recommendation.toString(),
                    )
                }
            }
            val width = character.length
            index += width
            column += width
        }
        return diagnostics
    }

    private fun heredocBodyLines(content: String): Set<Int> {
        val marker = Regex("""<<-?\s*(['"]?)([A-Za-z_][A-Za-z0-9_]*)\1""")
        val bodyLines = mutableSetOf<Int>()
        var delimiter: String? = null
        content.lineSequence().forEachIndexed { index, line ->
            val lineNumber = index + 1
            val currentDelimiter = delimiter
            if (currentDelimiter != null) {
                if (line.trimStart('\t').trimEnd() == currentDelimiter) {
                    delimiter = null
                } else {
                    bodyLines += lineNumber
                }
            } else {
                delimiter = marker.find(line)
                    ?.groupValues
                    ?.getOrNull(2)
                    ?.takeIf { it.isNotBlank() }
            }
        }
        return bodyLines
    }

    private fun startsComment(content: String, index: Int): Boolean {
        if (index == 0) return true
        return content[index - 1].isWhitespace() ||
            content[index - 1] in setOf(';', '|', '&', '(', ')')
    }

    private fun isHiddenFormattingCharacter(codePoint: Int): Boolean =
        codePoint == 0xFEFF ||
            codePoint in 0x200B..0x200F ||
            codePoint in 0x202A..0x202E ||
            codePoint in 0x2066..0x2069

    private fun diagnostic(
        line: Int,
        column: Int,
        message: String,
        replacement: String? = null,
    ) = EditorDiagnostic(
        line = line,
        column = column,
        message = message,
        replacement = replacement,
    )
}
