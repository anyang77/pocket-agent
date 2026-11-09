package dev.pocketagent.agent

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/** Read one JSON object without waiting for a proxy to close a completed response body.
 * Scan bytes once; braces inside JSON strings (including escaped quotes) are not framing.
 * Parsing/validation remains the caller's responsibility. Never used for SSE or binary files.
 */
internal object JsonResponseReader {
    fun read(input: InputStream, limit: Int, onBytes: (Long) -> Unit = {}): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var started = false
        var depth = 0
        var quoted = false
        var escaped = false
        while (true) {
            val count = input.read(buffer)
            if (count == -1) throw EOFException("JSON 响应在接收完整前中断")
            var end = count
            var complete = false
            for (i in 0 until count) {
                val value = buffer[i].toInt() and 0xff
                if (!started) {
                    if (value == 32 || value == 9 || value == 10 || value == 13) continue
                    if (value != '{'.code) throw AgentException("服务返回的不是 JSON 对象")
                    started = true
                    depth = 1
                } else if (quoted) {
                    if (escaped) escaped = false
                    else if (value == '\\'.code) escaped = true
                    else if (value == '"'.code) quoted = false
                } else when (value) {
                    '"'.code -> quoted = true
                    '{'.code, '['.code -> depth++
                    '}'.code, ']'.code -> {
                        depth--
                        if (depth == 0) {
                            end = i + 1
                            complete = true
                            break
                        }
                    }
                }
            }
            if (output.size().toLong() + end > limit) throw AgentException("JSON 响应超过大小限制，不会自动重试")
            output.write(buffer, 0, end)
            onBytes(output.size().toLong())
            if (complete) return output.toByteArray()
        }
    }
}
