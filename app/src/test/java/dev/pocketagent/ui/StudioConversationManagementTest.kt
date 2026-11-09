package dev.pocketagent.ui

import dev.pocketagent.agent.*
import dev.pocketagent.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StudioConversationManagementTest {
    private val config = AiSettings(model = "image", apiKey = "test")
    private val allowed = AgentPermissions(mode = PermissionMode.AUTO, allowNetwork = true)
    private suspend fun waitFor(c: ImageStudioController, predicate: (ImageStudioState) -> Boolean) {
        withTimeout(5000) { while (!predicate(c.state.value)) delay(10) }
    }

    @Test fun newOpenDeleteAndRestartPreserveOriginalsAndConversationIdentity() = runBlocking {
        val dir = Files.createTempDirectory("studio-conversations").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = ImageStudioController(scope, dir) { _, _, _ -> GeneratedImage(byteArrayOf(1, 2), "png") }
            waitFor(c) { !it.loading }
            val legacy = c.state.value.conversationId
            c.edit { it.copy(prompt = "first", count = 2) }
            c.generate(config, dir, allowed)
            waitFor(c) { !it.running && it.history.size == 2 }
            val original = c.state.value.history.map { File(it.path) }
            val batch = c.state.value.history.first().requestId
            c.newConversation()
            val second = c.state.value.conversationId
            assertNotEquals(legacy, second)
            assertNull(c.state.value.submittedPrompt)
            c.edit { it.copy(prompt = "second", count = 1) }
            c.generate(config, dir, allowed)
            waitFor(c) { !it.running && it.history.size == 3 }
            assertEquals(second, c.state.value.history.first().conversationId)
            c.openConversation(legacy)
            assertEquals(legacy, c.state.value.conversationId)
            c.deleteRecords(legacy, batch)
            waitFor(c) { !it.managing && it.history.size == 1 }
            assertTrue(original.all { it.exists() })
            assertNull(c.state.value.submittedPrompt)
            c.openConversation(second)
            waitFor(c) { File(dir, "draft.json").exists() && org.json.JSONObject(File(dir, "draft.json").readText()).optString("conversation_id") == second }
            val restarted = ImageStudioController(scope, dir)
            waitFor(restarted) { !it.loading }
            assertEquals(second, restarted.state.value.conversationId)
            assertEquals(c.state.value.history, restarted.state.value.history)
            c.deleteRecords(second)
            waitFor(c) { !it.managing && it.history.isEmpty() }
            assertNotEquals(second, c.state.value.conversationId)
            assertTrue(ImageStudioStore(File(dir, "history.json")).load().isEmpty())
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun managingIsBlockedWhileGeneratingAndDeletingActiveBatchClearsGhostPrompt() = runBlocking {
        val dir = Files.createTempDirectory("studio-generation-lock").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ready = CompletableDeferred<Unit>()
        try {
            val c = ImageStudioController(scope, dir) { _, _, _ -> ready.await(); GeneratedImage(byteArrayOf(1), "png") }
            waitFor(c) { !it.loading }
            c.edit { it.copy(prompt = "test") }
            val id = c.state.value.conversationId
            c.generate(config, dir, allowed)
            c.newConversation(); c.openConversation("other"); c.deleteRecords()
            assertEquals(id, c.state.value.conversationId)
            assertFalse(c.state.value.managing)
            ready.complete(Unit)
            waitFor(c) { !it.running && it.history.size == 1 }
            c.deleteRecords(id, c.state.value.history.first().requestId)
            waitFor(c) { !it.managing && it.history.isEmpty() }
            assertNull(c.state.value.submittedPrompt)
            assertNull(c.state.value.activeRequestId)
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun failedDeleteDoesNotChangeHistoryAndCorruptHistoryIsProtected() = runBlocking {
        val dir = Files.createTempDirectory("studio-delete-fail").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val record = StudioArtwork("id", "/tmp/kept.png", "prompt", "model", "auto", 1, 1)
            val index = File(dir, "history.json")
            ImageStudioStore(index).save(listOf(record))
            val c = ImageStudioController(scope, dir)
            waitFor(c) { !it.loading }
            File(dir, "history.json.tmp").mkdir() // Force atomic staging to fail.
            c.deleteRecords()
            waitFor(c) { !it.managing && it.error != null }
            assertEquals(listOf(record), c.state.value.history)
            assertEquals(listOf(record), ImageStudioStore(index).load())
            index.writeText("corrupt")
            val broken = ImageStudioController(scope, dir)
            waitFor(broken) { !it.loading }
            broken.deleteRecords()
            assertEquals("corrupt", index.readText())
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun placeholderUsesActualAspectRatioAndSafeAutomaticFallback() {
        assertEquals(2f / 3, studioPreviewRatio("1024x1536"), .001f)
        assertEquals(1.5f, studioPreviewRatio("1536x1024"), .001f)
        assertEquals(1f, studioPreviewRatio("auto"), .001f)
        assertEquals(1f, studioPreviewRatio("1024x0"), .001f)
    }
}
