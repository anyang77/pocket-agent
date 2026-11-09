package dev.pocketagent.agent

/** Monotonic timings. Recent speed excludes connection/model wait and uses a bounded window. */
internal class ImageTransferMeter(
    private val started: Long = System.nanoTime(),
    private val clock: () -> Long = System::nanoTime,
) {
    private var firstByte: Long? = null
    private var baselineTime = started
    private var baselineBytes = 0L
    private var rate: Long? = null

    fun progress(stage: ImageGenerationStage, bytes: Long, total: Long?): ImageGenerationProgress {
        val now = clock()
        if (bytes > 0 && firstByte == null) {
            firstByte = now
            baselineTime = now
            baselineBytes = bytes
        }
        val interval = now - baselineTime
        if (firstByte != null && interval >= 1_000_000_000L) {
            rate = ((bytes - baselineBytes).coerceAtLeast(0) * 1_000_000_000L / interval)
            baselineTime = now
            baselineBytes = bytes
        }
        return ImageGenerationProgress(stage, bytes, total,
            waitMs = ((firstByte ?: now) - started).coerceAtLeast(0) / 1_000_000,
            transferMs = firstByte?.let { (now - it).coerceAtLeast(0) / 1_000_000 } ?: 0,
            recentBytesPerSecond = rate, measuredAtNanos = now)
    }
}
