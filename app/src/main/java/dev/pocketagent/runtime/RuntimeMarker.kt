package dev.pocketagent.runtime

import java.io.File

/** A missing/unreadable marker means repair is needed, not an uncaught UI exception. */
internal fun readRuntimeMarker(file: File): String? = runCatching {
    if (file.isFile) file.inputStream().bufferedReader().use { it.readLine()?.trim() } else null
}.getOrNull()
