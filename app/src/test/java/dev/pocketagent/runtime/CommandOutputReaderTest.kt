package dev.pocketagent.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.PipedReader
import java.io.PipedWriter
import java.io.StringReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CommandOutputReaderTest {
    @Test fun emitsWithoutNewlineBeforeProducerFinishes() {
        val reader = PipedReader()
        val writer = PipedWriter(reader)
        val outputSeen = CountDownLatch(1)
        val producerDone = CountDownLatch(1)
        var captured = ""
        val worker = thread {
            captured = readCommandOutput(reader, 100) { outputSeen.countDown() }
            producerDone.countDown()
        }
        try {
            writer.write("progress 10%")
            writer.flush()
            assertTrue(outputSeen.await(2, TimeUnit.SECONDS))
            assertEquals(1L, producerDone.count)
        } finally {
            writer.close()
            worker.join(2000)
        }
        assertEquals("progress 10%", captured)
    }

    @Test fun captureLimitDoesNotStopStreamingAndPreservesExactText() {
        val streamed = StringBuilder()
        val captured = readCommandOutput(StringReader("abc\rdef\nlast"), 4) { streamed.append(it) }
        assertEquals("abc\r", captured)
        assertEquals("abc\rdef\nlast", streamed.toString())
    }
}
