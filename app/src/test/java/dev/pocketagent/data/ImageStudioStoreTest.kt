package dev.pocketagent.data

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ImageStudioStoreTest {
    @Test fun diagnosticsRoundTripAndOldHistoryRemainsReadable() {
        val dir = Files.createTempDirectory("studio-diagnostics").toFile()
        try {
            val file = java.io.File(dir, "history.json")
            val store = ImageStudioStore(file)
            val old = StudioArtwork("id", "/tmp/image.png", "draw", "model", "auto", 10, 20)
            file.writeText("""[{"id":"id","path":"/tmp/image.png","prompt":"draw","model":"model","size":"auto","created_at":10,"elapsed_ms":20}]""")
            assertEquals(listOf(old), store.load())
            val measured = old.copy(diagnostics = "等待首字节 44.70s · 接收 4.00s", requestId = "batch-1")
            store.save(listOf(measured))
            assertEquals(listOf(measured), store.load())
        } finally { dir.deleteRecursively() }
    }
}
