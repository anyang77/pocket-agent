package dev.pocketagent.ui

/** Very long generated lines can exceed Android text-layout/Canvas limits. */
internal fun gitDiffDisplayLines(diff: String): List<String> = diff.lineSequence().map { line ->
    if (line.length > 2_000) line.take(2_000) + " … [此行过长，显示已截断]" else line
}.toList()
