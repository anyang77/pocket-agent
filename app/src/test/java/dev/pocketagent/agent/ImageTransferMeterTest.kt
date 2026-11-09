package dev.pocketagent.agent

import org.junit.Assert.*
import org.junit.Test

class ImageTransferMeterTest {
    @Test fun speedExcludesLongWaitBeforeFirstBodyBytes() {
        var time = 0L
        val meter = ImageTransferMeter(0) { time }
        time = 120_000_000_000L
        val headers = meter.progress(ImageGenerationStage.RECEIVING, 0, null)
        assertEquals(120_000L, headers.waitMs)
        assertNull(headers.recentBytesPerSecond)
        time += 30_000_000_000L
        val first = meter.progress(ImageGenerationStage.RECEIVING, 8192, null)
        assertEquals(150_000L, first.waitMs)
        assertEquals(0L, first.transferMs)
        time += 1_000_000_000L
        val body = meter.progress(ImageGenerationStage.RECEIVING, 8192 + 1024 * 1024, null)
        assertEquals(150_000L, body.waitMs)
        assertEquals(1000L, body.transferMs)
        assertEquals(1024L * 1024, body.recentBytesPerSecond)
        assertEquals(time, body.measuredAtNanos)
    }

    @Test fun recentRateDropsOnStallAndRecoversWithoutLifetimeAveraging() {
        var time = 0L
        val meter = ImageTransferMeter(0) { time }
        meter.progress(ImageGenerationStage.DOWNLOADING, 100, 10000)
        time += 1_000_000_000L
        assertEquals(1024L, meter.progress(ImageGenerationStage.DOWNLOADING, 1124, 10000).recentBytesPerSecond)
        time += 20_000_000_000L
        assertEquals(0L, meter.progress(ImageGenerationStage.DOWNLOADING, 1124, 10000).recentBytesPerSecond)
        time += 1_000_000_000L
        val recovered = meter.progress(ImageGenerationStage.DOWNLOADING, 5220, 10000)
        assertEquals(4096L, recovered.recentBytesPerSecond)
        assertEquals(22000L, recovered.transferMs)
        assertEquals(10000L, recovered.totalBytes)
    }
}
