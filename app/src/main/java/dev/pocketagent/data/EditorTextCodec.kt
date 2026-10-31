package dev.pocketagent.data

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class EditorEncoding(val label: String, val charsetName: String, val bom: ByteArray = byteArrayOf()) {
    UTF8("UTF-8", "UTF-8"),
    UTF8_BOM("UTF-8 BOM", "UTF-8", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
    UTF16_LE_BOM("UTF-16 LE BOM", "UTF-16LE", byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
    UTF16_BE_BOM("UTF-16 BE BOM", "UTF-16BE", byteArrayOf(0xFE.toByte(), 0xFF.toByte())),
    UTF16_LE("UTF-16 LE", "UTF-16LE"),
    UTF16_BE("UTF-16 BE", "UTF-16BE"),
    GB18030("GB18030", "GB18030"),
    GBK("GBK", "GBK"),
    BIG5("Big5", "Big5"),
    WINDOWS1252("Windows-1252", "windows-1252"),
    LATIN1("ISO-8859-1", "ISO-8859-1"),
}

data class EditorDocument(val content: String, val encoding: EditorEncoding, val inferred: Boolean = false)

object EditorTextCodec {
    fun decode(bytes: ByteArray, requested: EditorEncoding? = null): EditorDocument {
        val marked = EditorEncoding.entries.firstOrNull { it.bom.isNotEmpty() && bytes.startsWith(it.bom) }
        val selected = requested ?: marked ?: runCatching {
            decodeStrict(bytes, EditorEncoding.UTF8)
            EditorEncoding.UTF8
        }.getOrDefault(EditorEncoding.GB18030)
        // Reopening only changes interpretation, never whether the file has a BOM.
        val encoding = marked?.takeIf { it.charsetName == selected.charsetName }
            ?: EditorEncoding.entries.first { it.charsetName == selected.charsetName && it.bom.isEmpty() }
        val content = try {
            decodeStrict(bytes, encoding)
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("无法按 ${encoding.label} 解码，请选择其他编码重新打开", error)
        }
        require(content.none { it == '\u0000' } && content.count {
            it < ' ' && it !in "\t\r\n\u000C"
        } * 20 <= content.length.coerceAtLeast(1)) { "这是二进制文件，不支持文本编辑" }
        return EditorDocument(content, encoding, requested == null && marked == null && encoding != EditorEncoding.UTF8)
    }

    fun encode(content: String, encoding: EditorEncoding): ByteArray {
        val buffer = try {
            Charset.forName(encoding.charsetName).newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(content))
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("${encoding.label} 无法表示部分字符，未保存；请选择 UTF-8 等编码", error)
        }
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return encoding.bom + bytes
    }

    private fun decodeStrict(bytes: ByteArray, encoding: EditorEncoding): String {
        val offset = if (encoding.bom.isNotEmpty() && bytes.startsWith(encoding.bom)) encoding.bom.size else 0
        return Charset.forName(encoding.charsetName).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
