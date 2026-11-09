package dev.pocketagent.agent

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class ImageResponseInputTest {
    @Test fun excludesFirstReadAndLocalProcessingButCountsLaterReadWait() {
        var time = 0L
        var reads = 0
        val source = object : ByteArrayInputStream(byteArrayOf(1, 2)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                time += if (reads++ == 0) 40_000_000_000L else 2_000_000_000L
                return super.read(b, off, minOf(len, 1))
            }
        }
        val measured = ImageResponseInput(source) { time }
        val buffer = ByteArray(8192)
        assertEquals(1, measured.read(buffer))
        assertEquals(0L, measured.readWaitNanos)
        time += 9_000_000_000L // JSON scan, copying and UI work must not count as a read.
        assertEquals(1, measured.read(buffer))
        assertEquals(2_000_000_000L, measured.readWaitNanos)
        assertEquals(-1, measured.read(buffer))
        assertEquals(4_000_000_000L, measured.readWaitNanos)
    }

    @Test fun switchesTimeoutOnlyAfterFirstSuccessfulBodyRead() {
        var switched = 0
        val source = object : ByteArrayInputStream(byteArrayOf(1, 2)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                assertEquals(if (available() == 2) 0 else 1, switched)
                return super.read(b, off, minOf(len, 1))
            }
        }
        val measured = ImageResponseInput(source, onFirstBytes = { switched++ })
        assertEquals(0, switched)
        assertEquals(1, measured.read())
        assertEquals(1, switched)
        assertEquals(2, measured.read())
        assertEquals(-1, measured.read())
        assertEquals(1, switched)
    }

    @Test fun countsFailedReadWithoutLeakingResponseOrExceptionData() {
        var time = 0L
        var first = true
        val source = object : InputStream() {
            override fun read(): Int = error("bulk reads only")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (first) { first = false; b[off] = 123; return 1 }
                time += 60_000_000_000L
                throw IOException("private network details")
            }
        }
        val measured = ImageResponseInput(source) { time }
        assertEquals(123, measured.read())
        try { measured.read(ByteArray(1024)); fail() } catch (_: IOException) { }
        assertEquals(60_000_000_000L, measured.readWaitNanos)
    }
}
