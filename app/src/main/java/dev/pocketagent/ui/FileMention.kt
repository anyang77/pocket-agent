package dev.pocketagent.ui

internal data class FileMention(val start: Int, val end: Int, val query: String)

/** Only a standalone @ token at the caret opens suggestions; email addresses stay ordinary text. */
internal fun activeFileMention(text: String, caret: Int, collapsed: Boolean = true): FileMention? {
    if (!collapsed || caret !in 0..text.length) return null
    var start = caret - 1
    while (start >= 0 && !text[start].isWhitespace() && text[start] != '@') start--
    if (start < 0 || text[start] != '@' || (start > 0 && !text[start - 1].isWhitespace())) return null
    return FileMention(start, caret, text.substring(start + 1, caret))
}

internal fun replaceFileMention(text: String, mention: FileMention, path: String): Pair<String, Int> {
    val reference = if (path.any(Char::isWhitespace)) "@\"$path\" " else "@$path "
    return text.replaceRange(mention.start, mention.end, reference) to (mention.start + reference.length)
}
