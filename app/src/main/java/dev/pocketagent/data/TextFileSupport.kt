package dev.pocketagent.data

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object TextFileSupport {
    private val binaryExtensions = setOf(
        "7z", "a", "aab", "apk", "apks", "avi", "bin", "bmp", "bz2",
        "class", "db", "dex", "dll", "doc", "docx", "elf", "exe", "flac", "gif",
        "gz", "heic", "ico", "img", "iso", "jar", "jpeg", "jpg", "keystore",
        "mkv", "mov", "mp3", "mp4", "o", "odex", "ogg", "pdf", "png", "ppt",
        "pptx", "rar", "so", "sqlite", "tar", "vdex", "wav", "webp", "woff",
        "woff2", "xls", "xlsx", "xz", "zst", "zip",
    )

    fun check(path: String): String? {
        val extension = path.substringAfterLast('.', "").lowercase()
        return if (extension in binaryExtensions) {
            "不支持在文本编辑器中打开 .$extension 文件"
        } else {
            null
        }
    }

    fun decode(bytes: ByteArray, maxChars: Int): String {
        if (bytes.any { it == 0.toByte() }) {
            throw IllegalArgumentException("这是二进制文件，不支持文本预览")
        }
        val sample = bytes.copyOfRange(0, minOf(bytes.size, 8 * 1024))
        val controlCount = sample.count { value ->
            val unsigned = value.toInt() and 0xFF
            unsigned < 0x09 || (unsigned in 0x0E..0x1F)
        }
        require(controlCount * 20 <= sample.size.coerceAtLeast(1)) {
            "这是二进制文件，不支持文本预览"
        }
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .take(maxChars)
        }.getOrElse {
            throw IllegalArgumentException("文件不是 UTF-8 文本，无法安全预览")
        }
    }
}
