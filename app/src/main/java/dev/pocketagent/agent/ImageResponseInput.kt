package dev.pocketagent.agent

import java.io.FilterInputStream
import java.io.InputStream

/** Time blocked in body reads separately from scanning, copying and progress delivery.
 * The first read belongs to time-to-first-byte, not transfer time. No response data is retained.
 */
internal class ImageResponseInput(
    input: InputStream,
    private val onFirstBytes: () -> Unit = {},
    private val clock: () -> Long = System::nanoTime,
) : FilterInputStream(input) {
    var readWaitNanos: Long = 0
        private set
    private var hasBytes = false

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val transferring = hasBytes
        val start = clock()
        try {
            return `in`.read(b, off, len).also {
                if (it > 0 && !hasBytes) {
                    hasBytes = true
                    onFirstBytes()
                }
            }
        } finally {
            if (transferring) readWaitNanos += (clock() - start).coerceAtLeast(0)
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }
}
