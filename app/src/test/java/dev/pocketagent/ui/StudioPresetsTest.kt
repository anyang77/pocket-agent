package dev.pocketagent.ui

import dev.pocketagent.agent.ImageGenerationClient
import kotlinx.coroutines.*
import dev.pocketagent.data.AiSettings
import org.junit.Assert.*
import org.junit.Test

class StudioPresetsTest {
    @Test fun filtersGenerationFamiliesWithoutOrdinaryChatAndVisionModels() {
        val images = listOf("gpt-image-2.6", "gemini-3.1-flash-image-preview", "imagen-4.0", "dall-e-3", "black-forest-labs/FLUX.1-dev", "sdxl", "stable-diffusion-xl", "ideogram-v3", "recraft-v3", "doubao-seedream-4.0")
        val other = listOf("gpt-6.1-sol", "gpt-4o", "gemini-2.5-pro", "claude-sonnet", "text-embedding-3", "image-embedding-1", "image-captioner", "image-classification-1", "image-upscaler", "fluxion", "myimagechat", "sora-2")
        images.forEach { assertTrue(it, isStudioImageModel(it)) }
        other.forEach { assertFalse(it, isStudioImageModel(it)) }
        assertEquals(images.sortedWith(String.CASE_INSENSITIVE_ORDER), studioModelChoices(other + images, "", false))
    }

    @Test fun customNamesRemainAccessibleWithSearchAndNoSilentModelSubstitution() {
        val models = listOf(" gpt-image-2.6 ", "gpt-image-2.6", "", "CUSTOM-ART", "gpt-4o")
        assertEquals(listOf("gpt-image-2.6"), studioModelChoices(models, "", false))
        assertEquals(listOf("CUSTOM-ART"), studioModelChoices(models, " custom ", true))
        assertTrue(studioModelChoices(models, "custom", false).isEmpty())
        assertEquals(3, studioModelChoices(models, "", true).size)
    }

    @Test fun templatesAreUniqueEditableAndUseSupportedSizes() {
        assertEquals(studioTemplates.size, studioTemplates.map { it.id }.distinct().size)
        assertTrue(studioTemplates.size >= 6)
        studioTemplates.forEach { template ->
            assertTrue(template.size in ImageGenerationClient.SIZES)
            assertEquals(3, template.fields.size)
            val prompt = template.compose(listOf("用户主体", "", "  用户风格  "))
            assertTrue(prompt.contains("用户主体"))
            assertTrue(prompt.contains("用户风格"))
            assertFalse(prompt.contains(template.fields[0].example))
            assertFalse(prompt.contains("${template.fields[1].label}："))
            assertFalse(template.compose(emptyList()).contains("null"))
        }
    }

    @Test fun templatesCanBeDiscoveredByPurposeAndCombinedWithCategories() {
        assertEquals(12, studioTemplateChoices("全部", "").size)
        assertEquals(listOf("food"), studioTemplateChoices("摄影", "美食").map { it.id })
        assertTrue(studioTemplateChoices("插画", "美食").isEmpty())
        assertEquals(listOf("icon"), studioTemplateChoices("全部", " 应用 ").map { it.id })
        assertTrue(studioTemplateChoices("全部", "没有这种模板").isEmpty())
    }

    @Test fun templateDimensionsRespectKnownDallEModelsWithoutChangingCustomProviders() {
        assertEquals("1024x1792", studioTemplateSize("1024x1536", "dall-e-3"))
        assertEquals("1792x1024", studioTemplateSize("1536x1024", "openai/dall-e-3"))
        assertEquals("1024x1024", studioTemplateSize("1536x1024", "DALL-E-2"))
        assertEquals("1024x1536", studioTemplateSize("1024x1536", "gpt-image-2.6"))
        assertEquals("1024x1536", studioTemplateSize("1024x1536", "custom-dall-e-3"))
    }

    @Test fun catalogUsesSelectedServiceAndIgnoresLateOldResponses() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = CompletableDeferred<Unit>()
        try {
            val catalog = StudioModelCatalog(scope) { config ->
                if (config.baseUrl == "old") withContext(NonCancellable) { old.await(); listOf("old-image") }
                else listOf("new-image")
            }
            catalog.load(AiSettings(baseUrl = "old"))
            catalog.load(AiSettings(baseUrl = "new"))
            assertEquals(listOf("new-image"), catalog.state.value.models)
            old.complete(Unit)
            yield()
            assertEquals(listOf("new-image"), catalog.state.value.models)
            assertFalse(catalog.state.value.loading)
        } finally { old.complete(Unit); scope.cancel() }
    }

    @Test fun revokedNetworkClearsCatalogAndInvalidatesPendingResponse() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ready = CompletableDeferred<Unit>()
        try {
            val catalog = StudioModelCatalog(scope) { withContext(NonCancellable) { ready.await(); listOf("late-image") } }
            catalog.load(AiSettings())
            catalog.clear("网络访问未开启")
            ready.complete(Unit)
            yield()
            assertTrue(catalog.state.value.models.isEmpty())
            assertEquals("网络访问未开启", catalog.state.value.error)
            assertFalse(catalog.state.value.loading)
        } finally { ready.complete(Unit); scope.cancel() }
    }

    @Test fun catalogClearsOtherServiceResultsAndDoesNotExposeRawErrors() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val catalog = StudioModelCatalog(scope) { config ->
                if (config.baseUrl == "good") listOf("image-model") else error("secret-key-and-server-response")
            }
            catalog.load(AiSettings(baseUrl = "good"))
            assertEquals(listOf("image-model"), catalog.state.value.models)
            catalog.load(AiSettings(baseUrl = "bad"))
            assertTrue(catalog.state.value.models.isEmpty())
            assertNotNull(catalog.state.value.error)
            assertFalse(catalog.state.value.error!!.contains("secret-key"))
        } finally { scope.cancel() }
    }
}
