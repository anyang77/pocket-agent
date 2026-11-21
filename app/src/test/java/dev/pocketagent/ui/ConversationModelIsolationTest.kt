package dev.pocketagent.ui

import dev.pocketagent.data.AiSettings
import dev.pocketagent.data.ConversationModelSelection
import dev.pocketagent.data.ModelProfile
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class ConversationModelIsolationTest {
    private val profile = ModelProfile("p", "Provider", "https://a.example", "global-default", "secret-a", 128000, "auto")
    private fun session(id: Long, model: String) = AppUiState(currentConversationId = id,
        conversationModel = ConversationModelSelection("p", model, "high"))
    private fun router() = SessionStateRouter(AppUiState(modelProfiles = listOf(profile), workspacePath = "/work"))

    @Test fun switchingBackRestoresItsModelEvenAfterAnotherSessionChangesTheProfileDefault() {
        val router = router()
        router.show(session(1, "model-a"))
        assertEquals("model-a", router.value.settings.model)
        router.show(session(2, "model-b"))
        router.update { it.copy(settings = it.settings.copy(model = "model-c"),
            modelProfiles = listOf(profile.copy(model = "model-c"))) }
        router.show(session(1, "stale"))
        assertEquals("model-a", router.value.settings.model)
        assertEquals("high", router.value.settings.reasoningEffort)
        assertEquals("secret-a", router.value.settings.apiKey)
        router.show(session(2, "stale"))
        assertEquals("model-c", router.value.settings.model)
    }

    @Test fun hiddenModelSaveAndCatalogStayInTheOriginatingSession() = runTest {
        val router = router()
        router.show(session(1, "model-a"))
        router.show(session(2, "model-b"))
        withContext(router.context(1)) {
            router.update { it.copy(settings = it.settings.copy(model = "model-a2"),
                availableModels = listOf("model-a2"), modelsLoading = false,
                modelSettingsError = "error from A", modelReasoningEfforts = mapOf("model-a2" to listOf("high")),
                modelProfiles = listOf(profile.copy(model = "model-a2"))) }
            assertEquals("model-a2", router.value.settings.model)
        }
        assertEquals(2L, router.value.currentConversationId)
        assertEquals("model-b", router.value.settings.model)
        assertTrue(router.value.availableModels.isEmpty())
        assertNull(router.value.modelSettingsError)
        router.show(session(1, "stale"))
        assertEquals("model-a2", router.value.settings.model)
        assertEquals(listOf("model-a2"), router.value.availableModels)
        assertEquals(listOf("high"), router.value.modelReasoningEfforts["model-a2"])
        assertEquals("error from A", router.value.modelSettingsError)
    }

    @Test fun eachConversationKeepsItsProviderAndReasoningEffort() {
        val second = profile.copy(id = "q", apiKey = "secret-b", baseUrl = "https://b.example")
        val router = SessionStateRouter(AppUiState(modelProfiles = listOf(profile, second)))
        router.show(session(1, "model-a"))
        router.show(session(2, "model-b").copy(conversationModel = ConversationModelSelection("q", "model-b", "low")))
        assertEquals("secret-b", router.value.settings.apiKey)
        router.show(session(1, "stale"))
        assertEquals("p", router.value.activeProfileId)
        assertEquals("https://a.example", router.value.settings.baseUrl)
        assertEquals("high", router.value.settings.reasoningEffort)
        router.show(session(2, "stale"))
        assertEquals("q", router.value.activeProfileId)
        assertEquals("low", router.value.settings.reasoningEffort)
    }

    @Test fun deletedConversationsCallbacksNeverChangeTheVisibleModel() {
        val router = router()
        router.show(session(1, "model-a"))
        router.show(session(2, "model-b"))
        router.remove(1)
        var transformCalled = false
        router.forSession(1) {
            router.update { transformCalled = true; it.copy(settings = it.settings.copy(model = "late-save-a")) }
            try { router.value; fail("A deleted session must not read B's settings") }
            catch (_: kotlinx.coroutines.CancellationException) { }
        }
        assertFalse(transformCalled)
        assertEquals(2L, router.value.currentConversationId)
        assertEquals("model-b", router.value.settings.model)
        assertNull(router.snapshot(1))
    }

    @Test fun removedProviderDoesNotFallBackToAnotherProvidersCredentials() {
        val router = router()
        router.show(session(1, "model-a"))
        router.show(session(2, "model-b"))
        router.publish { it.copy(modelProfiles = emptyList(), settings = AiSettings(apiKey = "another-secret")) }
        router.show(session(1, "stale"))
        assertEquals("model-a", router.value.settings.model)
        assertEquals("", router.value.settings.apiKey)
        assertEquals("p", router.value.activeProfileId)
    }
}
