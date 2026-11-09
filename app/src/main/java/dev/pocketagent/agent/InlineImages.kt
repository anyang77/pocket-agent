package dev.pocketagent.agent

import java.io.File
import java.util.Base64

/** Gateway chat responses may embed generated images as Markdown data URLs, not tool calls. */
internal object InlineImages {
    private const val MAX_BYTES = 32 * 1024 * 1024
    private val image = Regex("""!\[[^\]\r\n]{0,200}\]\(\s*(data:image/[^\s)]+)\s*\)|data:image/[^\s)]+""", RegexOption.IGNORE_CASE)
    private val header = Regex("""data:image/(png|jpeg|jpg|webp);base64,""", RegexOption.IGNORE_CASE)
    data class Result(val text: String, val paths: List<String>)

    /** No decoding or disk IO: safe for old history and request serialization. */
    fun strip(text: String): String = image.replace(text, "[图片数据已移除]")

    fun extract(text: String, directory: File): Result {
        val paths = mutableListOf<String>()
        var total = 0L
        try {
            val cleaned = image.replace(text) { match ->
                val data = match.groups[1]?.value ?: match.value
                val prefix = header.find(data)?.takeIf { it.range.first == 0 }
                if (prefix == null || paths.size >= 8 || data.length - prefix.value.length > (MAX_BYTES + 2L) / 3 * 4) {
                    return@replace "[图片数据不受支持或超过限制]"
                }
                val bytes = runCatching { Base64.getDecoder().decode(data.substring(prefix.value.length)) }.getOrNull()
                    ?: return@replace "[图片数据无效]"
                if (total + bytes.size > MAX_BYTES) return@replace "[图片总大小超过 32 MB]"
                val extension = runCatching { ImageGenerationClient.imageExtension(bytes) }.getOrNull()
                    ?: return@replace "[图片数据无效]"
                require(directory.mkdirs() || directory.isDirectory) { "无法创建图片附件目录" }
                val target = File.createTempFile("chat-image-", ".$extension", directory)
                paths += target.absolutePath
                target.writeBytes(bytes)
                total += bytes.size
                "[已生成图片：${target.name}]"
            }
            return Result(cleaned, paths)
        } catch (error: Throwable) {
            paths.forEach { File(it).delete() }
            throw error
        }
    }
}

/** Keep normal text streaming, but never send a partial or complete image data URL to Compose.
 * Once an image starts, the final response resolver supplies the remaining text and attachments.
 */
internal class InlineImagePreview {
    private val marker = "data:image/"
    private val pending = StringBuilder()
    var hidden = false
        private set
    fun append(delta: String): String {
        if (hidden) return ""
        pending.append(delta)
        val start = pending.indexOf(marker, ignoreCase = true)
        if (start >= 0) {
            hidden = true
            val safe = pending.substring(0, start)
            pending.clear()
            return safe
        }
        var held = minOf(marker.length - 1, pending.length)
        while (held > 0 && !pending.endsWith(marker.take(held), ignoreCase = true)) held--
        val safe = pending.substring(0, pending.length - held)
        pending.delete(0, pending.length - held)
        return safe
    }
    fun finish(): String = if (hidden) "" else pending.toString().also { pending.clear() }
}
