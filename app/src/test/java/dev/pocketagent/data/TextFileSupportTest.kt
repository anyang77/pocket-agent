package dev.pocketagent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TextFileSupportTest {
    @Test
    fun rejectsKnownBinaryExtensions() {
        assertNotNull(TextFileSupport.check("archive.zip"))
        assertNotNull(TextFileSupport.check("app.apk"))
        assertNotNull(TextFileSupport.check("photo.PNG"))
        assertNull(TextFileSupport.check("Main.kt"))
        assertNull(TextFileSupport.check("README.md"))
    }

    @Test
    fun rejectsNulBytesAndInvalidUtf8() {
        assertThrows(IllegalArgumentException::class.java) {
            TextFileSupport.decode(byteArrayOf(0x50, 0x4B, 0x00, 0x04), 1_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextFileSupport.decode(byteArrayOf(0xC3.toByte(), 0x28), 1_000)
        }
    }

    @Test
    fun decodesUtf8Text() {
        assertEquals(
            "你好\nPocket Agent",
            TextFileSupport.decode("你好\nPocket Agent".toByteArray(), 1_000),
        )
    }
}
