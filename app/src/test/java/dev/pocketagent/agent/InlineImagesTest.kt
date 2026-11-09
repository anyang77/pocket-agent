package dev.pocketagent.agent

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class InlineImagesTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6cKAAAAAASUVORK5CYII=")
    private val data get() = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)

    @Test fun extractsGatewayResponseToAttachmentAndPreservesText() {
        val dir = Files.createTempDirectory("inline-images").toFile()
        try {
            val result = InlineImages.extract("{\"prompt\":\"test\"}![image_1]($data) done", dir)
            assertEquals(1, result.paths.size)
            assertArrayEquals(png, File(result.paths.single()).readBytes())
            assertFalse(result.text.contains("base64"))
            assertTrue(result.text.endsWith(" done"))
            assertTrue(result.text.contains("已生成图片"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun multipleImagesAreUniqueAndNonImagesNeverSaved() {
        val dir = Files.createTempDirectory("inline-images").toFile()
        try {
            val result = InlineImages.extract("![]($data) ![]($data) ![](data:image/png;base64,aGVsbG8=)", dir)
            assertEquals(2, result.paths.distinct().size)
            assertTrue(result.text.contains("图片数据无效"))
            assertFalse(result.text.contains("data:image"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsUnsupportedAndMalformedDataWithoutEchoingPayload() {
        val dir = Files.createTempDirectory("inline-images").toFile()
        try {
            val result = InlineImages.extract("![](data:image/svg+xml;base64,PHN2Zz4=) ![](data:image/png;base64,invalid!)", dir)
            assertTrue(result.paths.isEmpty())
            assertFalse(result.text.contains("data:image"))
            assertFalse(result.text.contains("invalid!"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun stripsOldHistoryWithoutReadingOrSavingImages() {
        val result = InlineImages.strip("before ![]($data) after")
        assertEquals("before [图片数据已移除] after", result)
        assertEquals("ordinary text", InlineImages.strip("ordinary text"))
    }

    @Test fun everyStreamingSplitHidesImageBytes() {
        val input = "hello ![image_1]($data) after"
        for (split in 0..input.length) {
            val filter = InlineImagePreview()
            val preview = filter.append(input.take(split)) + filter.append(input.drop(split)) + filter.finish()
            assertEquals("hello ![image_1](", preview)
            assertFalse(preview.contains("iVBOR"))
        }
    }

    @Test fun singleCharacterChunksAndPlainTextRemainCorrect() {
        for (input in listOf("normal data: text", "data:imaginary", "a DATA:IMAGE/png;base64,hidden")) {
            val filter = InlineImagePreview()
            val preview = input.map { filter.append(it.toString()) }.joinToString("") + filter.finish()
            assertEquals(if (input.contains("DATA:IMAGE")) "a " else input, preview)
        }
    }
}
