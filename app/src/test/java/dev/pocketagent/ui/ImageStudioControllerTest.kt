package dev.pocketagent.ui

import dev.pocketagent.agent.AgentPermissions
import dev.pocketagent.agent.GeneratedImage
import dev.pocketagent.agent.PermissionMode
import dev.pocketagent.data.AiSettings
import dev.pocketagent.data.ImageStudioStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class ImageStudioControllerTest {
    private val settings = AiSettings(model = "chosen-image", apiKey = "test-key")
    private val allowed = AgentPermissions(mode = PermissionMode.AUTO, allowNetwork = true)
    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private suspend fun waitFor(controller: ImageStudioController, predicate: (ImageStudioState) -> Boolean) {
        withTimeout(4000) { while (!predicate(controller.state.value)) delay(10) }
    }

    @Test fun persistsOriginalsAndMetadataWithoutChangingAgentSettings() = runBlocking {
        val dir = Files.createTempDirectory("studio-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        try {
            val controller = ImageStudioController(scope, File(dir, "records")) { config, prompt, size ->
                assertEquals(settings, config); assertEquals("draw a flower", prompt); assertEquals("1024x1536", size)
                calls.incrementAndGet(); GeneratedImage(png, "png")
            }
            waitFor(controller) { !it.loading }
            controller.edit { it.copy(prompt = "draw a flower", size = "1024x1536", count = 2, profileId = "images") }
            controller.generate(settings, dir, allowed)
            controller.generate(settings, dir, allowed) // Duplicate tap must not submit again.
            waitFor(controller) { !it.running && it.history.size == 2 }
            assertEquals(2, calls.get())
            assertTrue(controller.state.value.history.all { File(it.path).readBytes().contentEquals(png) })
            val records = ImageStudioStore(File(dir, "records/history.json")).load()
            assertEquals(controller.state.value.history, records)
            assertTrue(records.all { it.profileId == "images" })
            assertFalse(File(dir, "records/history.json").readText().contains("test-key"))
            assertEquals("chosen-image", settings.model)
            assertEquals("", controller.state.value.prompt)
            assertEquals("draw a flower", controller.state.value.submittedPrompt)
            assertEquals(1, records.map { it.requestId }.distinct().size)
            assertTrue(records.all { it.requestId.isNotBlank() })
            assertEquals(1, studioConversationGroups(records).size)
            waitFor(controller) { File(dir, "records/draft.json").isFile &&
                org.json.JSONObject(File(dir, "records/draft.json").readText()).optString("prompt").isEmpty() }
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun batchFailureStopsWithoutRetryAndKeepsCompletedWork() = runBlocking {
        val dir = Files.createTempDirectory("studio-fail").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        try {
            val controller = ImageStudioController(scope, File(dir, "records")) { _, _, _ ->
                if (calls.incrementAndGet() == 2) error("service unavailable")
                GeneratedImage(png, "png")
            }
            waitFor(controller) { !it.loading }
            controller.edit { it.copy(prompt = "draw", count = 4) }
            controller.generate(settings, dir, allowed)
            waitFor(controller) { !it.running && it.error != null }
            assertEquals(2, calls.get())
            assertEquals(1, controller.state.value.history.size)
            assertEquals(1, ImageStudioStore(File(dir, "records/history.json")).load().size)
            assertEquals("draw", controller.state.value.prompt)
            assertEquals("draw", controller.state.value.submittedPrompt)
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun permissionsAndCancellationNeverTriggerExtraRequest() = runBlocking {
        val dir = Files.createTempDirectory("studio-stop").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        try {
            val controller = ImageStudioController(scope, File(dir, "records")) { _, _, _ ->
                calls.incrementAndGet(); awaitCancellation()
            }
            waitFor(controller) { !it.loading }
            controller.edit { it.copy(prompt = "draw") }
            controller.generate(settings, dir, allowed.copy(allowNetwork = false))
            assertEquals(0, calls.get())
            assertNotNull(controller.state.value.error)
            controller.generate(settings, dir, allowed.copy(mode = PermissionMode.READ_ONLY))
            assertEquals(0, calls.get())
            controller.generate(settings, dir, allowed)
            withTimeout(2000) { while (calls.get() == 0) delay(10) }
            controller.stop()
            waitFor(controller) { !it.running }
            assertEquals(1, calls.get())
            assertTrue(controller.state.value.status.orEmpty().contains("可能仍在生成"))
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun timeoutEndsWaitingWithoutRetry() = runBlocking {
        val dir = Files.createTempDirectory("studio-timeout").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        try {
            val controller = ImageStudioController(scope, dir, requestTimeoutMs = 100) { _, _, _ ->
                calls.incrementAndGet(); awaitCancellation()
            }
            waitFor(controller) { !it.loading }
            controller.edit { it.copy(prompt = "draw") }
            controller.generate(settings, dir, allowed)
            waitFor(controller) { !it.running && it.error != null }
            assertEquals(1, calls.get())
            assertTrue(controller.state.value.error.orEmpty().contains("不会自动重试"))
            assertTrue(controller.state.value.history.isEmpty())
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun conversationGroupingKeepsIdenticalPromptsInSeparateTurnsAndLoadsLegacyItems() {
        fun artwork(id: String, requestId: String = "") = dev.pocketagent.data.StudioArtwork(
            id, "/tmp/$id.png", "same prompt", "image-model", "auto", 0, 1, requestId = requestId)
        val old = artwork("old")
        val first = artwork("first", "batch-a")
        val second = artwork("second", "batch-a")
        val next = artwork("next", "batch-b")
        val groups = studioConversationGroups(listOf(next, second, first, old))
        assertEquals(listOf(listOf(old), listOf(first, second), listOf(next)), groups)
    }

    @Test fun historyPositionIncludesNoticesAndFindsAnyImageInBatch() {
        fun art(id: String, batch: String) = dev.pocketagent.data.StudioArtwork(
            id, "/tmp/$id.png", "prompt", "image-model", "auto", 0, 1, requestId = batch)
        val groups = studioConversationGroups(listOf(art("new", "b"), art("two", "a"), art("one", "a")))
        assertEquals(0, studioHistoryIndex(groups, "two", loading = false, profileMissing = false))
        assertEquals(2, studioHistoryIndex(groups, "new", loading = false, profileMissing = true))
        assertEquals(3, studioHistoryIndex(groups, "new", loading = true, profileMissing = true))
        assertNull(studioHistoryIndex(groups, "removed", loading = false, profileMissing = false))
    }

    @Test fun repeatedSubmissionGetsNewTurnAndSuccessfulDraftDoesNotReturnOnRestart() = runBlocking {
        val dir = Files.createTempDirectory("studio-turns").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = ImageStudioController(scope, dir) { _, _, _ -> GeneratedImage(png, "png") }
            waitFor(controller) { !it.loading }
            repeat(2) { index ->
                controller.edit { it.copy(prompt = "same prompt") }
                controller.generate(settings, dir, allowed)
                waitFor(controller) { !it.running && it.history.size == index + 1 }
            }
            assertEquals(2, studioConversationGroups(controller.state.value.history).size)
            waitFor(controller) { File(dir, "draft.json").isFile &&
                org.json.JSONObject(File(dir, "draft.json").readText()).optString("prompt").isEmpty() }
            val restored = ImageStudioController(scope, dir) { _, _, _ -> error("Must not auto-generate") }
            waitFor(restored) { !it.loading }
            assertEquals("", restored.state.value.prompt)
            assertEquals(2, restored.state.value.history.size)
            assertNull(restored.state.value.submittedPrompt)
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun imageReturnedWhileCancellationArrivesIsStillSaved() = runBlocking {
        val dir = Files.createTempDirectory("studio-save-stop").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = ImageStudioController(scope, dir) { _, _, _ ->
                currentCoroutineContext().cancel()
                GeneratedImage(png, "png")
            }
            waitFor(controller) { !it.loading }
            controller.edit { it.copy(prompt = "draw") }
            controller.generate(settings, dir, allowed)
            waitFor(controller) { !it.running }
            assertEquals(1, controller.state.value.history.size)
            assertEquals(1, ImageStudioStore(File(dir, "history.json")).load().size)
            assertTrue(File(controller.state.value.history.single().path).readBytes().contentEquals(png))
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun corruptDraftIsBackedUpWithoutHidingHistoryOrBlockingGeneration() = runBlocking {
        val dir = Files.createTempDirectory("studio-bad-draft").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val original = dev.pocketagent.data.StudioArtwork("old", "/tmp/old.png", "old", "model", "auto", 0, 0)
            ImageStudioStore(File(dir, "history.json")).save(listOf(original))
            File(dir, "draft.json").writeText("broken draft")
            val controller = ImageStudioController(scope, dir) { _, _, _ -> GeneratedImage(png, "png") }
            waitFor(controller) { !it.loading }
            assertEquals(listOf(original), controller.state.value.history)
            assertTrue(dir.listFiles()!!.any { it.name.startsWith("draft-corrupt-") && it.readText() == "broken draft" })
            controller.edit { it.copy(prompt = "draw") }
            controller.generate(settings, dir, allowed)
            waitFor(controller) { !it.running }
            assertEquals(2, controller.state.value.history.size)
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun draftWriteFailureIsVisibleAndDoesNotDiscardExistingRecords() = runBlocking {
        val dir = Files.createTempDirectory("studio-draft-write").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = ImageStudioController(scope, dir) { _, _, _ -> error("No request") }
            waitFor(controller) { !it.loading }
            File(dir, "draft.json").mkdir()
            controller.edit { it.copy(prompt = "unsaved") }
            waitFor(controller) { it.status.orEmpty().contains("草稿保存失败") }
            assertFalse(File(dir, "draft.tmp").exists())
            assertEquals("unsaved", controller.state.value.prompt)
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun reusingOneArtworkResetsBatchAndPreservesFailedRequestUntilNextSubmission() = runBlocking {
        val dir = Files.createTempDirectory("studio-reuse").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = ImageStudioController(scope, dir) { _, _, _ -> error("service unavailable") }
            waitFor(controller) { !it.loading }
            controller.edit { it.copy(prompt = "failed prompt", count = 4) }
            controller.generate(settings, dir, allowed)
            waitFor(controller) { !it.running && it.error != null }
            val failure = controller.state.value.error
            controller.edit { it.copy(size = "1024x1536") }
            assertEquals(failure, controller.state.value.error)
            val artwork = dev.pocketagent.data.StudioArtwork("one", "/tmp/one.png", "original prompt", "original-model", "1024x1024", 0, 1, "original-profile")
            controller.reuse(artwork)
            assertEquals(1, controller.state.value.count)
            assertEquals("original prompt", controller.state.value.prompt)
            assertEquals("original-model", controller.state.value.model)
            assertEquals("original-profile", controller.state.value.profileId)
            assertEquals("1024x1024", controller.state.value.size)
            assertEquals(failure, controller.state.value.error)
            assertEquals("failed prompt", controller.state.value.submittedPrompt)
            assertFalse(controller.state.value.running)
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun failureNoticeDoesNotRepeatNoRetryWarning() {
        val original = "连接中断；服务端可能已计费，不会自动重试"
        val message = studioFailureMessage(IllegalStateException(original), 0, null)
        assertEquals(1, Regex("不会自动重试").findAll(message).count())
        assertTrue(message.contains("已完成 0 张"))
        assertTrue(studioFailureMessage(IllegalStateException("保存失败"), 1, null).contains("不会自动重试"))
    }

    @Test fun failedTransfersExposeOnlyBoundedTimingAndProtocolDiagnostics() {
        val details = transferFailureDetails(dev.pocketagent.agent.ImageGenerationProgress(
            stage = dev.pocketagent.agent.ImageGenerationStage.RECEIVING,
            receivedBytes = 2 * 1024 * 1024, waitMs = 45000, transferMs = 299000,
            readWaitMs = 298000, protocol = "h2"))
        assertTrue(details.contains("2048 KiB"))
        assertTrue(details.contains("正文读取等待 298s"))
        assertTrue(details.contains("h2"))
        assertEquals("", transferFailureDetails(null))
        assertEquals("", transferFailureDetails(dev.pocketagent.agent.ImageGenerationProgress(
            dev.pocketagent.agent.ImageGenerationStage.DECODING)))
    }

    @Test fun corruptHistoryIsProtectedFromOverwrite() = runBlocking {
        val dir = Files.createTempDirectory("studio-corrupt").toFile()
        val file = File(dir, "history.json").apply { writeText("corrupt original") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = ImageStudioController(scope, dir) { _, _, _ -> error("Must never submit") }
            waitFor(controller) { !it.loading }
            controller.edit { it.copy(prompt = "draw") }
            controller.generate(settings, dir, allowed)
            assertFalse(controller.state.value.running)
            assertEquals("corrupt original", file.readText())
        } finally { scope.cancel(); dir.deleteRecursively() }
    }
}
