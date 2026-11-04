package dev.pocketagent.runtime

import java.io.Reader

/** Read available chunks rather than waiting for a newline or process exit. */
internal fun readCommandOutput(reader: Reader, maxChars: Int, onOutput: (String) -> Unit): String {
    val captured = StringBuilder()
    val buffer = CharArray(2048)
    reader.use {
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            val chunk = String(buffer, 0, count)
            if (captured.length < maxChars) captured.append(chunk.take(maxChars - captured.length))
            onOutput(chunk)
        }
    }
    return captured.toString()
}
