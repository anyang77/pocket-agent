package dev.pocketagent.data

import org.junit.Assert.*
import org.junit.Test

class EditorTextCodecTest {
    @Test fun utf8AndBomAreDetectedAndPreserved() {
        for (encoding in listOf(EditorEncoding.UTF8, EditorEncoding.UTF8_BOM,
            EditorEncoding.UTF16_LE_BOM, EditorEncoding.UTF16_BE_BOM)) {
            val text = "你好\r\nCodeMate 🙂\n"
            val bytes = EditorTextCodec.encode(text, encoding)
            val result = EditorTextCodec.decode(bytes)
            assertEquals(text, result.content)
            assertEquals(encoding, result.encoding)
            assertFalse(result.inferred)
            assertArrayEquals(bytes, EditorTextCodec.encode(result.content, result.encoding))
        }
    }

    @Test fun legacyChineseIsLabeledAsInferred() {
        val bytes = EditorTextCodec.encode("中文编码测试", EditorEncoding.GBK)
        val result = EditorTextCodec.decode(bytes)
        assertEquals("中文编码测试", result.content)
        assertEquals(EditorEncoding.GB18030, result.encoding)
        assertTrue(result.inferred)
    }

    @Test fun explicitEncodingsRoundTrip() {
        for (encoding in EditorEncoding.entries) {
            val text = when (encoding) {
                EditorEncoding.WINDOWS1252 -> "café €\r\n"
                EditorEncoding.LATIN1 -> "café\r\n"
                else -> "中文\r\nabc\n"
            }
            val bytes = EditorTextCodec.encode(text, encoding)
            val result = EditorTextCodec.decode(bytes, encoding)
            assertEquals(text, result.content)
            assertEquals(encoding, result.encoding)
            assertFalse(result.inferred)
            assertArrayEquals(bytes, EditorTextCodec.encode(result.content, result.encoding))
        }
    }

    @Test fun cannotSilentlyReplaceUnmappableCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            EditorTextCodec.encode("中文🙂", EditorEncoding.LATIN1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EditorTextCodec.encode("🙂", EditorEncoding.GBK)
        }
    }

    @Test fun rejectsInvalidInputAndBinary() {
        assertThrows(IllegalArgumentException::class.java) {
            EditorTextCodec.decode(byteArrayOf(0xC3.toByte(), 0x28), EditorEncoding.UTF8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EditorTextCodec.decode(byteArrayOf(1, 0, 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EditorTextCodec.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41))
        }
    }

    @Test fun emptyFileDefaultsToUtf8() {
        assertEquals(EditorDocument("", EditorEncoding.UTF8), EditorTextCodec.decode(byteArrayOf()))
    }

    @Test fun reopeningReportsActualBomWithoutDuplicatingIt() {
        val marked = EditorTextCodec.encode("你好", EditorEncoding.UTF8_BOM)
        val reopened = EditorTextCodec.decode(marked, EditorEncoding.UTF8)
        assertEquals("你好", reopened.content)
        assertEquals(EditorEncoding.UTF8_BOM, reopened.encoding)
        assertArrayEquals(marked, EditorTextCodec.encode(reopened.content, reopened.encoding))
        val plain = EditorTextCodec.decode("你好".toByteArray(), EditorEncoding.UTF8_BOM)
        assertEquals(EditorEncoding.UTF8, plain.encoding)
        val utf16 = EditorTextCodec.decode(EditorTextCodec.encode("hi", EditorEncoding.UTF16_LE_BOM), EditorEncoding.UTF16_LE)
        assertEquals("hi", utf16.content)
        assertEquals(EditorEncoding.UTF16_LE_BOM, utf16.encoding)
    }

    @Test fun conversionPreservesTextAndLineEndings() {
        val original = EditorTextCodec.encode("你好\r\n世界\r\n", EditorEncoding.GBK)
        val text = EditorTextCodec.decode(original, EditorEncoding.GBK).content
        val converted = EditorTextCodec.encode(text, EditorEncoding.UTF8_BOM)
        assertEquals(text, EditorTextCodec.decode(converted).content)
        assertFalse(original.contentEquals(converted))
    }
}
