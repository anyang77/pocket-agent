package dev.pocketagent.agent

internal data class FileChangePreview(val additions: Int, val deletions: Int, val diff: String)

/** Bounded LCS keeps separated edits accurate without quadratic work on giant files. */
internal fun fileChangePreview(before: String, after: String): FileChangePreview {
    fun lines(s: String) = if (s.isEmpty()) emptyList() else s.removeSuffix("\n").split('\n')
    val old = lines(before); val new = lines(after)
    var prefix = 0; var suffix = 0
    while (prefix < minOf(old.size, new.size) && old[prefix] == new[prefix]) prefix++
    while (suffix < minOf(old.size, new.size) - prefix && old[old.lastIndex - suffix] == new[new.lastIndex - suffix]) suffix++
    val a = old.subList(prefix, old.size - suffix); val b = new.subList(prefix, new.size - suffix)
    val output = StringBuilder(); var added = 0; var removed = 0; var shown = 0; var truncated = false; var approximate = false
    fun emit(kind: Char, line: String) {
        if (kind == '+') added++ else if (kind == '-') removed++
        if (line.length > 2_000) truncated = true
        if (shown < 600 && output.length < 60_000) {
            output.append(kind).append(line.take(2_000)).append('\n'); shown++
        } else truncated = true
    }
    if (a.isEmpty() && b.isEmpty()) {
        if (before != after) {
            emit('-', old.lastOrNull().orEmpty()); emit('+', new.lastOrNull().orEmpty())
            output.append("\\ No newline at end of ").append(if (after.endsWith('\n')) "old file" else "new file").append('\n')
        }
        return FileChangePreview(added, removed, output.toString().removeSuffix("\n"))
    }
    val contextBefore = minOf(prefix, 3)
    val contextAfter = minOf(suffix, 3)
    output.append("@@ -${prefix - contextBefore + 1},${a.size + contextBefore + contextAfter} +${prefix - contextBefore + 1},${b.size + contextBefore + contextAfter} @@\n")
    old.subList((prefix - 3).coerceAtLeast(0), prefix).forEach { emit(' ', it) }
    if (a.size.toLong() * b.size <= 500_000 && a.size + b.size <= 4_000) {
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed())
            lcs[i][j] = if (a[i] == b[j]) 1 + lcs[i + 1][j + 1] else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        var i = 0; var j = 0
        while (i < a.size || j < b.size) when {
            i < a.size && j < b.size && a[i] == b[j] -> { emit(' ', a[i]); i++; j++ }
            i < a.size && (j == b.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> emit('-', a[i++])
            else -> emit('+', b[j++])
        }
    } else {
        approximate = true
        a.forEach { emit('-', it) }; b.forEach { emit('+', it) }
    }
    old.subList(old.size - suffix, minOf(old.size, old.size - suffix + 3)).forEach { emit(' ', it) }
    if (approximate) output.insert(0, "≈ 大范围替换按块估算，增删统计可能包含块内未变行\n")
    if (truncated) output.append("… 差异预览已截断，统计包含全部改动\n")
    return FileChangePreview(added, removed, output.toString().removeSuffix("\n"))
}

/** Reads complete fields and a safely decoded prefix of an incomplete JSON string. */
internal fun partialToolStrings(raw: String): Map<String, String> {
    val result = linkedMapOf<String, String>(); var index = 0
    fun skip() { while (index < raw.length && raw[index].isWhitespace()) index++ }
    fun string(): Pair<String, Boolean>? {
        if (index >= raw.length || raw[index++] != '"') return null
        val value = StringBuilder()
        while (index < raw.length) {
            val char = raw[index++]
            if (char == '"') return value.toString() to true
            if (char != '\\') { value.append(char); continue }
            if (index == raw.length) break
            when (val escaped = raw[index++]) {
                'n' -> value.append('\n'); 'r' -> value.append('\r'); 't' -> value.append('\t')
                'b' -> value.append('\b'); 'f' -> value.append('\u000c')
                '"', '\\', '/' -> value.append(escaped)
                'u' -> {
                    if (index + 4 > raw.length) break
                    val code = raw.substring(index, index + 4).toIntOrNull(16) ?: break
                    value.append(code.toChar()); index += 4
                }
                else -> break
            }
        }
        return value.toString() to false
    }
    skip(); if (index >= raw.length || raw[index++] != '{') return result
    while (index < raw.length) {
        skip(); if (index < raw.length && raw[index] == ',') { index++; skip() }
        val key = string() ?: break
        if (!key.second) break
        skip(); if (index >= raw.length || raw[index++] != ':') break
        skip()
        if (index < raw.length && raw[index] == '"') {
            val value = string() ?: break
            result[key.first] = value.first
            if (!value.second) break
            result["${key.first}:complete"] = "true"
        } else {
            val start = index
            while (index < raw.length && raw[index] != ',' && raw[index] != '}') index++
            result[key.first] = raw.substring(start, index).trim()
        }
    }
    return result
}
